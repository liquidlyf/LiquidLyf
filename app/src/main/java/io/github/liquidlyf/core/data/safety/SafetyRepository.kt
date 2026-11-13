package io.github.liquidlyf.core.data.safety

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.github.liquidlyf.core.domain.StatusTransitions
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.dto.InboxDoc
import io.github.liquidlyf.core.firebase.dto.ReportDoc
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.inbox
import io.github.liquidlyf.core.firebase.dto.reportRef
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.ReportTarget
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Reports and "ask to donate" (write contract items 14, 15). */
interface SafetyRepository {
    /** One report per reporter per request; the third report hides an OPEN request. */
    suspend fun reportRequest(request: BloodRequest, reporterUid: String, reason: ReportReason, note: String?): AppResult<Unit>

    suspend fun reportUser(targetUid: String, reporterUid: String, reason: ReportReason, note: String?): AppResult<Unit>

    /** The requester asks a listed donor directly: at most [MAX_ASKS] per request. */
    suspend fun askToDonate(request: BloodRequest, donor: DonorCard): AppResult<Unit>

    companion object {
        const val MAX_ASKS = 10
    }
}

@Singleton
class FirestoreSafetyRepository @Inject constructor(private val db: FirebaseFirestore) : SafetyRepository {

    override suspend fun reportRequest(
        request: BloodRequest,
        reporterUid: String,
        reason: ReportReason,
        note: String?,
    ): AppResult<Unit> {
        if (request.requesterUid == reporterUid || !Validators.fitsOptional(note?.trim(), Validators.REPORT_NOTE_MAX)) {
            return AppResult.Failure(AppError.InvalidInput)
        }
        return appResultOf {
            val batch = db.batch()
            batch.set(
                db.reportRef(ReportDoc.docId(reporterUid, request.id)),
                ReportDoc.createMap(reporterUid, ReportTarget.REQUEST, request.id, reason, note),
            )
            val fields = mutableMapOf<String, Any>(
                RequestDoc.REPORT_COUNT to FieldValue.increment(1),
                RequestDoc.UPDATED_AT to FieldValue.serverTimestamp(),
            )
            if (StatusTransitions.reportsCanRemove(request.status, request.reportCount + 1)) {
                fields[RequestDoc.STATUS] = RequestStatus.REMOVED.code
            }
            batch.update(db.requestRef(request.id), fields)
            batch.commit().await()
        }
    }

    override suspend fun reportUser(targetUid: String, reporterUid: String, reason: ReportReason, note: String?): AppResult<Unit> {
        if (targetUid == reporterUid || !Validators.fitsOptional(note?.trim(), Validators.REPORT_NOTE_MAX)) {
            return AppResult.Failure(AppError.InvalidInput)
        }
        return appResultOf {
            db.reportRef(ReportDoc.docId(reporterUid, targetUid))
                .set(ReportDoc.createMap(reporterUid, ReportTarget.USER, targetUid, reason, note))
                .await()
        }
    }

    override suspend fun askToDonate(request: BloodRequest, donor: DonorCard): AppResult<Unit> {
        if (request.status != RequestStatus.OPEN || request.askCount >= SafetyRepository.MAX_ASKS ||
            donor.bloodGroup !in request.acceptedGroups || donor.uid == request.requesterUid
        ) {
            return AppResult.Failure(AppError.InvalidInput)
        }
        return appResultOf {
            val batch = db.batch()
            batch.set(
                db.inbox(donor.uid).document(InboxDoc.docId(InboxType.ASKED_TO_DONATE, request.id, request.requesterUid)),
                InboxDoc.createMap(InboxType.ASKED_TO_DONATE, request.id, request.requesterUid),
            )
            batch.update(
                db.requestRef(request.id),
                mapOf(
                    RequestDoc.ASK_COUNT to FieldValue.increment(1),
                    RequestDoc.LAST_ASKED_UID to donor.uid,
                    RequestDoc.UPDATED_AT to FieldValue.serverTimestamp(),
                ),
            )
            batch.commit().await()
        }
    }
}
