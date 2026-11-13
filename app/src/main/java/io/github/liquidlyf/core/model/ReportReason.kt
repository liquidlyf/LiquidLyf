package io.github.liquidlyf.core.model

/** Why a request or user is reported (doc 07 §1). [code] is the stored Firestore value. */
enum class ReportReason(val code: String) {
    FAKE_REQUEST("FAKE_REQUEST"),
    ASKING_MONEY("ASKING_MONEY"),
    ABUSIVE("ABUSIVE"),
    SPAM("SPAM"),
    WRONG_INFO("WRONG_INFO"),
    OTHER("OTHER"),
    ;

    companion object {
        fun fromCode(code: String?): ReportReason? = entries.firstOrNull { it.code == code }
    }
}
