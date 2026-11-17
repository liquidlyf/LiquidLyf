package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * The app's card (UI-02, D4 a "Calm white, one red"): white on the warm off-white background with
 * a 1 dp outlineVariant border (dark: #231918 with #534341), flat. Use it instead of Material's
 * Card, whose default fill is the pink surfaceContainerHighest, so the cards and the blood-group
 * badge were all one pink.
 */
@Composable
fun LLCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = LiquidLyfTheme.extendedColors.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, border = border, content = content)
    } else {
        Card(modifier = modifier, colors = colors, border = border, content = content)
    }
}

/** What an [LLNoticeCard] is about, and so its colour. */
enum class NoticeTone {
    /** Good to know (waiting for the network, a reminder): neutral grey. */
    Info,

    /** Take care (the daily alert limit, a request flagged for money): amber. */
    Warning,

    /** Something failed or is off and needs the person: the error container. */
    Error,
}

/**
 * A notice inside a screen, with its icon, text and an optional action under it (DS-01: one
 * shared card instead of each screen's own). Colour is never the only signal: the icon and the
 * words say the same.
 */
@Composable
fun LLNoticeCard(
    text: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = LiquidLyfTheme.extendedColors
    val (container, content) = when (tone) {
        NoticeTone.Info -> scheme.secondaryContainer to scheme.onSecondaryContainer
        NoticeTone.Warning -> extended.warningContainer to extended.onWarningContainer
        NoticeTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            Modifier.padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = if (title == null && action == null) Alignment.CenterVertically else Alignment.Top,
        ) {
            Icon(painterResource(icon), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(text, style = MaterialTheme.typography.bodyMedium)
                action?.invoke()
            }
        }
    }
}
