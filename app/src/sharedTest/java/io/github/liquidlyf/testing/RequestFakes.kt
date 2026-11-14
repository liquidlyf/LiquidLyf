package io.github.liquidlyf.testing

import io.github.liquidlyf.core.data.backend.Dispatch
import io.github.liquidlyf.core.data.backend.WorkerClient
import io.github.liquidlyf.core.data.hospital.Hospital
import io.github.liquidlyf.core.data.hospital.HospitalRepository
import io.github.liquidlyf.core.data.hospital.HospitalSearch
import io.github.liquidlyf.core.data.request.OfferRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.safety.SafetyRepository
import io.github.liquidlyf.core.domain.BloodCompatibility
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.NetworkMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant

/** Requests in memory. [createGate], when set, holds create() like an offline Firestore write. */
class FakeRequestRepository(private val now: Instant = TestData.NOW) : RequestRepository {
    val requests = MutableStateFlow<Map<String, BloodRequest>>(emptyMap())
    val contacts = mutableMapOf<String, RequestContact>()
    val created = mutableListOf<Pair<String, RequestDraft>>()
    val closed = mutableListOf<Triple<String, RequestStatus, String?>>()
    var createGate: CompletableDeferred<Unit>? = null
    /** create() calls that started, including ones still held by [createGate]. */
    var createStarted = 0
    var failNext: AppError? = null
    private var nextId = 1

    fun put(vararg r: BloodRequest) = requests.update { all -> all + r.associateBy { it.id } }

    private fun <T> result(block: () -> AppResult<T>): AppResult<T> =
        failNext?.let { failNext = null; AppResult.Failure(it) } ?: block()

    override fun feed(district: String, group: BloodGroup): Flow<List<BloodRequest>> = requests.map { all ->
        all.values.filter { it.status == RequestStatus.OPEN && it.district == district && group in it.acceptedGroups }
            .sortedBy { it.neededBy }
    }

    override suspend fun nearby(district: String, after: BloodRequest?, limit: Long) = result {
        val all = requests.value.values.filter { it.status == RequestStatus.OPEN && it.district == district }.sortedBy { it.neededBy }
        val start = after?.let { a -> all.indexOfFirst { it.id == a.id } + 1 } ?: 0
        AppResult.Success(all.drop(start).take(limit.toInt()))
    }

    override fun observe(requestId: String): Flow<BloodRequest?> = requests.map { it[requestId] }

    override suspend fun byIds(ids: List<String>) = result { AppResult.Success(ids.mapNotNull { requests.value[it] }) }

    override suspend fun mine(uid: String, after: BloodRequest?, limit: Long) = result {
        val all = requests.value.values.filter { it.requesterUid == uid }.sortedByDescending { it.createdAt }
        val start = after?.let { a -> all.indexOfFirst { it.id == a.id } + 1 } ?: 0
        AppResult.Success(all.drop(start).take(limit.toInt()))
    }

    override fun newRequestId(): String = "req-${nextId++}"

    override suspend fun create(requester: UserProfile, draft: RequestDraft, requestId: String): AppResult<String> {
        createStarted++
        createGate?.await()
        return result {
            created += requestId to draft
            put(TestData.request(requestId, requesterUid = requester.uid, group = draft.bloodGroup, district = draft.district).copy(
                component = draft.component,
                acceptCompatible = draft.acceptCompatible,
                acceptedGroups = BloodCompatibility.acceptedGroups(draft.bloodGroup, draft.component, draft.acceptCompatible),
                unitsNeeded = draft.unitsNeeded,
                hospitalName = draft.hospitalName,
                upazila = draft.upazila,
                urgency = draft.urgency,
                neededBy = draft.neededBy,
                notes = draft.notes,
                expiresAt = Validators.expiresAt(draft.neededBy),
            ))
            contacts[requestId] = RequestContact(requester.uid, draft.contactName, draft.contactPhone, draft.patientName, draft.wardOrBed)
            AppResult.Success(requestId)
        }
    }

    override suspend fun close(request: BloodRequest, status: RequestStatus, reason: String?) = result {
        closed += Triple(request.id, status, reason)
        put(request.copy(status = status, closedReason = reason))
        AppResult.Success(Unit)
    }

    override suspend fun edit(request: BloodRequest, notes: String?, unitsNeeded: Int?, neededBy: Instant?) = result {
        put(request.copy(
            notes = notes ?: request.notes,
            unitsNeeded = unitsNeeded ?: request.unitsNeeded,
            neededBy = neededBy ?: request.neededBy,
            expiresAt = neededBy?.let(Validators::expiresAt) ?: request.expiresAt,
        ))
        AppResult.Success(Unit)
    }

