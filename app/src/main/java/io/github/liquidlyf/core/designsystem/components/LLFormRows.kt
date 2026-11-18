package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.numberText

/*
 * Rows shared by forms and settings (DS-01): before, Settings, Notifications, the request form and
 * Find donors each had their own switch row, and the units stepper was copied in Edit request.
 */

/**
 * A yes/no answer or setting with a switch. The whole row toggles, and TalkBack reads it as one
 * switch (8.5). Inside a form leave [horizontalPadding] at 0, so the text lines up with the fields
 * around it (UI-07: Material's ListItem indented it 16 dp); a list like Settings passes its gutter.
 */
@Composable
fun LLSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    horizontalPadding: Dp = 0.dp,
    /** A leading icon, for lists where every row has one (Settings, N5). */
    @DrawableRes icon: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = horizontalPadding, vertical = Spacing.xs),
    ) {
        icon?.let {
            Icon(
                painterResource(it),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
            )
        }
        // Low 27: the words stay readable when the row is off (0.38 made the reason unreadable).
        Column(
            Modifier
                .weight(1f)
                .alpha(if (enabled) 1f else DISABLED_TEXT_ALPHA),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A count with − and + (the bags of blood). Each button is the full 48 dp, next to large text
 * too (8.5); TalkBack reads [spokenValue] ("2 bags"), not just the digit.
 */
@Composable
fun LLStepper(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    spokenValue: String,
    lessLabel: String,
    moreLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        // Low 12: − and + grow with the text (up to 1.5x), so they don't look tiny next to the number.
        val grow = LocalDensity.current.fontScale.coerceIn(1f, MAX_LABEL_SCALE)
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(Spacing.minTouchTarget * grow)) {
            Icon(painterResource(LLIcons.Remove), contentDescription = lessLabel, modifier = Modifier.size(24.dp * grow))
        }
        Text(
            numberText(value),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics { contentDescription = spokenValue },
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(Spacing.minTouchTarget * grow)) {
            Icon(painterResource(LLIcons.Add), contentDescription = moreLabel, modifier = Modifier.size(24.dp * grow))
        }
    }
}

/** Material's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** A disabled row's words: still dimmer than enabled, but readable (Low 27). */
private const val DISABLED_TEXT_ALPHA = 0.7f
