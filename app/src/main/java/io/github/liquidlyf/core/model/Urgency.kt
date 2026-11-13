package io.github.liquidlyf.core.model

/** How soon blood is needed (doc 07 §1). [code] is the stored Firestore value. */
enum class Urgency(val code: String) {
    /** Within 6 hours. */
    CRITICAL("CRITICAL"),

    /** Within 24 hours. */
    URGENT("URGENT"),

    /** On a scheduled date. */
    PLANNED("PLANNED"),
    ;

    companion object {
        fun fromCode(code: String?): Urgency? = entries.firstOrNull { it.code == code }
    }
}