    override suspend fun contact(requestId: String) = result { AppResult.Success(contacts[requestId]) }
}

/**
 * Offers in memory. Like the real batch, an offer or a decision also updates the request's
 * responders map (and units/status for DONATED) in [requests].
 */
class FakeOfferRepository(private val requests: FakeRequestRepository) : OfferRepository {
    val responses = MutableStateFlow<Map<Pair<String, String>, DonorResponse>>(emptyMap())
    val decisions = mutableListOf<Triple<String, String, ResponseStatus>>()
    var failNext: AppError? = null

    private fun <T> result(block: () -> AppResult<T>): AppResult<T> =
        failNext?.let { failNext = null; AppResult.Failure(it) } ?: block()

    override fun responders(requestId: String, requesterUid: String): Flow<List<DonorResponse>> =
        responses.map { all -> all.values.filter { it.requestId == requestId && it.requesterUid == requesterUid } }

    override suspend fun myOffer(requestId: String, donorUid: String) = result { AppResult.Success(responses.value[requestId to donorUid]) }

    override suspend fun mine(donorUid: String, after: DonorResponse?, limit: Long) = result {
        val all = responses.value.values.filter { it.donorUid == donorUid }.sortedByDescending { it.createdAt }
        val start = after?.let { a -> all.indexOfFirst { it.requestId == a.requestId } + 1 } ?: 0
        AppResult.Success(all.drop(start).take(limit.toInt()))
    }

    override suspend fun offer(request: BloodRequest, donor: UserProfile, message: String?) = result {
        val group = donor.bloodGroup ?: return@result AppResult.Failure(AppError.InvalidInput)
        val response = DonorResponse(
            donor.uid, request.id, request.requesterUid, ResponseStatus.OFFERED, Validators.displayName(donor.fullName),
            group, donor.phone, message, TestData.NOW, TestData.NOW,
        )
        responses.update { it + ((request.id to donor.uid) to response) }
        requests.put(request.copy(
            responders = request.responders + (donor.uid to Responder(response.donorDisplayName, group, ResponseStatus.OFFERED)),
            responseCount = request.responseCount + 1,
        ))
        AppResult.Success(Unit)
    }

    override suspend fun withdraw(request: BloodRequest, donorUid: String) = setStatus(request, donorUid, ResponseStatus.WITHDRAWN)

    override suspend fun decide(request: BloodRequest, response: DonorResponse, to: ResponseStatus): AppResult<Unit> {
        decisions += Triple(request.id, response.donorUid, to)
        return setStatus(request, response.donorUid, to)
    }

    private fun setStatus(request: BloodRequest, donorUid: String, to: ResponseStatus) = result {
        val key = request.id to donorUid
        responses.update { all -> all[key]?.let { all + (key to it.copy(status = to)) } ?: all }
        val entry = request.responders[donorUid] ?: return@result AppResult.Failure(AppError.NotFound)
        val units = if (to == ResponseStatus.DONATED) request.unitsFulfilled + 1 else request.unitsFulfilled
        requests.put(request.copy(
            responders = request.responders + (donorUid to entry.copy(status = to)),
            unitsFulfilled = units,
            status = if (units >= request.unitsNeeded) RequestStatus.FULFILLED else request.status,
        ))
        AppResult.Success(Unit)
    }
}

class FakeSafetyRepository : SafetyRepository {
    val reports = mutableListOf<Pair<String, ReportReason>>()

    override suspend fun reportRequest(request: BloodRequest, reporterUid: String, reason: ReportReason, note: String?): AppResult<Unit> {
        reports += request.id to reason
        return AppResult.Success(Unit)
    }

    override suspend fun reportUser(targetUid: String, reporterUid: String, reason: ReportReason, note: String?) = AppResult.Success(Unit)

    override suspend fun askToDonate(request: BloodRequest, donor: DonorCard) = AppResult.Success(Unit)
}

/** Records every Worker call; [dispatchResult] is what dispatch answers. */
class FakeWorkerClient : WorkerClient {
    val dispatched = mutableListOf<String>()
    val offerNotices = mutableListOf<Pair<String, String>>()
    val responderNotices = mutableListOf<String>()
    var dispatchResult: AppResult<Dispatch> = AppResult.Success(Dispatch(tier = 1, alreadyDispatched = false))

    override suspend fun dispatch(requestId: String): AppResult<Dispatch> {
        dispatched += requestId
        return dispatchResult
    }

    override suspend fun notifyOffer(requestId: String, donorUid: String): AppResult<Unit> {
        offerNotices += requestId to donorUid
        return AppResult.Success(Unit)
    }

