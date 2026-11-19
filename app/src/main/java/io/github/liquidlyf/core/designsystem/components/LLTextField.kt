package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme

/**
 * A text field with its label, help text and error shown inline under it (never a Toast).
 * [errorText] replaces [supportingText] while set and is read out by TalkBack.
 * [contentType] lets autofill and password managers fill the field (Phase 3).
 * The height grows with the font size; there is no fixed height (v0 clipped at large fonts).
 * With large text the label sits above the box ([FieldLabelAbove]).
 * [isOk] turns the border and the label green, for a check that passes (D24: the passwords match).
 * [lines] replaces the help or error line under the box with the caller's own lines, outside the
 * box's TalkBack item, so a line can be read out when it changes (a live region inside the box
 * would read the whole box at every letter). [errorText] still turns the box red and is its error.
 */
@Composable
fun LLTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    errorText: String? = null,
    isOk: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isPassword: Boolean = false,
    @DrawableRes leadingIcon: Int? = null,
    contentType: ContentType? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /** On the text box itself (not its label above it): e.g. an [ErrorAnchors] anchor. */
    fieldModifier: Modifier = Modifier,
    lines: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val helpText = errorText ?: supportingText
    val labelAbove = isLargeText()
    val ok = isOk && errorText == null
    val okColor = LiquidLyfTheme.extendedColors.success

    Column(modifier.fillMaxWidth()) {
        if (labelAbove) FieldLabelAbove(label, isError = errorText != null, isOk = ok)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .semantics {
                    if (labelAbove) contentDescription = label
                    if (contentType != null) this.contentType = contentType
                    if (errorText != null) error(errorText)
                },
            enabled = enabled,
            label = if (labelAbove) null else { { Text(label) } },
            leadingIcon = leadingIcon?.let { icon -> { Icon(painterResource(icon), contentDescription = null) } },
            trailingIcon = when {
                isPassword -> {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                painter = painterResource(if (passwordVisible) LLIcons.VisibilityOff else LLIcons.Visibility),
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.action_hide_password else R.string.action_show_password,
                                ),
                            )
                        }
                    }
                }
                errorText != null -> {
                    { Icon(painterResource(LLIcons.Error), contentDescription = null) }
                }
                else -> null
            },
            supportingText = if (lines != null) null else helpText?.let { text -> { Text(text) } },
            isError = errorText != null,
            colors = if (ok) {
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = okColor, unfocusedBorderColor = okColor,
                    focusedLabelColor = okColor, unfocusedLabelColor = okColor, cursorColor = okColor,
                )
            } else {
                OutlinedTextFieldDefaults.colors()
            },
            visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (isPassword) {
                keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
            } else {
                keyboardOptions
            },
            keyboardActions = keyboardActions,
            singleLine = singleLine,
        )
        lines?.invoke(this)
    }
}
