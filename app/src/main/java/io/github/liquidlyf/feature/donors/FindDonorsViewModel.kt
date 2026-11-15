package io.github.liquidlyf.feature.donors

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.backend.WorkerClient
import io.github.liquidlyf.core.data.config.FeatureFlags
import io.github.liquidlyf.core.data.donor.DonorRepository
import io.github.liquidlyf.core.data.donor.DonorSearch
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.safety.SafetyRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the filter form holds. A search runs only on "Search" (read budget, 6.1). */
data class DonorFilters(
    val groups: Set<BloodGroup> = emptySet(),
    val division: String? = null,
    val district: String? = null,
    /** Null = the whole district. */
    val upazila: String? = null,
    val eligibleNowOnly: Boolean = true,
) {
    val ready: Boolean get() = groups.isNotEmpty() && district != null
}

/** The donor's sheet: my OPEN requests their group can give to, to pick one for the ask (6.2). */
data class AskSheet(
    val donor: DonorCard,
    /** Null while loading. */
    val requests: List<BloodRequest>? = null,
    /** True when I have open requests, but none this donor can give to. */
    val noneCompatible: Boolean = false,
    val selected: String? = null,
)

data class FindDonorsUiState(
    /** Null while the saver-mode switch is read. */
    val enabled: Boolean? = null,
    val filters: DonorFilters = DonorFilters(),
    /** The filters of the results on screen; null before the first search. */
    val applied: DonorFilters? = null,
    val results: List<DonorCard>? = null,
    val total: Long? = null,
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: AppError? = null,
    val geo: BdGeo? = null,
    val me: UserProfile? = null,
    val ask: AskSheet? = null,
    val asking: Boolean = false,
    /** "requestId:donorUid" asked on this screen, so the sheet says "Asked". */
    val asked: Set<String> = emptySet(),
)

sealed interface FindDonorsEvent {
    data class Message(@param:StringRes val text: Int, val arg: String? = null) : FindDonorsEvent
}

/**
 * Phase 6.1/6.2: find listed donors, and ask one to donate for one of my open requests. Results
 * never carry a phone number (donor cards have none); the donor decides whether to offer.
 */
