package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.relativeTimeText
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.RequestStatus
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
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BloodGroupBadge(request.bloodGroup)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        unitsText(request.unitsNeeded, stringResource(request.component.labelRes)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (request.status == RequestStatus.OPEN) UrgencyBadge(request.urgency) else StatusPill(request.status)
                }
                IconLine(LLIcons.Hospital, request.hospitalName)
                IconLine(LLIcons.Location, placeText)
                IconLine(LLIcons.Schedule, neededByText(request.neededBy, now))
                val extras = listOfNotNull(
                    request.condition?.let { stringResource(it.labelRes) },
                    request.responseCount.takeIf { it > 0 }?.let {
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
        }
    }
}

/** "2 units of whole blood". */
@Composable
fun unitsText(units: Int, component: String): String = pluralStringResource(R.plurals.request_units, units, units, component)

/** "Needed in 3 h" / "Needed 2 h ago". */
@Composable
fun neededByText(neededBy: Instant, now: Instant): String =
    stringResource(R.string.request_needed_by, relativeTimeText(neededBy, now))

/** A small icon and one line of text. */
@Composable
fun IconLine(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
