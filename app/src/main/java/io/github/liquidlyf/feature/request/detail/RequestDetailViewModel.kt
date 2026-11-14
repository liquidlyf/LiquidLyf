package io.github.liquidlyf.feature.request.detail

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.backend.WorkerClient
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.profile.VerifiedDonationRecorder
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.safety.SafetyRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.core.util.NetworkMonitor
import io.github.liquidlyf.feature.request.create.PostNotice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

enum class ViewerRole { REQUESTER, DONOR }

/** A donor's offer statuses that show the requester's contact. */
private val CONTACT_STATUSES = setOf(ResponseStatus.OFFERED, ResponseStatus.CONFIRMED, ResponseStatus.DONATED)

/** Why this donor can't offer right now; the button is disabled with this reason. */
sealed interface OfferBlock {
    data object Closed : OfferBlock
    /** P1: no blood group on the profile yet. */
    data object NoBloodGroup : OfferBlock
    data object Incompatible : OfferBlock
    data class NotEligible(val eligibleFrom: Instant, val reasons: Set<IneligibleReason>) : OfferBlock
}

data class RequestDetailUiState(
    val loading: Boolean = true,
    val request: BloodRequest? = null,
    val geo: BdGeo? = null,
    val role: ViewerRole = ViewerRole.DONOR,
    /** The donor's own offer status, live from the request's responders map. */
    val myStatus: ResponseStatus? = null,
    val offerBlock: OfferBlock? = null,
    /**
     * The requester always; a donor only after offering (the rules allow nobody else), and no longer
     * once the offer is withdrawn or declined (doc 03: harassers), although the rules would still allow it.
     */
    val contact: RequestContact? = null,
    /** The requester's live list of offers, with phones. */
    val responses: List<DonorResponse> = emptyList(),
    val working: Boolean = false,
    val notice: PostNotice? = null,
) {
    /** Created offline and not on the server yet: the server time is still empty. */
    val waitingForNetwork: Boolean get() = request != null && request.createdAt == null
}

sealed interface DetailEvent {
    data class Message(@param:StringRes val text: Int) : DetailEvent

    /** Hidden with "Not for me": leave the screen. */
    data object Left : DetailEvent
}

/**
 * One request, for its requester or a donor (Phase 4.3). Live: the request (rules: listener 2)
 * and, for the requester, the offers (listener 3). Every action is one rules-checked batch in the
 * repositories; the Worker's personal pushes follow and may fail without harm.
 */
