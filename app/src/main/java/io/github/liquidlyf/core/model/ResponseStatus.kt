package io.github.liquidlyf.core.model

/** Lifecycle of a donor's offer on a request (doc 07 §1). [code] is the stored Firestore value. */
enum class ResponseStatus(val code: String) {
    OFFERED("OFFERED"),
    CONFIRMED("CONFIRMED"),
    DECLINED("DECLINED"),
    WITHDRAWN("WITHDRAWN"),
    DONATED("DONATED"),
    ;

    companion object {
        fun fromCode(code: String?): ResponseStatus? = entries.firstOrNull { it.code == code }
    }
}
