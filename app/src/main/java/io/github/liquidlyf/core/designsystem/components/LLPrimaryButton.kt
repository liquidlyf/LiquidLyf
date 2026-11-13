package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * The main action of a screen. While [loading], it shows a spinner and [loadingText]
 * (e.g. "Posting…") and ignores taps, so the action can't be sent twice.
 */
@Composable
fun LLPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String? = null,
    @DrawableRes icon: Int? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .heightIn(min = Spacing.minTouchTarget)
            .semantics { if (loading && loadingText != null) stateDescription = loadingText },
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(loadingText ?: text)
            }
            icon != null -> {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(text)
            }
            else -> Text(text)
        }
    }
}
