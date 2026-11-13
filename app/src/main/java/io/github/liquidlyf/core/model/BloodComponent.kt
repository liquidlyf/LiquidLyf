package io.github.liquidlyf.core.model

/** What the patient needs (doc 07 §1). [code] is the stored Firestore value. */
enum class BloodComponent(val code: String) {
    WHOLE_BLOOD("WHOLE_BLOOD"),
    RED_CELLS("RED_CELLS"),
    PLATELETS("PLATELETS"),
    PLASMA("PLASMA"),
    ;

    companion object {
        fun fromCode(code: String?): BloodComponent? = entries.firstOrNull { it.code == code }
    }
}
