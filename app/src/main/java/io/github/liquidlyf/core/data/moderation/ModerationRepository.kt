package io.github.liquidlyf.core.data.moderation

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import io.github.liquidlyf.core.data.request.FirestoreRequestRepository
import io.github.liquidlyf.core.firebase.appResultOf
import io.github.liquidlyf.core.firebase.dto.AdminLogDoc
import io.github.liquidlyf.core.firebase.dto.ReportDoc
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.firebase.dto.newAdminLogRef
import io.github.liquidlyf.core.firebase.dto.reportRef
import io.github.liquidlyf.core.firebase.dto.reports
import io.github.liquidlyf.core.firebase.dto.requestRef
import io.github.liquidlyf.core.firebase.dto.statsRef
import io.github.liquidlyf.core.model.AdminAction
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Report
import io.github.liquidlyf.core.model.ReportStatus
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** What an admin can do to a reported request (contract 17). */
enum class RequestAction(val log: AdminAction, val to: RequestStatus) {
    /** OPEN → REMOVED: hidden until reviewed. */
    HIDE(AdminAction.REMOVE_REQUEST, RequestStatus.REMOVED),

    /** REMOVED → OPEN. */
    RESTORE(AdminAction.RESTORE_REQUEST, RequestStatus.OPEN),

    /** OPEN or REMOVED → CANCELLED, giving the owner's open-request slot back (B15). */
    CANCEL(AdminAction.CANCEL_REQUEST, RequestStatus.CANCELLED),
    ;

    fun allowedFrom(status: RequestStatus): Boolean = when (this) {
        HIDE -> status == RequestStatus.OPEN
        RESTORE -> status == RequestStatus.REMOVED
        CANCEL -> status == RequestStatus.OPEN || status == RequestStatus.REMOVED
    }
}

/**
 * Phase 7.4/7.5, for admins only (the `admin` claim; the rules refuse everyone else). Each
 * action is one batch: the change, the report's new status, and one `adminLog` entry.
 */
interface ModerationRepository {
    /** Newest first; the screen re-orders by priority. */
    suspend fun openReports(limit: Long = PAGE_SIZE): AppResult<List<Report>>

    suspend fun dismiss(adminUid: String, report: Report, note: String?): AppResult<Unit>

    suspend fun act(adminUid: String, action: RequestAction, request: BloodRequest, report: Report?, note: String?): AppResult<Unit>

    /**
     * A report about a person, handled outside the app (a ban is the maintainer's
     * `revokeAndBan.mjs`): marks it ACTIONED and logs it.
     */
    suspend fun markPersonHandled(adminUid: String, report: Report, note: String?): AppResult<Unit>

    companion object {
        const val PAGE_SIZE = 50L
    }
}

@Singleton
class FirestoreModerationRepository @Inject constructor(private val db: FirebaseFirestore) : ModerationRepository {

    override suspend fun openReports(limit: Long): AppResult<List<Report>> = appResultOf {
        // Index: reports (status, createdAt desc).
        db.reports().whereEqualTo(ReportDoc.STATUS, ReportStatus.OPEN.code)
            .orderBy(ReportDoc.CREATED_AT, Query.Direction.DESCENDING)
            .limit(limit).get().await()
            .documents.mapNotNull { d -> d.data?.let { ReportDoc.fromMap(d.id, it) } }
    }

    override suspend fun dismiss(adminUid: String, report: Report, note: String?): AppResult<Unit> = appResultOf {
        val batch = db.batch()
        batch.update(db.reportRef(report.id), ReportDoc.STATUS, ReportStatus.DISMISSED.code)
        batch.set(db.newAdminLogRef(), AdminLogDoc.createMap(adminUid, AdminAction.DISMISS_REPORT, TARGET_REPORT, report.id, null, note))
        batch.commit().await()
    }

    override suspend fun act(
        adminUid: String,
        action: RequestAction,
        request: BloodRequest,
        report: Report?,
        note: String?,
    ): AppResult<Unit> {
        if (!action.allowedFrom(request.status)) return AppResult.Failure(AppError.InvalidInput)
        return appResultOf {
            // The owner's slot comes back on a cancel, unless their stats are gone (deleted account).
            val ownerStats = db.statsRef(request.requesterUid)
            val giveSlotBack = action == RequestAction.CANCEL && ownerStats.get(Source.SERVER).await().exists()
            val batch = db.batch()
            batch.update(
                db.requestRef(request.id),
                mapOf(RequestDoc.STATUS to action.to.code, RequestDoc.UPDATED_AT to FieldValue.serverTimestamp()),
            )
            if (giveSlotBack) batch.update(ownerStats, FirestoreRequestRepository.openRequestsDown(request.id))
            report?.let { batch.update(db.reportRef(it.id), ReportDoc.STATUS, ReportStatus.ACTIONED.code) }
            batch.set(db.newAdminLogRef(), AdminLogDoc.createMap(adminUid, action.log, TARGET_REQUEST, request.id, report?.id, note))
            batch.commit().await()
        }
    }

    override suspend fun markPersonHandled(adminUid: String, report: Report, note: String?): AppResult<Unit> = appResultOf {
        val batch = db.batch()
        batch.update(db.reportRef(report.id), ReportDoc.STATUS, ReportStatus.ACTIONED.code)
        batch.set(db.newAdminLogRef(), AdminLogDoc.createMap(adminUid, AdminAction.ACTION_REPORT, TARGET_USER, report.targetId, report.id, note))
        batch.commit().await()
    }

    private companion object {
        const val TARGET_REQUEST = "REQUEST"
        const val TARGET_USER = "USER"
        const val TARGET_REPORT = "REPORT"
    }
}