@HiltViewModel
class RequestDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val session: SessionManager,
    private val requests: RequestRepository,
    private val offers: OfferRepository,
    private val profiles: ProfileRepository,
    private val safety: SafetyRepository,
    private val worker: WorkerClient,
    private val geoRepository: GeoRepository,
    private val eligibility: EligibilityCalculator,
    private val clock: Clock,
    network: NetworkMonitor,
) : ViewModel() {

    /** Offline, an action's batch waits in Firestore's queue; the screen says so. */
    val online: StateFlow<Boolean> = network.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // The RequestDetail route's arguments, by property name (works in plain unit tests too).
    val requestId: String = checkNotNull(savedState.get<String>("id"))
    private val noticeArg: String? = savedState.get<String>("notice")
    private val me: UserProfile? get() = (session.state.value as? SessionState.Ready)?.profile

    private val state = MutableStateFlow(
        RequestDetailUiState(notice = noticeArg?.let { n -> PostNotice.entries.firstOrNull { it.name == n } }),
    )
    val uiState: StateFlow<RequestDetailUiState> = state.asStateFlow()

    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val eventFlow: Flow<DetailEvent> = events.receiveAsFlow()

    private var listeningToResponses = false
    private var contactLoaded = false
    private var donationChecked = false
    private val donations = VerifiedDonationRecorder(profiles, offers, requests, clock)

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo) }
            requests.observe(requestId).collect(::onRequest)
        }
    }

    private fun onRequest(r: BloodRequest?) {
        val profile = me
        val mine = r != null && profile != null && r.requesterUid == profile.uid
        val myStatus = if (r != null && profile != null) r.responders[profile.uid]?.status else null
        val seesContact = mine || myStatus in CONTACT_STATUSES
        state.update {
            it.copy(
                loading = false,
                request = r,
                role = if (mine) ViewerRole.REQUESTER else ViewerRole.DONOR,
                myStatus = myStatus,
                offerBlock = if (r != null && profile != null && !mine) offerBlock(r, profile) else null,
                contact = if (seesContact) it.contact else null,
            )
        }
        if (r == null || profile == null) return
        if (mine) listenToResponses(profile.uid)
        if (seesContact) loadContact()
        if (myStatus == ResponseStatus.DONATED) recordDonationOnce(r)
    }

    private fun offerBlock(r: BloodRequest, p: UserProfile): OfferBlock? {
        val now = clock.now()
        val group = p.bloodGroup
        val e = eligibility.evaluate(p.birthYear, p.weightOk, p.gender, p.lastDonationAt, now)
        return when {
            !r.isActive(now) -> OfferBlock.Closed
            group == null -> OfferBlock.NoBloodGroup
            group !in r.acceptedGroups -> OfferBlock.Incompatible
            !e.eligibleNow -> OfferBlock.NotEligible(e.eligibleFrom, e.reasons)
            else -> null
        }
    }

    private fun listenToResponses(myUid: String) {
        if (listeningToResponses) return
        listeningToResponses = true
        viewModelScope.launch {
            offers.responders(requestId, myUid).collect { list ->
                state.update { it.copy(responses = list.sortedWith(compareBy({ order(it.status) }, { it.createdAt }))) }
            }
        }
    }

    private fun loadContact() {
        if (contactLoaded) return
        contactLoaded = true
        viewModelScope.launch {
            when (val c = requests.contact(requestId)) {
                is AppResult.Success -> state.update { it.copy(contact = c.data) }
                is AppResult.Failure -> contactLoaded = false // try again on the next update
            }
        }
    }

    /** Contract 12: my verified donation, once per request (also done from the push and the inbox). */
    private fun recordDonationOnce(r: BloodRequest) {
        if (donationChecked) return
        donationChecked = true
        val uid = me?.uid ?: return
        viewModelScope.launch {
            when (val result = donations.recordIfMissing(uid, r)) {
                is AppResult.Success -> if (result.data) session.refresh() // eligibility and alert topics change
                is AppResult.Failure -> donationChecked = false
            }
        }
    }

    // ---- Donor actions ----

    /** "I can donate": shares my phone with the requester (the consent point), then tells them. */
    fun offer(message: String?) = act(R.string.detail_offer_sent) { r, p ->
        offers.offer(r, p, message?.trim()?.ifEmpty { null }).also { result ->
            if (result is AppResult.Success) viewModelScope.launch { worker.notifyOffer(r.id, p.uid) }
        }
    }

    fun withdraw() = act(R.string.detail_withdrawn) { r, p -> offers.withdraw(r, p.uid) }

    /** "Not for me": hidden from Home on every device. */
    fun hide() {
        val p = me ?: return
        viewModelScope.launch {
            when (val result = profiles.hideRequest(p, requestId)) {
                is AppResult.Success -> {
                    session.refresh()
                    events.send(DetailEvent.Left)
                }
                is AppResult.Failure -> events.send(DetailEvent.Message(result.error.messageRes))
            }
        }
    }

    fun report(reason: ReportReason, note: String?) = act(R.string.detail_reported) { r, p ->
        safety.reportRequest(r, p.uid, reason, note?.trim()?.ifEmpty { null })
    }

    // ---- Requester actions ----

    fun confirm(response: DonorResponse) = decide(response, ResponseStatus.CONFIRMED, R.string.detail_confirmed)

    fun decline(response: DonorResponse) = decide(response, ResponseStatus.DECLINED, R.string.detail_declined)

    fun markDonated(response: DonorResponse) = decide(response, ResponseStatus.DONATED, R.string.detail_marked_donated)

    private fun decide(response: DonorResponse, to: ResponseStatus, @StringRes done: Int) = act(done) { r, _ ->
        offers.decide(r, response, to).also { result ->
            if (result is AppResult.Success) viewModelScope.launch { worker.notifyOffer(r.id, response.donorUid) }
        }
    }

    /** "Found blood" (FULFILLED) or "No longer needed" (CANCELLED); donors still waiting are told. */
    fun close(status: RequestStatus, reason: String?) = act(R.string.detail_closed) { r, _ ->
        requests.close(r, status, reason?.trim()?.ifEmpty { null }).also { result ->
            if (result is AppResult.Success) viewModelScope.launch { worker.notifyResponders(r.id) }
        }
    }

    fun edit(notes: String?, unitsNeeded: Int?, neededBy: Instant?) = act(R.string.detail_saved) { r, _ ->
        requests.edit(r, notes, unitsNeeded, neededBy)
    }

    /** Runs one action at a time; the result is a snackbar message. */
    private fun act(@StringRes done: Int, block: suspend (BloodRequest, UserProfile) -> AppResult<Unit>) {
        val s = state.value
        val r = s.request ?: return
        val p = me ?: return
        if (s.working) return
        state.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = block(r, p)
            state.update { it.copy(working = false) }
            events.send(DetailEvent.Message(if (result is AppResult.Failure) result.error.messageRes else done))
        }
    }

    private companion object {
        /** Requester's list: active offers first, then the finished ones. */
        fun order(s: ResponseStatus): Int = when (s) {
            ResponseStatus.CONFIRMED -> 0
            ResponseStatus.OFFERED -> 1
            ResponseStatus.DONATED -> 2
            ResponseStatus.DECLINED -> 3
            ResponseStatus.WITHDRAWN -> 4
        }
    }
}
