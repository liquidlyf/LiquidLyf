package io.github.liquidlyf.core.data.request

import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.liquidlyf.core.domain.StatusTransitions
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.firebase.DocState
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.docStates
import io.github.liquidlyf.core.firebase.dto.ContactDoc
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.UserStatsDoc
import io.github.liquidlyf.core.firebase.dto.contactRef
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.firebase.dto.requests
import io.github.liquidlyf.core.firebase.dto.statsRef
import io.github.liquidlyf.core.firebase.dto.toTimestamp
import io.github.liquidlyf.core.firebase.snapshots
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blood requests (write contract items 6–8). Live listeners only for the Home feed and the
 * request being viewed (doc 10 §5.1 rule 1); everything else is a one-off read.
 */
interface RequestRepository {
    /**
     * Home feed: OPEN requests in [district] that accept [group], soonest first. The caller drops
     * expired, hidden and blocked ones, and its own.
     */
    fun feed(district: String, group: BloodGroup): Flow<List<BloodRequest>>

    /**
     * Requests → Nearby: every OPEN request in [district], including groups I can't give, soonest
     * first, a page at a time (a plain read with pull-to-refresh; listeners are for Home only).
     */
    suspend fun nearby(district: String, after: BloodRequest? = null, limit: Long = PAGE_SIZE): AppResult<List<BloodRequest>>

    /** The request being viewed, live. Null if it doesn't exist. */
    fun observe(requestId: String): Flow<BloodRequest?>

    /**
     * The request being viewed, live, and whether "not there" is sure (the server) or not yet
     * known (offline, not saved on the phone): Request details shows "isn't available" only when
     * it's sure (STATE-05).
     */
    fun observeState(requestId: String): Flow<DocState<BloodRequest>> =
        observe(requestId).map { if (it == null) DocState.Missing else DocState.Found(it) }

    /** Several requests by id (My offers), in any status; missing ones are left out. */
    suspend fun byIds(ids: List<String>): AppResult<List<BloodRequest>>

    /** My requests, newest first, one page at a time ([after] = last item of the previous page). */
    suspend fun mine(uid: String, after: BloodRequest? = null, limit: Long = PAGE_SIZE): AppResult<List<BloodRequest>>

    /** A fresh id, known before the write, so an offline create can open the request at once. */
    fun newRequestId(): String

    /**
     * Contract 6. Returns [requestId] once the server has the batch. Offline, Firestore keeps the
     * write and this suspends until it's back online; the request shows locally meanwhile.
     */
    suspend fun create(requester: UserProfile, draft: RequestDraft, requestId: String = newRequestId()): AppResult<String>

    /** Contract 7: CANCELLED or FULFILLED, freeing a slot in the 1/3 open-request limit. */
    suspend fun close(request: BloodRequest, status: RequestStatus, reason: String?): AppResult<Unit>

    /** Contract 8. Null leaves a value unchanged. */
    suspend fun edit(request: BloodRequest, notes: String?, unitsNeeded: Int?, neededBy: Instant?): AppResult<Unit>

    /** The private contact: only the requester and donors who offered can read it. */
    suspend fun contact(requestId: String): AppResult<RequestContact?>

    companion object {
        const val PAGE_SIZE = 20L
        const val FEED_LIMIT = 50L
    }
}

