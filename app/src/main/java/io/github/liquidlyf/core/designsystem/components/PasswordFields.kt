package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.domain.FieldCheck
import io.github.liquidlyf.core.domain.NewPassword
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.AppLocale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * D24 (the maintainer's picks, 19 Nov 2026): a new password typed twice, on Create account, Change
 * password and Add a password. [NewPassword] decides the colours; these two fields draw them. Never
 * colour alone: each green or red line has its icon and its words. TalkBack hears a line once, when
 * it changes, and never the count at each letter. Both boxes are new passwords for the phone's
 * password manager, and each has its own eye.
 */

/**
 * The new password's box. Under it, the 8-character rule from the start ("○ At least 8
 * characters", with a count while short), a green tick at 8, and "A longer password is stronger.".
 * Red only after it was left short or the button was tapped. [errorText]: a server's red line
 * instead (Firebase's weak password).
 */
@Composable
fun NewPasswordField(
    password: NewPassword,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    errorText: String? = null,
    enabled: Boolean = true,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    fieldModifier: Modifier = Modifier,
) {
    val check = if (errorText != null) FieldCheck.ERROR else password.valueCheck
    val red = errorText ?: if (check == FieldCheck.ERROR) stringResource(R.string.error_password_short) else null
    PasswordBox(
        value = password.value, onValueChange = onValueChange, onFocusChange = onFocusChange, label = label, check = check,
        errorText = red, imeAction = ImeAction.Next, modifier = modifier, enabled = enabled,
        keyboardActions = keyboardActions, fieldModifier = fieldModifier,
    ) {
        CheckLine(
            check, red ?: stringResource(R.string.password_rule), idleIcon = LLIcons.Circle, count = password.count,
            // A44: the words stay the same at 8, so the tick says it: "Done, At least 8 characters".
            okDescription = stringResource(R.string.password_rule_met),
        )
        if (check != FieldCheck.ERROR) {
            Text(
                stringResource(R.string.password_longer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = LINE_START + lineIconSize() + LINE_GAP, end = LINE_START),
            )
        }
    }
}

/**
 * The second box, "Type the password again": no line while it may still turn out right, a green
 * "Passwords match", or the red "Passwords don't match. Tap the eye to see what you typed.".
 */
@Composable
fun RetypePasswordField(
    password: NewPassword,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    fieldModifier: Modifier = Modifier,
) {
    val check = password.againCheck
    val red = if (check == FieldCheck.ERROR) stringResource(R.string.error_passwords_differ) else null
    PasswordBox(
        value = password.again, onValueChange = onValueChange, onFocusChange = onFocusChange, label = label, check = check,
        errorText = red, imeAction = ImeAction.Done, modifier = modifier, enabled = enabled,
        keyboardActions = keyboardActions, fieldModifier = fieldModifier,
    ) {
        if (check != FieldCheck.NONE) CheckLine(check, red ?: stringResource(R.string.passwords_match))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PasswordBox(
    value: String,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    label: String,
    check: FieldCheck,
    errorText: String?,
    imeAction: ImeAction,
    modifier: Modifier,
    enabled: Boolean,
    keyboardActions: KeyboardActions,
    fieldModifier: Modifier,
    lines: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val group = remember { BringIntoViewRequester() }
    KeepLinesInView(check, focused, group)
    LLTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier.bringIntoViewRequester(group),
        errorText = errorText,
        isOk = check == FieldCheck.OK,
        enabled = enabled,
        isPassword = true,
        contentType = ContentType.NewPassword,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = keyboardActions,
        fieldModifier = fieldModifier.onFocusChanged {
            if (it.isFocused != focused) {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }
        },
        lines = { lines() },
    )
}

/**
 * While typing in the box, its line turning green or red (or the button focusing it, which opens
 * the keyboard) brings the box and its lines into view above the keyboard, not just the cursor.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun KeepLinesInView(check: FieldCheck, focused: Boolean, group: BringIntoViewRequester) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    LaunchedEffect(check, focused) {
        if (!focused || check == FieldCheck.NONE) return@LaunchedEffect
        withTimeoutOrNull(KEYBOARD_SETTLE_MS) { snapshotFlow { ime.getBottom(density) == imeTarget.getBottom(density) }.first { it } }
        withFrameNanos { } // the new line is laid out first
        runCatching { group.bringIntoView() }
    }
}

/**
 * One line under a box: its icon and words in the check's colour, and the count (not read out).
 * [okDescription]: the tick's spoken name, for a line whose words don't change when it turns green.
 */
@Composable
private fun CheckLine(
    check: FieldCheck,
    text: String,
    @DrawableRes idleIcon: Int? = null,
    count: Int? = null,
    okDescription: String? = null,
) {
    val color: Color = when (check) {
        FieldCheck.OK -> LiquidLyfTheme.extendedColors.success
        FieldCheck.ERROR -> MaterialTheme.colorScheme.error
        FieldCheck.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (check) {
        FieldCheck.OK -> LLIcons.CheckCircle
        FieldCheck.ERROR -> LLIcons.Error
        FieldCheck.NONE -> idleIcon
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(LINE_GAP),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LINE_START, top = 4.dp, end = LINE_START)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = okDescription.takeIf { check == FieldCheck.OK },
                tint = color,
                modifier = Modifier.size(lineIconSize()),
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
        if (count != null) {
            val shown = "$count/${Validators.PASSWORD_MIN}"
            Text(
                if (currentAppLocale() == AppLocale.BN) shown.inBanglaDigits() else shown,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/** The line's icon grows with the text, like the words next to it. */
@Composable
private fun lineIconSize(): Dp = with(LocalDensity.current) { 16.sp.toDp() }

private fun String.inBanglaDigits(): String = map { c -> if (c in '0'..'9') '০' + (c - '0') else c }.joinToString("")

/** Where Material's own help line under a box starts. */
private val LINE_START = 16.dp
private val LINE_GAP = 6.dp
private const val KEYBOARD_SETTLE_MS = 1_000L
