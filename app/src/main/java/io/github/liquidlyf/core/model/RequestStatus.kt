package io.github.liquidlyf.core.model

/** Lifecycle of a blood request (doc 07 §1). [code] is the stored Firestore value. */
enum class RequestStatus(val code: String) {
    OPEN("OPEN"),
    FULFILLED("FULFILLED"),
    CANCELLED("CANCELLED"),
    EXPIRED("EXPIRED"),

    /** Hidden by moderation or by enough reports (Phase 7). */
    REMOVED("REMOVED"),
    ;

    companion object {
        fun fromCode(code: String?): RequestStatus? = entries.firstOrNull { it.code == code }
    }
}