    override suspend fun notifyResponders(requestId: String): AppResult<Unit> {
        responderNotices += requestId
        return AppResult.Success(Unit)
    }
}

class FakeHospitalRepository(private val all: List<Hospital> = emptyList()) : HospitalRepository {
    val queries = mutableListOf<String>()

    override suspend fun search(district: String, query: String, limit: Int): List<Hospital> {
        queries += query
        return HospitalSearch.search(all, district, query, limit)
    }
}

class FakeNetworkMonitor(online: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(online)
}

/** A plain OPEN request; override what a test cares about with copy(). */
fun TestData.request(
    id: String = "req-1",
    requesterUid: String = "requester-1",
    group: BloodGroup = BloodGroup.B_POS,
    district: String = DHAKA_SOUTH.district,
    status: RequestStatus = RequestStatus.OPEN,
    neededBy: Instant = NOW.plus(Duration.ofHours(6)),
) = BloodRequest(
    id = id, requesterUid = requesterUid, requesterDisplayName = "Karim H.", bloodGroup = group,
    component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, acceptedGroups = listOf(group),
    unitsNeeded = 1, unitsFulfilled = 0, condition = null, hospitalId = null, hospitalName = "Dhaka Medical College Hospital",
    hospitalAddress = null, division = DHAKA_SOUTH.division, district = district, upazila = DHAKA_SOUTH.upazila,
    location = null, urgency = Urgency.CRITICAL, neededBy = neededBy, notes = null, flagged = false, status = status,
    closedReason = null, responseCount = 0, reportCount = 0, askCount = 0, responders = emptyMap(), fanoutTier = 1,
    nextTierAt = null, createdAt = NOW, updatedAt = NOW, expiresAt = Validators.expiresAt(neededBy),
)

/** In memory; [cleared] counts sign-out clears. */
class FakeAlertStore : io.github.liquidlyf.core.data.push.AlertStore {
    private val state = MutableStateFlow<List<io.github.liquidlyf.core.data.push.LocalAlert>>(emptyList())
    val shown = mutableMapOf<String, Int>()
    val mine = mutableSetOf<String>()
    var cleared = 0

    override val alerts: Flow<List<io.github.liquidlyf.core.data.push.LocalAlert>> = state

    override suspend fun shownCount(requestId: String): Int = shown[requestId] ?: 0

    override suspend fun recordShown(alert: io.github.liquidlyf.core.data.push.LocalAlert) {
        shown[alert.requestId] = (shown[alert.requestId] ?: 0) + 1
        state.value = listOf(alert) + state.value
    }

    override suspend fun rememberMyRequest(requestId: String) {
        mine += requestId
    }

    override suspend fun isMyRequest(requestId: String): Boolean = requestId in mine

    override suspend fun clear() {
        cleared++
        shown.clear()
        mine.clear()
        state.value = emptyList()
    }
}

/** The personal inbox in memory, newest first by [InboxItem.createdAt]. */
class FakeInboxRepository : io.github.liquidlyf.core.data.inbox.InboxRepository {
    val items = mutableListOf<io.github.liquidlyf.core.model.InboxItem>()
    var reads = 0

    override suspend fun page(
        uid: String,
        after: io.github.liquidlyf.core.model.InboxItem?,
        limit: Long,
    ): AppResult<List<io.github.liquidlyf.core.model.InboxItem>> {
        reads++
        val sorted = items.sortedByDescending { it.createdAt }
        val start = after?.let { a -> sorted.indexOfFirst { it.id == a.id } + 1 } ?: 0
        return AppResult.Success(sorted.drop(start).take(limit.toInt()))
    }

    override suspend fun hasNewer(uid: String, since: java.time.Instant?): AppResult<Boolean> {
        reads++
        return AppResult.Success(items.any { since == null || (it.createdAt != null && it.createdAt.isAfter(since)) })
    }
}

/** Records what would be scheduled. */
class FakeReminders : io.github.liquidlyf.core.data.push.Reminders {
    var eligibleAgain: java.time.Instant? = null
    val ending = mutableMapOf<String, java.time.Instant>()
    var cancelledAll = 0

    override fun scheduleEligibleAgain(at: java.time.Instant?) {
        eligibleAgain = at
    }

    override fun scheduleRequestEnding(requestId: String, neededBy: java.time.Instant) {
        ending[requestId] = neededBy
    }

    override fun cancelRequestEnding(requestId: String) {
        ending -= requestId
    }

    override fun cancelAll() {
        cancelledAll++
        eligibleAgain = null
        ending.clear()
    }
}
