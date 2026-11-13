package io.github.liquidlyf.core.data.request

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.liquidlyf.core.domain.StatusTransitions
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.dto.InboxDoc
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.ResponseDoc
import io.github.liquidlyf.core.firebase.dto.Responders
import io.github.liquidlyf.core.firebase.dto.inbox
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.firebase.dto.responseRef
import io.github.liquidlyf.core.firebase.dto.responses
import io.github.liquidlyf.core.firebase.dto.statsRef
import io.github.liquidlyf.core.firebase.dto.toTimestamp
import io.github.liquidlyf.core.firebase.snapshots
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Donors' offers on a request (write contract items 9–11). One response changes per batch, and
 * the request's phone-free `responders` entry always mirrors that response's status.
 */
interface OfferRepository {
    /** The requester's live list of offers on their request (the third allowed listener). */
    fun responders(requestId: String, requesterUid: String): Flow<List<DonorResponse>>

    /** The donor's own offer on [requestId], or null. */
    suspend fun myOffer(requestId: String, donorUid: String): AppResult<DonorResponse?>

    /** My offers on any request, newest first, a page at a time. */
    suspend fun mine(donorUid: String, after: DonorResponse? = null, limit: Long = RequestRepository.PAGE_SIZE): AppResult<List<DonorResponse>>

    /** Contract 9. Sharing the phone with the requester is the donor's consent. */
    suspend fun offer(request: BloodRequest, donor: UserProfile, message: String?): AppResult<Unit>

    /** Contract 10. */
    suspend fun withdraw(request: BloodRequest, donorUid: String): AppResult<Unit>

    /**
     * Contract 11: CONFIRMED, DECLINED or DONATED. The last donated unit also fulfils the request
     * and frees its open-request slot.
     */
    suspend fun decide(request: BloodRequest, response: DonorResponse, to: ResponseStatus): AppResult<Unit>
}

@Singleton
class FirestoreOfferRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val clock: Clock,
) : OfferRepository {

    override fun responders(requestId: String, requesterUid: String): Flow<List<DonorResponse>> =
        // The rules only allow this query with the requesterUid filter.
        db.responses(requestId)
            .whereEqualTo(ResponseDoc.REQUESTER_UID, requesterUid)
            .snapshots { d -> d.data?.let(ResponseDoc::fromMap) }

    override suspend fun myOffer(requestId: String, donorUid: String): AppResult<DonorResponse?> = appResultOf {
        db.responseRef(requestId, donorUid).get().await().data?.let(ResponseDoc::fromMap)
    }

    override suspend fun mine(donorUid: String, after: DonorResponse?, limit: Long): AppResult<List<DonorResponse>> = appResultOf {
        var query = db.collectionGroup(ResponseDoc.COLLECTION)
            .whereEqualTo(ResponseDoc.DONOR_UID, donorUid)
            .orderBy(ResponseDoc.CREATED_AT, Query.Direction.DESCENDING)
        after?.createdAt?.let { query = query.startAfter(it.toTimestamp()) }
        query.limit(limit).get().await().documents.mapNotNull { d -> d.data?.let(ResponseDoc::fromMap) }
    }

    override suspend fun offer(request: BloodRequest, donor: UserProfile, message: String?): AppResult<Unit> {
        val group = donor.bloodGroup ?: return AppResult.Failure(AppError.InvalidInput) // unknown group: can't offer (P1)
        val canOffer = request.isActive(clock.now()) &&
            request.requesterUid != donor.uid &&
            donor.uid !in request.responders &&
            group in request.acceptedGroups &&
            Validators.fitsOptional(message?.trim(), Validators.OFFER_MESSAGE_MAX)
        if (!canOffer) return AppResult.Failure(AppError.InvalidInput)
        val name = Validators.displayName(donor.fullName)
        return appResultOf {
            val batch = db.batch()
            batch.set(
                db.responseRef(request.id, donor.uid),
                ResponseDoc.offerMap(request.id, request.requesterUid, donor.uid, name, group, donor.phone, message),
            )
            batch.update(
                db.requestRef(request.id),
                mapOf(
                    Responders.path(donor.uid) to Responders.entry(Responder(name, group, ResponseStatus.OFFERED)),
                    RequestDoc.RESPONSE_COUNT to FieldValue.increment(1),
                    RequestDoc.LAST_RESPONDER_UID to donor.uid,
                    RequestDoc.UPDATED_AT to FieldValue.serverTimestamp(),
                ),
            )
            batch.set(
                db.inbox(request.requesterUid).document(InboxDoc.docId(InboxType.NEW_OFFER, request.id, donor.uid)),
                InboxDoc.createMap(InboxType.NEW_OFFER, request.id, donor.uid),
            )
            batch.commit().await()
        }
    }

    override suspend fun withdraw(request: BloodRequest, donorUid: String): AppResult<Unit> {
        val current = request.responders[donorUid]?.status ?: return AppResult.Failure(AppError.NotFound)
        if (!StatusTransitions.donorCanSet(current, ResponseStatus.WITHDRAWN)) return AppResult.Failure(AppError.InvalidInput)
        return appResultOf {
            val batch = db.batch()
            batch.update(db.responseRef(request.id, donorUid), ResponseDoc.statusMap(ResponseStatus.WITHDRAWN))
            batch.update(db.requestRef(request.id), responderChange(donorUid, ResponseStatus.WITHDRAWN))
            batch.commit().await()
        }
    }

    override suspend fun decide(request: BloodRequest, response: DonorResponse, to: ResponseStatus): AppResult<Unit> {
        if (response.requestId != request.id || !StatusTransitions.requesterCanSet(response.status, to)) {
            return AppResult.Failure(AppError.InvalidInput)
        }
        val inboxType = when (to) {
            ResponseStatus.CONFIRMED -> InboxType.OFFER_CONFIRMED
            ResponseStatus.DECLINED -> InboxType.OFFER_DECLINED
            ResponseStatus.DONATED -> InboxType.DONATION_RECORDED
            else -> return AppResult.Failure(AppError.InvalidInput)
        }
        return appResultOf {
            val batch = db.batch()
            batch.update(db.responseRef(request.id, response.donorUid), ResponseDoc.statusMap(to))
            val requestFields = responderChange(response.donorUid, to).toMutableMap()
            if (to == ResponseStatus.DONATED) {
                requestFields[RequestDoc.UNITS_FULFILLED] = FieldValue.increment(1)
                val fulfilled = StatusTransitions.statusAfterDonation(request.unitsFulfilled + 1, request.unitsNeeded)
                if (request.status == RequestStatus.OPEN && fulfilled == RequestStatus.FULFILLED) {
                    requestFields[RequestDoc.STATUS] = RequestStatus.FULFILLED.code
                    batch.update(db.statsRef(request.requesterUid), FirestoreRequestRepository.openRequestsDown(request.id))
                }
            }
            batch.update(db.requestRef(request.id), requestFields)
            batch.set(
                db.inbox(response.donorUid).document(InboxDoc.docId(inboxType, request.id, request.requesterUid)),
                InboxDoc.createMap(inboxType, request.id, request.requesterUid),
            )
            batch.commit().await()
        }
    }

    /** Only this donor's entry's status changes; name and group stay as the rules require. */
    private fun responderChange(donorUid: String, status: ResponseStatus): Map<String, Any> = mapOf(
        "${Responders.path(donorUid)}.${Responders.STATUS}" to status.code,
        RequestDoc.LAST_RESPONDER_UID to donorUid,
        RequestDoc.UPDATED_AT to FieldValue.serverTimestamp(),
    )
}
