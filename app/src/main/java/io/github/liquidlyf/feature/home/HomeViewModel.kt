package io.github.liquidlyf.feature.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.inbox.InboxRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.push.AlertStore
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.Eligibility
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.firebase.endOnListenError
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.core.util.NetworkMonitor
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val profile: UserProfile? = null,
    val eligibility: Eligibility? = null,
    /** What the switch shows: the saved value, or the user's tap until the write finishes. */
    val available: Boolean = false,
    val savingAvailability: Boolean = false,
    /** Null while the first feed snapshot loads, or when it failed ([feedError]). */
    val requests: List<BloodRequest>? = null,
    /** The feed couldn't load (STATE-01): an error with Retry, never "no one needs blood". */
    val feedError: AppError? = null,
    val geo: BdGeo? = null,
    /** My own open requests, newest first: "Your request" while it's open (FLOW-03, N6). */
    val ownRequests: List<BloodRequest> = emptyList(),
    /** False while my own requests load, so the screen doesn't jump when "Your request" arrives. */
    val ownLoaded: Boolean = true,
)

sealed interface HomeEvent {
    data class Message(@param:StringRes val text: Int) : HomeEvent
}

/**
 * Home (Phase 4.1): my availability and eligibility, and the open requests in my district that
 * accept my group (listener 1, doc 10 §5.1), without hidden, blocked, my own or expired ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val session: SessionManager,
    private val requests: RequestRepository,
    private val profiles: ProfileRepository,
    private val geoRepository: GeoRepository,
    private val eligibility: EligibilityCalculator,
    private val clock: Clock,
    private val network: NetworkMonitor,
    private val inbox: InboxRepository,
    private val alerts: AlertStore,
) : ViewModel() {

    private val profileFlow: Flow<UserProfile?> =
        session.state.map { (it as? SessionState.Ready)?.profile }.distinctUntilChanged()

    /** The user's own tap, shown until the write succeeds (then the profile has it) or fails. */
    private val pendingAvailable = MutableStateFlow<Boolean?>(null)
    private val geo = MutableStateFlow<BdGeo?>(null)

    /** Bumped by Retry, which starts the feed's listener again. */
    private val feedAttempt = MutableStateFlow(0)

    private val feed: Flow<FeedState> = combine(
        profileFlow.map { p -> p?.let { Triple(it.uid, it.area.district, it.bloodGroup) } }.distinctUntilChanged(),
        feedAttempt,
    ) { key, _ -> key }
        .flatMapLatest { key ->
            val group = key?.third
            if (key == null || group == null) {
                flowOf(FeedState(emptyList()))
            } else {
                // Not known yet (only an empty cache) and offline: say it couldn't load, not "no one
                // near you needs B+" (D's re-audit); online, it's still loading.
                combine(requests.feed(key.second, group), network.isOnline) { list, online ->
                    when {
                        list != null -> FeedState(list)
                        online -> FeedState(null)
                        else -> FeedState(null, AppError.Network)
                    }
                }
                    .onStart { emit(FeedState(null)) }
                    .endOnListenError("feed") { error ->
                        // Signed out under the listener: the screen is leaving, so quietly empty.
                        // Anything else: say it failed (STATE-01: "no requests" was false comfort).
                        emit(if (error == AppError.PermissionDenied) FeedState(emptyList()) else FeedState(null, error))
                    }
            }
        }

    /** Bumped when Home comes back into view (after posting a request, say), to look again. */
    private val ownAttempt = MutableStateFlow(0)
    private var resumedOnce = false

    /**
     * My open requests (FLOW-03): one small query for my latest ones, then a live listener on each
     * still open, so the offers count and units update. None open: no listener at all.
     */
    private val ownRequests: Flow<List<BloodRequest>?> = combine(profileFlow.map { it?.uid }.distinctUntilChanged(), ownAttempt) { uid, _ -> uid }
        .flatMapLatest { uid ->
            if (uid == null) {
                flowOf(emptyList())
            } else {
                flow<List<BloodRequest>?> {
                    val open = (requests.openMine(uid) as? AppResult.Success)?.data.orEmpty()
                        .filter { it.isActive(clock.now()) }
                    if (open.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            combine(open.map { r -> requests.observe(r.id).onStart { emit(r) } }) { live ->
                                live.filterNotNull().filter { it.isActive(clock.now()) }
                            },
                        )
                    }
                }.endOnListenError("own requests") { emit(emptyList()) }
            }
        }
        // Null until the first answer: the screen holds the list meanwhile instead of jumping.
        .onStart { emit(null) }

    val uiState: StateFlow<HomeUiState> = combine(profileFlow, feed, pendingAvailable, geo, ownRequests) { p, f, pending, g, own ->
        HomeUiState(
            profile = p,
            eligibility = p?.let { eligibility.evaluate(it, clock.now()) },
            available = pending ?: p?.available ?: false,
            savingAvailability = pending != null,
            requests = f.list?.let { visible(it, p) },
            feedError = f.error,
            geo = g,
            ownRequests = own.orEmpty(),
            ownLoaded = own != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Retry on the feed's error state. */
    fun retryFeed() = feedAttempt.update { it + 1 }

    /** Home is in view again: a request posted meanwhile shows as "Your request". The first time, the flow has just looked. */
    fun onResume() {
        if (resumedOnce) ownAttempt.update { it + 1 } else resumedOnce = true
    }

    /**
     * The bell's badge (phase-05 5.9): anything newer than my last visit to the inbox. One read
     * each time `inboxSeenAt` changes, plus this phone's own alert list.
     */
    val unreadInbox: StateFlow<Boolean> = profileFlow
        .map { p -> p?.let { it.uid to it.inboxSeenAt } }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            if (key == null) {
                flowOf(false)
            } else {
                val (uid, seenAt) = key
                val remote = flow { emit((inbox.hasNewer(uid, seenAt) as? AppResult.Success)?.data == true) }
                combine(remote, alerts.alerts) { newer, local ->
                    newer || local.any { seenAt == null || it.receivedAtMillis > seenAt.toEpochMilli() }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val online: StateFlow<Boolean> = network.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val events = Channel<HomeEvent>(Channel.BUFFERED)
    val eventFlow: Flow<HomeEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch { geo.value = geoRepository.geo() }
        // The tap is shown until the refreshed profile carries it.
        viewModelScope.launch {
            profileFlow.collect { p -> if (p != null && p.available == pendingAvailable.value) pendingAvailable.value = null }
        }
    }

    /**
     * The "Ready to donate" switch. Only the user's tap calls this (never a state change), so it
     * can't loop (BUG-009). The switch moves at once and moves back if the write fails.
     */
    fun onAvailableToggled(available: Boolean) {
        val p = (session.state.value as? SessionState.Ready)?.profile ?: return
        if (pendingAvailable.value != null) return
        pendingAvailable.value = available
        viewModelScope.launch {
            when (val result = profiles.setAvailable(p, available)) {
                is AppResult.Success -> {
                    session.refresh() // the new profile clears the pending tap; topics follow (PushTopicManager)
                    delay(PENDING_TIMEOUT_MS)
                    pendingAvailable.update { null } // in case the refresh never comes
                }
                is AppResult.Failure -> {
                    pendingAvailable.value = null
                    events.send(HomeEvent.Message(result.error.messageRes))
                }
            }
        }
    }

    /** Home's "List me", after the consent dialog (8.1): the same write as Settings → Privacy. */
    fun listMe() {
        val p = (session.state.value as? SessionState.Ready)?.profile ?: return
        viewModelScope.launch {
            when (val result = profiles.setListed(p, true)) {
                is AppResult.Success -> session.refresh()
                is AppResult.Failure -> events.send(HomeEvent.Message(result.error.messageRes))
            }
        }
    }

    private fun visible(all: List<BloodRequest>, p: UserProfile?): List<BloodRequest> {
        val now = clock.now()
        val hidden = p?.hiddenRequestIds.orEmpty().toSet()
        val blocked = p?.blockedUids.orEmpty().toSet()
        return all.filter { r ->
            r.isActive(now) && r.id !in hidden && r.requesterUid !in blocked && r.requesterUid != p?.uid
        }.sortedWith(compareBy({ urgencyOrder(it.urgency) }, { it.neededBy }))
    }

    private companion object {
        const val PENDING_TIMEOUT_MS = 10_000L

        fun urgencyOrder(u: Urgency): Int = when (u) {
            Urgency.CRITICAL -> 0
            Urgency.URGENT -> 1
            Urgency.PLANNED -> 2
        }
    }
}

/** Home's feed: the list, or null while it loads or when it failed with [error]. */
private data class FeedState(val list: List<BloodRequest>?, val error: AppError? = null)
