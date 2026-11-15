package io.github.liquidlyf.core.model

import java.time.Instant

/** A report as the admins see it (`reports/{reporterUid}_{targetId}`, admin read only). */
data class Report(
    val id: String,
    val reporterUid: String,
    val targetType: ReportTarget,
    val targetId: String,
    val reason: ReportReason,
    val note: String?,
    val createdAt: Instant?,
    val status: ReportStatus,
)

/** OPEN until an admin dismisses it or acts on it (phase-07 7.4). */
enum class ReportStatus(val code: String) {
    OPEN("OPEN"),
    DISMISSED("DISMISSED"),
    ACTIONED("ACTIONED"),
    ;

    companion object {
        fun fromCode(code: String?): ReportStatus? = entries.firstOrNull { it.code == code }
    }
}

/** The `adminLog` actions the rules allow (contract 17). */
enum class AdminAction(val code: String) {
    DISMISS_REPORT("DISMISS_REPORT"),
    ACTION_REPORT("ACTION_REPORT"),
    REMOVE_REQUEST("REMOVE_REQUEST"),
    RESTORE_REQUEST("RESTORE_REQUEST"),
    CANCEL_REQUEST("CANCEL_REQUEST"),
}
