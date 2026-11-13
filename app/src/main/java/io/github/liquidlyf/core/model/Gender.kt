package io.github.liquidlyf.core.model

/**
 * Used only for the donation interval (doc 07 §1, §4). Private: it is never copied to the
 * public donor card. [code] is the stored Firestore value.
 */
enum class Gender(val code: String) {
    MALE("MALE"),
    FEMALE("FEMALE"),
    OTHER("OTHER"),
    ;

    companion object {
        fun fromCode(code: String?): Gender? = entries.firstOrNull { it.code == code }
    }
}