@Singleton
class FirestoreRequestRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val clock: Clock,
) : RequestRepository {

    override fun feed(district: String, group: BloodGroup): Flow<List<BloodRequest>> =
        db.requests()
            .whereEqualTo(RequestDoc.STATUS, RequestStatus.OPEN.code)
            .whereEqualTo(RequestDoc.DISTRICT, district)
            .whereArrayContains(RequestDoc.ACCEPTED_GROUPS, group.code)
            .orderBy(RequestDoc.NEEDED_BY)
            .limit(RequestRepository.FEED_LIMIT)
            .snapshots { d -> d.data?.let { RequestDoc.fromMap(d.id, it) } }

    override suspend fun nearby(district: String, after: BloodRequest?, limit: Long): AppResult<List<BloodRequest>> = appResultOf {
        var query = db.requests()
            .whereEqualTo(RequestDoc.STATUS, RequestStatus.OPEN.code)
            .whereEqualTo(RequestDoc.DISTRICT, district)
            .orderBy(RequestDoc.NEEDED_BY)
        after?.let { query = query.startAfter(it.neededBy.toTimestamp()) }
        query.limit(limit).get().await().documents.mapNotNull { d -> d.data?.let { RequestDoc.fromMap(d.id, it) } }
    }

    override fun observe(requestId: String): Flow<BloodRequest?> =
        db.requestRef(requestId).snapshots { d -> d.data?.let { RequestDoc.fromMap(d.id, it) } }

    override fun observeState(requestId: String): Flow<DocState<BloodRequest>> =
        db.requestRef(requestId).docStates { d -> d.data?.let { RequestDoc.fromMap(d.id, it) } }

    override suspend fun byIds(ids: List<String>): AppResult<List<BloodRequest>> = appResultOf {
        // whereIn takes at most 30 values; a page of offers is 20.
        ids.distinct().chunked(30).flatMap { chunk ->
            db.requests().whereIn(FieldPath.documentId(), chunk).get().await().documents
                .mapNotNull { d -> d.data?.let { RequestDoc.fromMap(d.id, it) } }
        }
    }

    override suspend fun mine(uid: String, after: BloodRequest?, limit: Long): AppResult<List<BloodRequest>> = appResultOf {
        var query = db.requests()
            .whereEqualTo(RequestDoc.REQUESTER_UID, uid)
            .orderBy(RequestDoc.CREATED_AT, Query.Direction.DESCENDING)
        after?.createdAt?.let { query = query.startAfter(it.toTimestamp()) }
        query.limit(limit).get().await().documents.mapNotNull { d -> d.data?.let { RequestDoc.fromMap(d.id, it) } }
    }

    override fun newRequestId(): String = db.requests().document().id

    override suspend fun create(requester: UserProfile, draft: RequestDraft, requestId: String): AppResult<String> {
        if (!isValid(draft)) return AppResult.Failure(AppError.InvalidInput)
        return appResultOf {
            val ref = db.requestRef(requestId)
            val batch = db.batch()
            batch.set(ref, RequestDoc.createMap(draft, requester.uid, displayNameOf(requester)))
            batch.set(db.contactRef(ref.id), ContactDoc.createMap(draft, requester.uid))
            // The rules allow +1 only together with this request, named in lastRequestId.
            batch.update(
                db.statsRef(requester.uid),
                mapOf(
                    UserStatsDoc.OPEN_REQUESTS to FieldValue.increment(1),
                    UserStatsDoc.TOTAL_REQUESTS to FieldValue.increment(1),
                    UserStatsDoc.LAST_REQUEST_ID to ref.id,
                ),
            )
            batch.commit().await()
            ref.id
        }
    }

    override suspend fun close(request: BloodRequest, status: RequestStatus, reason: String?): AppResult<Unit> {
        if (!StatusTransitions.requesterCanSet(request.status, status)) return AppResult.Failure(AppError.InvalidInput)
        if (!Validators.fitsOptional(reason, Validators.CLOSED_REASON_MAX)) return AppResult.Failure(AppError.InvalidInput)
        return appResultOf {
            val batch = db.batch()
            val fields = buildMap<String, Any> {
                put(RequestDoc.STATUS, status.code)
                put(RequestDoc.UPDATED_AT, FieldValue.serverTimestamp())
                reason?.trim()?.takeIf { it.isNotEmpty() }?.let { put(RequestDoc.CLOSED_REASON, it) }
            }
            batch.update(db.requestRef(request.id), fields)
            batch.update(db.statsRef(request.requesterUid), openRequestsDown(request.id))
            batch.commit().await()
        }
    }

    override suspend fun edit(request: BloodRequest, notes: String?, unitsNeeded: Int?, neededBy: Instant?): AppResult<Unit> {
        if (request.status != RequestStatus.OPEN) return AppResult.Failure(AppError.InvalidInput)
        if (unitsNeeded != null && (!Validators.isValidUnits(unitsNeeded) || unitsNeeded < request.unitsFulfilled)) {
            return AppResult.Failure(AppError.InvalidInput)
        }
        // The rules keep neededBy within 30 days of the request's creation, not of now.
        val createdAt = request.createdAt ?: clock.now()
        if (neededBy != null &&
            (!Validators.isValidNeededBy(neededBy, clock.now()) || !neededBy.isBefore(createdAt.plus(Validators.MAX_NEEDED_BY_AHEAD)))
        ) {
            return AppResult.Failure(AppError.InvalidInput)
        }
        if (!Validators.fitsOptional(notes, Validators.NOTES_MAX)) return AppResult.Failure(AppError.InvalidInput)
        return appResultOf {
            val fields = buildMap<String, Any> {
                put(RequestDoc.UPDATED_AT, FieldValue.serverTimestamp())
                if (notes != null) {
                    val trimmed = notes.trim()
                    put(RequestDoc.NOTES, trimmed.ifEmpty { FieldValue.delete() })
                    put(RequestDoc.FLAGGED, Validators.hasMoneyWords(trimmed))
                }
                unitsNeeded?.let { put(RequestDoc.UNITS_NEEDED, it) }
                neededBy?.let {
                    val whole = it.truncatedTo(ChronoUnit.SECONDS)
                    put(RequestDoc.NEEDED_BY, whole.toTimestamp())
                    put(RequestDoc.EXPIRES_AT, Validators.expiresAt(whole).toTimestamp())
                }
            }
            db.requestRef(request.id).update(fields).await()
        }
    }

    override suspend fun contact(requestId: String): AppResult<RequestContact?> = appResultOf {
        db.contactRef(requestId).get().await().data?.let(ContactDoc::fromMap)
    }

    private fun isValid(d: RequestDraft): Boolean =
        Validators.isValidUnits(d.unitsNeeded) &&
            Validators.isValidHospitalName(d.hospitalName) &&
            Validators.fitsOptional(d.hospitalAddress?.trim(), Validators.HOSPITAL_ADDRESS_MAX) &&
            (d.hospitalId == null || Validators.isId(d.hospitalId)) &&
            Validators.isCode(d.division) && Validators.isCode(d.district) &&
            (d.upazila == null || Validators.isCode(d.upazila)) &&
            Validators.isValidNeededBy(d.neededBy, clock.now()) &&
            Validators.fitsOptional(d.notes?.trim(), Validators.NOTES_MAX) &&
            Validators.isValidName(d.contactName) && Validators.isBdPhone(d.contactPhone) &&
            Validators.fitsOptional(d.patientName?.trim(), Validators.PATIENT_NAME_MAX) &&
            Validators.fitsOptional(d.wardOrBed?.trim(), Validators.WARD_OR_BED_MAX)

    companion object {
        /** userStats −1 with the request that closes in the same batch (rules: requestClosed). */
        internal fun openRequestsDown(requestId: String): Map<String, Any> = mapOf(
            UserStatsDoc.OPEN_REQUESTS to FieldValue.increment(-1),
            UserStatsDoc.LAST_REQUEST_ID to requestId,
        )

        internal fun displayNameOf(p: UserProfile): String = Validators.displayName(p.fullName)
    }
}
