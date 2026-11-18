package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.StatusTone
import io.github.liquidlyf.core.designsystem.colors
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency

/**
 * A small coloured label for a state. Colour is never the only signal: there is always an icon and
 * text too (doc 04 §3), so it works for colour-blind people and in TalkBack. The colour comes from
 * what it means ([StatusTone], COLOR-01), never picked by hand.
 */
@Composable
fun StatusPill(label: String, @DrawableRes icon: Int, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = tone.colors()
    Surface(shape = CircleShape, color = colors.container, contentColor = colors.content, modifier = modifier) {
        PillContent(label, icon, Modifier.padding(horizontal = 10.dp, vertical = Spacing.xxs))
    }
}

/**
 * A pill's icon and its text. The text grows with the phone's font size up to 1.5x (A11Y-03: at 200%
 * Bangla pills wrapped into 2-line blobs), and a long one takes a second line rather than losing words
 * (P2-3: "not listed" lost its "not").
 */
@Composable
private fun PillContent(label: String, @DrawableRes icon: Int, modifier: Modifier) {
    CapFontScale(MAX_LABEL_SCALE) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            modifier = modifier,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A fact about a person, not a state: a badge, "Active this week" (COLOR-01). Outlined with
 * small corners, so it doesn't read as a status pill; each tag has its own icon.
 */
@Composable
fun LLTag(label: String, @DrawableRes icon: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
    ) {
        PillContent(label, icon, Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs))
    }
}

/**
 * A warning about the thing itself, not its state: a request whose note mentions money (D15 a,
 * VT-03). Amber like the request page's notice, with small corners like a [LLTag].
 */
@Composable
fun WarningTag(label: String, modifier: Modifier = Modifier) {
    val colors = StatusTone.Urgent.colors()
    Surface(shape = MaterialTheme.shapes.extraSmall, color = colors.container, contentColor = colors.content, modifier = modifier) {
        PillContent(label, LLIcons.Warning, Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs))
    }
}

/**
 * A request's state. Open is the normal state, so where the urgency shows (lists, the detail
 * page) an open request shows its [UrgencyBadge] instead (COLOR-01).
 */
@Composable
fun StatusPill(status: RequestStatus, modifier: Modifier = Modifier) {
    val (icon, tone) = when (status) {
        RequestStatus.OPEN -> LLIcons.HourglassTop to StatusTone.Neutral
        RequestStatus.FULFILLED -> LLIcons.CheckCircle to StatusTone.Success
        RequestStatus.CANCELLED -> LLIcons.Cancel to StatusTone.Neutral
        RequestStatus.EXPIRED -> LLIcons.History to StatusTone.Neutral
        RequestStatus.REMOVED -> LLIcons.Block to StatusTone.Neutral
    }
    StatusPill(stringResource(status.labelRes), icon, tone, modifier)
}

/** An offer's state: on its way (grey, its own icon), done (green) or stopped (neutral). */
@Composable
fun StatusPill(status: ResponseStatus, modifier: Modifier = Modifier) {
    val (icon, tone) = when (status) {
        ResponseStatus.OFFERED -> LLIcons.VolunteerActivism to StatusTone.Progress
        ResponseStatus.CONFIRMED -> LLIcons.Verified to StatusTone.Progress
        ResponseStatus.DECLINED -> LLIcons.Block to StatusTone.Neutral
        ResponseStatus.WITHDRAWN -> LLIcons.Undo to StatusTone.Neutral
        ResponseStatus.DONATED -> LLIcons.Favorite to StatusTone.Success
    }
    StatusPill(stringResource(status.labelRes), icon, tone, modifier)
}

/**
 * How urgent a request is (doc 04 §3, COLOR-01): Critical = red + priority icon, Urgent = amber +
 * clock, Planned = neutral + calendar. In dark theme too, Critical stays the loudest.
 */
@Composable
fun UrgencyBadge(urgency: Urgency, modifier: Modifier = Modifier) {
    val (icon, tone) = when (urgency) {
        Urgency.CRITICAL -> LLIcons.PriorityHigh to StatusTone.Critical
        Urgency.URGENT -> LLIcons.Schedule to StatusTone.Urgent
        Urgency.PLANNED -> LLIcons.Event to StatusTone.Neutral
    }
    StatusPill(stringResource(urgency.labelRes), icon, tone, modifier)
}

/** The pill for an open request where urgency matters, the state's otherwise. */
@Composable
fun RequestStatePill(status: RequestStatus, urgency: Urgency, modifier: Modifier = Modifier) {
    if (status == RequestStatus.OPEN) UrgencyBadge(urgency, modifier) else StatusPill(status, modifier)
}
