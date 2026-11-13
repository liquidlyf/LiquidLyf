package io.github.liquidlyf.core.model

/**
 * The eight ABO/Rh blood groups. [code] is the value stored in Firestore (doc 07 §1); it
 * never changes. The text shown to people comes from string resources, not from here.
 */
enum class BloodGroup(val code: String) {
    A_POS("A_POS"),
    A_NEG("A_NEG"),
    B_POS("B_POS"),
    B_NEG("B_NEG"),
    AB_POS("AB_POS"),
    AB_NEG("AB_NEG"),
    O_POS("O_POS"),
    O_NEG("O_NEG"),
    ;

    val isRhNegative: Boolean get() = this == A_NEG || this == B_NEG || this == AB_NEG || this == O_NEG

    companion object {
        /** Returns the group for a stored [code], or null for unknown or missing values. */
        fun fromCode(code: String?): BloodGroup? = entries.firstOrNull { it.code == code }
    }
}
