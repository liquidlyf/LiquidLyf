package io.github.liquidlyf.core.domain

/** How a checked field looks: no colour yet, green (its check passes) or red. */
enum class FieldCheck { NONE, OK, ERROR }

/**
 * A new password, typed twice (D24, the maintainer's picks on 19 Nov 2026): Create account, Change
 * password and Add a password. Being unfinished is never a mistake, so nothing is red while it may
 * still turn out right:
 * - The first box shows the 8-character rule from the start, and turns green at
 *   [Validators.PASSWORD_MIN]. It turns red only once it was left with 1–7 characters, or the
 *   button was tapped while short, and stays red until it reaches 8.
 * - The second box turns green as soon as it's the same as a long-enough first one. It turns red
 *   as soon as it's no longer the start of the first password (a wrong letter), when it's left
 *   unfinished, or when the button was tapped while the two differ; that red stays until they match.
 */
data class NewPassword(
    val value: String = "",
    val again: String = "",
    /** The first box's red "too short" was shown (left short, or the button). */
    val shortShown: Boolean = false,
    /** The button was tapped while the two differed. */
    val differShown: Boolean = false,
    /** The second box has the keyboard focus: a correct start being typed there isn't red. */
    val typingAgain: Boolean = false,
) {
    val longEnough: Boolean get() = value.length >= Validators.PASSWORD_MIN

    val matches: Boolean get() = again == value

    /** Ready to save: long enough, and typed the same twice. */
    val isValid: Boolean get() = longEnough && matches

    val valueCheck: FieldCheck
        get() = when {
            longEnough -> FieldCheck.OK
            shortShown -> FieldCheck.ERROR
            else -> FieldCheck.NONE
        }

    /** "5" of 8 under the first box while it has 1–7 characters; null when empty or long enough. */
    val count: Int? get() = value.length.takeIf { it in 1 until Validators.PASSWORD_MIN }

    val againCheck: FieldCheck
        get() = when {
            again.isNotEmpty() && matches -> if (longEnough) FieldCheck.OK else FieldCheck.NONE
            differShown -> FieldCheck.ERROR
            again.isEmpty() -> FieldCheck.NONE
            !value.startsWith(again) -> FieldCheck.ERROR // a wrong letter shows at once
            typingAgain -> FieldCheck.NONE
            else -> FieldCheck.ERROR // left unfinished
        }

    fun type(v: String): NewPassword =
        copy(value = v, shortShown = shortShown && v.length < Validators.PASSWORD_MIN, differShown = differShown && v != again)

    fun typeAgain(v: String): NewPassword = copy(again = v, differShown = differShown && v != value)

    /** The first box got or lost the keyboard focus: left with 1–7 characters, it says it's too short. */
    fun focusValue(focused: Boolean): NewPassword =
        if (!focused && value.length in 1 until Validators.PASSWORD_MIN) copy(shortShown = true) else this

    fun focusAgain(focused: Boolean): NewPassword = if (typingAgain == focused) this else copy(typingAgain = focused)

    /** The button was tapped: everything still wrong shows red. */
    fun check(): NewPassword = copy(shortShown = !longEnough, differShown = !matches)
}