@HiltViewModel
class FindDonorsViewModel @Inject constructor(
    private val session: SessionManager,
    private val donors: DonorRepository,
    private val requests: RequestRepository,
    private val safety: SafetyRepository,
    private val profiles: ProfileRepository,
    private val worker: WorkerClient,
    private val flags: FeatureFlags,
    private val geoRepository: GeoRepository,
    private val eligibility: EligibilityCalculator,
    private val clock: Clock,
) : ViewModel() {
    private val state = MutableStateFlow(FindDonorsUiState())
    val uiState: StateFlow<FindDonorsUiState> = state.asStateFlow()
    private val events = Channel<FindDonorsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    /** The last card the server returned (filtered ones too): the next page starts after it. */
    private var lastRaw: DonorCard? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val enabled = flags.donorSearchEnabled()
            state.update { it.copy(enabled = enabled) }
        }
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo) }
        }
        viewModelScope.launch {
            session.state.collect { s ->
                val me = (s as? SessionState.Ready)?.profile ?: return@collect
                state.update {
                    // Start in my own area; keep what the user changed since.
                    val f = if (it.me == null) it.filters.copy(division = me.area.division, district = me.area.district) else it.filters
                    it.copy(me = me, filters = f)
                }
            }
        }
    }

    fun toggleGroup(group: BloodGroup) = editFilters {
        copy(groups = if (group in groups) groups - group else groups + group)
    }

    fun setDivision(code: String) = editFilters { if (code == division) this else copy(division = code, district = null, upazila = null) }

    fun setDistrict(code: String) = editFilters { if (code == district) this else copy(district = code, upazila = null) }

    fun setUpazila(code: String?) = editFilters { copy(upazila = code) }

    fun setEligibleNowOnly(on: Boolean) = editFilters { copy(eligibleNowOnly = on) }

    fun search() {
        val s = state.value
        val f = s.filters
        if (!f.ready || s.searching) return
        val query = f.toSearch()
        lastRaw = null
        state.update { it.copy(applied = f, searching = true, results = null, total = null, error = null, endReached = false) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val now = clock.now()
            val count = async { donors.count(query, now) }
            when (val page = donors.search(query, now)) {
                is AppResult.Success -> {
                    lastRaw = page.data.lastOrNull()
                    state.update {
                        it.copy(results = visible(page.data), searching = false, endReached = page.data.size < DonorRepository.PAGE_SIZE)
                    }
                }
                is AppResult.Failure -> state.update { it.copy(searching = false, error = page.error) }
            }
            val total = count.await()
            if (total is AppResult.Success) {
                val mine = if (meMatches(query, now)) 1 else 0
                state.update { it.copy(total = (total.data - mine).coerceAtLeast(0)) }
            }
        }
    }

    fun loadMore() {
        val s = state.value
        val f = s.applied ?: return
        val after = lastRaw ?: return
        if (s.loadingMore || s.endReached || s.searching) return
        state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val page = donors.search(f.toSearch(), clock.now(), after)) {
                is AppResult.Success -> {
                    lastRaw = page.data.lastOrNull() ?: after
                    state.update {
                        it.copy(
                            results = (it.results.orEmpty() + visible(page.data)).distinctBy { d -> d.uid },
                            loadingMore = false,
                            endReached = page.data.size < DonorRepository.PAGE_SIZE,
                        )
                    }
                }
                is AppResult.Failure -> {
                    state.update { it.copy(loadingMore = false) }
                    events.send(FindDonorsEvent.Message(page.error.messageRes))
                }
            }
        }
    }

    // ---- Ask to donate (6.2) ----

    fun openAsk(donor: DonorCard) {
        val me = state.value.me ?: return
        state.update { it.copy(ask = AskSheet(donor)) }
        viewModelScope.launch {
            val now = clock.now()
            val open = when (val mine = requests.mine(me.uid)) {
                is AppResult.Success -> mine.data.filter { it.isActive(now) }
                is AppResult.Failure -> {
                    events.send(FindDonorsEvent.Message(mine.error.messageRes))
                    state.update { it.copy(ask = null) }
                    return@launch
                }
            }
            val compatible = open.filter { donor.bloodGroup in it.acceptedGroups }
            state.update { s ->
                val sheet = s.ask
                if (sheet == null || sheet.donor.uid != donor.uid) return@update s
                s.copy(
                    ask = sheet.copy(
                        requests = compatible,
                        noneCompatible = open.isNotEmpty() && compatible.isEmpty(),
                        selected = compatible.singleOrNull()?.id,
                    ),
                )
            }
        }
    }

    fun selectRequest(requestId: String) = state.update { s -> s.copy(ask = s.ask?.copy(selected = requestId)) }

    fun closeAsk() = state.update { it.copy(ask = null) }

    fun ask() {
        val s = state.value
        val sheet = s.ask ?: return
        val request = sheet.requests?.firstOrNull { it.id == sheet.selected } ?: return
        val donor = sheet.donor
        if (s.asking || key(request, donor) in s.asked) return
        if (request.askCount >= SafetyRepository.MAX_ASKS) {
            viewModelScope.launch { events.send(FindDonorsEvent.Message(R.string.donors_ask_request_full)) }
            return
        }
        state.update { it.copy(asking = true) }
        viewModelScope.launch {
            when (val result = safety.askToDonate(request, donor)) {
                is AppResult.Failure -> {
                    state.update { it.copy(asking = false) }
                    // The ask's id is fixed per donor and request, so asking twice is refused.
                    val text = if (result.error == AppError.PermissionDenied) R.string.donors_ask_already else result.error.messageRes
                    events.send(FindDonorsEvent.Message(text))
                }
                is AppResult.Success -> {
                    state.update { st ->
                        st.copy(
                            asked = st.asked + key(request, donor),
                            ask = st.ask?.let { a ->
                                a.copy(requests = a.requests?.map { if (it.id == request.id) it.copy(askCount = it.askCount + 1) else it })
                            },
                        )
                    }
                    val pushed = worker.notifyAsk(request.id, donor.uid)
                    state.update { it.copy(asking = false, ask = null) }
                    events.send(
                        when {
                            pushed is AppResult.Success -> FindDonorsEvent.Message(R.string.donors_asked, donor.displayName)
                            (pushed as AppResult.Failure).error == AppError.DailyAskLimit -> FindDonorsEvent.Message(R.string.error_daily_ask_limit)
                            else -> FindDonorsEvent.Message(R.string.donors_ask_no_alert)
                        },
                    )
                }
            }
        }
    }

    fun isAsked(request: BloodRequest, donor: DonorCard): Boolean = key(request, donor) in state.value.asked

    /** 7.1: report the person on the open sheet. */
    fun reportDonor(reason: ReportReason, note: String?) {
        val donor = state.value.ask?.donor ?: return
        val me = state.value.me ?: return
        viewModelScope.launch {
            val result = safety.reportUser(donor.uid, me.uid, reason, note?.trim()?.ifEmpty { null })
            events.send(FindDonorsEvent.Message(if (result is AppResult.Failure) result.error.messageRes else R.string.reported_thanks))
        }
    }

    /** 7.2: block the person on the open sheet; they leave the results at once. */
    fun blockDonor() {
        val donor = state.value.ask?.donor ?: return
        val me = state.value.me ?: return
        viewModelScope.launch {
            when (val result = profiles.block(me, donor.uid)) {
                is AppResult.Success -> {
                    session.refresh()
                    state.update { s -> s.copy(ask = null, results = s.results?.filter { it.uid != donor.uid }) }
                    events.send(FindDonorsEvent.Message(R.string.blocked_done))
                }
                is AppResult.Failure -> events.send(FindDonorsEvent.Message(result.error.messageRes))
            }
        }
    }

    private fun key(request: BloodRequest, donor: DonorCard) = "${request.id}:${donor.uid}"

    private fun editFilters(change: DonorFilters.() -> DonorFilters) = state.update { it.copy(filters = it.filters.change()) }

    private fun DonorFilters.toSearch() = DonorSearch(groups, district!!, upazila, eligibleNowOnly)

    /** Never me, never someone I blocked. */
    private fun visible(cards: List<DonorCard>): List<DonorCard> {
        val me = state.value.me ?: return cards
        return cards.filter { it.uid != me.uid && it.uid !in me.blockedUids }
    }

    /** My own card is in the count when it matches the search: leave it out. */
    private fun meMatches(q: DonorSearch, now: java.time.Instant): Boolean {
        val me = state.value.me ?: return false
        val group = me.bloodGroup ?: return false
        val area = if (q.upazila != null) me.area.upazila == q.upazila else me.area.district == q.district
        val eligible = !q.eligibleNowOnly || !eligibility.eligibleFrom(me.lastDonationAt, me.gender).isAfter(now)
        return me.showInDonorSearch && me.available && group in q.groups && area && eligible
    }
}
