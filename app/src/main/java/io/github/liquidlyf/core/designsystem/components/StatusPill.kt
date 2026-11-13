package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency

/**
 * A small coloured label. Colour is never the only signal: there is always an icon and
 * text too (doc 04 §3), so it works for colour-blind people and in TalkBack.
 */
@Composable
fun StatusPill(
    label: String,
    @DrawableRes icon: Int,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CircleShape, color = containerColor, contentColor = contentColor, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = Spacing.xxs),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun StatusPill(status: RequestStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val extended = LiquidLyfTheme.extendedColors
    val (icon, container, content) = when (status) {
        RequestStatus.OPEN -> Triple(LLIcons.HourglassTop, colors.tertiaryContainer, colors.onTertiaryContainer)
        RequestStatus.FULFILLED -> Triple(LLIcons.CheckCircle, extended.successContainer, extended.onSuccessContainer)
        RequestStatus.CANCELLED -> Triple(LLIcons.Cancel, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        RequestStatus.EXPIRED -> Triple(LLIcons.History, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        RequestStatus.REMOVED -> Triple(LLIcons.Block, colors.errorContainer, colors.onErrorContainer)
    }
    StatusPill(stringResource(status.labelRes), icon, container, content, modifier)
}

@Composable
fun StatusPill(status: ResponseStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val extended = LiquidLyfTheme.extendedColors
    val (icon, container, content) = when (status) {
        ResponseStatus.OFFERED -> Triple(LLIcons.VolunteerActivism, colors.tertiaryContainer, colors.onTertiaryContainer)
        ResponseStatus.CONFIRMED -> Triple(LLIcons.Verified, colors.secondaryContainer, colors.onSecondaryContainer)
        ResponseStatus.DECLINED -> Triple(LLIcons.Block, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        ResponseStatus.WITHDRAWN -> Triple(LLIcons.Undo, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        ResponseStatus.DONATED -> Triple(LLIcons.Favorite, extended.successContainer, extended.onSuccessContainer)
    }
    StatusPill(stringResource(status.labelRes), icon, container, content, modifier)
}

/**
 * How urgent a request is (doc 04 §3): Critical = error + priority icon, Urgent = warning
 * + clock, Planned = tertiary container + calendar. Red is kept for real urgency.
 */
@Composable
fun UrgencyBadge(urgency: Urgency, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val extended = LiquidLyfTheme.extendedColors
    val (icon, container, content) = when (urgency) {
        Urgency.CRITICAL -> Triple(LLIcons.PriorityHigh, colors.error, colors.onError)
        Urgency.URGENT -> Triple(LLIcons.Schedule, extended.warning, extended.onWarning)
        Urgency.PLANNED -> Triple(LLIcons.Event, colors.tertiaryContainer, colors.onTertiaryContainer)
    }
    StatusPill(stringResource(urgency.labelRes), icon, container, content, modifier)
}
