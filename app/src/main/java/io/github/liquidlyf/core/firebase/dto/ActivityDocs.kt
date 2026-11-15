package io.github.liquidlyf.core.firebase.dto

import io.github.liquidlyf.core.model.AdminAction
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.core.model.InboxItem
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.Report
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.ReportStatus
import io.github.liquidlyf.core.model.ReportTarget
import java.time.Instant

/** `users/{uid}/donations/{id}`. Keys match `validDonation` in firestore.rules. */
object DonationDoc {
    const val COLLECTION = "donations"

    const val DONATED_AT = "donatedAt"
    const val COMPONENT = "component"
    const val HOSPITAL_NAME = "hospitalName"
    const val REQUEST_ID = "requestId"
    const val VERIFIED = "verified"
    const val SOURCE = "source"
    const val CREATED_AT = "createdAt"

    fun fromMap(id: String, m: Map<String, Any?>): Donation? = Donation(
        id = id,
        donatedAt = m.instant(DONATED_AT) ?: return null,
        component = BloodComponent.fromCode(m.str(COMPONENT)) ?: return null,
        hospitalName = m.str(HOSPITAL_NAME),
        requestId = m.str(REQUEST_ID),
        verified = m.bool(VERIFIED) ?: false,
        source = DonationSource.fromCode(m.str(SOURCE)) ?: return null,
        createdAt = m.instant(CREATED_AT),
    )

    /** Verified, after the requester marked this donor DONATED (contract 12). Doc id = request id. */
    fun verifiedMap(requestId: String, donatedAt: Instant, component: BloodComponent, hospitalName: String?): Map<String, Any?> =
        fields(
            DONATED_AT to donatedAt.toTimestamp(),
            COMPONENT to component.code,
            HOSPITAL_NAME to hospitalName?.trim()?.ifEmpty { null },
            REQUEST_ID to requestId,
            VERIFIED to true,
            SOURCE to DonationSource.REQUEST.code,
            CREATED_AT to serverTime(),
        )

    /** Typed in by the donor (contract 13). Never verified, never linked to a request. */
    fun selfMap(donatedAt: Instant, component: BloodComponent, hospitalName: String?): Map<String, Any?> = fields(
        DONATED_AT to donatedAt.toTimestamp(),
        COMPONENT to component.code,
        HOSPITAL_NAME to hospitalName?.trim()?.ifEmpty { null },
        VERIFIED to false,
        SOURCE to DonationSource.SELF.code,
        CREATED_AT to serverTime(),
    )
}

/**
 * `users/{uid}/notifications/{id}`. Keys and ids match `validInboxItem` in firestore.rules.
 * The id is fixed per event, so a retried batch can't create a duplicate.
 */
object InboxDoc {
    const val COLLECTION = "notifications"

    const val TYPE = "type"
    const val FROM_UID = "fromUid"
    const val REQUEST_ID = "requestId"
    const val CREATED_AT = "createdAt"

    fun fromMap(id: String, m: Map<String, Any?>): InboxItem? = InboxItem(
        id = id,
        type = InboxType.fromCode(m.str(TYPE)) ?: return null,
        fromUid = m.str(FROM_UID) ?: return null,
        requestId = m.str(REQUEST_ID) ?: return null,
        createdAt = m.instant(CREATED_AT),
    )

    fun docId(type: InboxType, requestId: String, fromUid: String): String = when (type) {
        InboxType.NEW_OFFER -> "${type.code}_${requestId}_$fromUid"
        InboxType.OFFER_CONFIRMED, InboxType.OFFER_DECLINED, InboxType.DONATION_RECORDED -> "${type.code}_$requestId"
        InboxType.ASKED_TO_DONATE -> "ask_$requestId"
    }

    fun createMap(type: InboxType, requestId: String, fromUid: String): Map<String, Any?> = mapOf(
        TYPE to type.code,
        FROM_UID to fromUid,
        REQUEST_ID to requestId,
        CREATED_AT to serverTime(),
    )
}

/** `reports/{reporterUid}_{targetId}`: one report per reporter per target. Keys match `validReport`. */
object ReportDoc {
    const val COLLECTION = "reports"

    const val REPORTER_UID = "reporterUid"
    const val TARGET_TYPE = "targetType"
    const val TARGET_ID = "targetId"
    const val REASON = "reason"
    const val NOTE = "note"
    const val CREATED_AT = "createdAt"
    const val STATUS = "status"

    fun docId(reporterUid: String, targetId: String): String = "${reporterUid}_$targetId"

    fun createMap(reporterUid: String, target: ReportTarget, targetId: String, reason: ReportReason, note: String?): Map<String, Any?> =
        fields(
            REPORTER_UID to reporterUid,
            TARGET_TYPE to target.code,
            TARGET_ID to targetId,
            REASON to reason.code,
            NOTE to note?.trim()?.ifEmpty { null },
            CREATED_AT to serverTime(),
            STATUS to "OPEN",
        )

    fun fromMap(id: String, m: Map<String, Any?>): Report? = Report(
        id = id,
        reporterUid = m.str(REPORTER_UID) ?: return null,
        targetType = ReportTarget.fromCode(m.str(TARGET_TYPE)) ?: return null,
        targetId = m.str(TARGET_ID) ?: return null,
        reason = ReportReason.fromCode(m.str(REASON)) ?: ReportReason.OTHER,
        note = m.str(NOTE),
        createdAt = m.instant(CREATED_AT),
        status = ReportStatus.fromCode(m.str(STATUS)) ?: ReportStatus.OPEN,
    )
}

/**
 * `adminLog/{id}` (contract 17): one entry per in-app admin action, created in the action's own
 * batch. Create-only: nobody can edit or delete an entry from the app.
 */
object AdminLogDoc {
    const val COLLECTION = "adminLog"

    fun createMap(adminUid: String, action: AdminAction, targetType: String, targetId: String, reportId: String?, note: String?): Map<String, Any?> =
        fields(
            "adminUid" to adminUid,
            "action" to action.code,
            "targetType" to targetType,
            "targetId" to targetId,
            "reportId" to reportId,
            "note" to note?.trim()?.ifEmpty { null },
            "createdAt" to serverTime(),
        )
}
