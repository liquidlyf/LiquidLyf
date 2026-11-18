package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.dateTimeText
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.relativeTimeText
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Urgency
import java.time.Instant

/**
 * A blood request in a list (doc 04 §3): group badge, units and component, urgency or status,
 * hospital, area, when it's needed, the condition and how many people offered. [footer] adds a
 * line, e.g. my offer's status in "My offers".
 */
@Composable
fun RequestCard(
    request: BloodRequest,
    placeText: String,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    // 8.5: side by side when they fit; with large text or a long label, the pill wraps below.
    val title = @Composable {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                unitsText(request.unitsNeeded, stringResource(request.component.labelRes)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = Spacing.xs),
            )
            RequestStatePill(request.status, request.urgency, Modifier.align(Alignment.CenterVertically))
        }
    }
    LLCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        if (isLargeText()) {
            // P2-4: with large text the badge shares only the first row and the details use the
            // full width (beside it, the place took 4 lines and one card was taller than the list).
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    BloodGroupBadge(request.bloodGroup)
                    Column(Modifier.weight(1f)) { title() }
                }
                RequestCardDetails(request, placeText, now, footer)
            }
        } else {
            Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                BloodGroupBadge(request.bloodGroup)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    title()
                    RequestCardDetails(request, placeText, now, footer)
                }
            }
        }
    }
}

/** A request card's hospital, place, time, condition and offers, then its [footer]. */
@Composable
private fun RequestCardDetails(request: BloodRequest, placeText: String, now: Instant, footer: (@Composable () -> Unit)?) {
    IconLine(LLIcons.Hospital, request.hospitalName)
    IconLine(LLIcons.Location, placeText)
    // A closed request needs nothing any more: its date, not "Needed in 5 h".
    // A planned request shows its date, not "Needed in 47 h" (COPY-04).
    IconLine(
        LLIcons.Schedule,
        when {
            request.status != RequestStatus.OPEN -> stringResource(R.string.request_was_needed_by, dateTimeText(request.neededBy)) // Low 11
            request.urgency == Urgency.PLANNED -> stringResource(R.string.request_needed_on, dateTimeText(request.neededBy))
            else -> neededByText(request.neededBy, now)
        },
    )
    val extras = listOfNotNull(
        request.condition?.let { stringResource(it.labelRes) },
        request.liveOffers.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.request_offers_count, it, it)
        },
    )
    if (extras.isNotEmpty()) {
        Text(
            extras.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    footer?.invoke()
}

/** "2 units of whole blood". */
@Composable
fun unitsText(units: Int, component: String): String = pluralStringResource(R.plurals.request_units, units, units, component)

/** "Needed in 3 h" / "Needed 2 h ago"; [long]: "Needed in 3 hours". */
@Composable
fun neededByText(neededBy: Instant, now: Instant, long: Boolean = false): String =
    stringResource(R.string.request_needed_by, relativeTimeText(neededBy, now, long))

/**
 * A small icon and its text. The text wraps as far as it needs: with 200% text even an area like
 * "Dhaka South City Corporation, Dhaka" takes three lines, and the offer sheet's safety line more.
 */
@Composable
fun IconLine(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FirstLineIcon(icon, style)
        Text(text, style = style)
    }
}

/**
 * A row's small icon, centred on the first line of text in [style], not on the whole block
 * (Low 21: at 200% icons floated in the middle of 3-line text).
 */
@Composable
fun FirstLineIcon(@DrawableRes icon: Int, style: TextStyle, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val lineHeight = with(LocalDensity.current) {
        (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toDp()
    }
    Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}
