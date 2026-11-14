package io.github.liquidlyf.feature.notifications

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.relativeTimeText
import io.github.liquidlyf.core.designsystem.rememberNow
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.Urgency

@Composable
fun InboxRoute(
    onBack: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    InboxScreen(state, onBack, onOpenRequest, viewModel::loadMore, onOpenSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxScreen(
    state: InboxUiState,
    onBack: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val entries = state.entries
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.title_notifications),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(LLIcons.Settings), contentDescription = stringResource(R.string.title_notification_settings))
                    }
                },
            )
        },
    ) { padding ->
        when {
            entries == null && state.error == null -> LoadingState(Modifier.padding(padding))
            entries.isNullOrEmpty() && state.error == null -> EmptyState(
                icon = LLIcons.Notifications,
                title = stringResource(R.string.inbox_empty_title),
                body = stringResource(R.string.inbox_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = Spacing.sm),
                ) {
                    items(entries.orEmpty(), key = { it.key }) { entry ->
                        InboxRow(entry, isNew = state.isNew(entry), state = state, onClick = { onOpenRequest(entry.requestId) })
                        HorizontalDivider()
                    }
                    item(key = "footer") { Footer(state, onLoadMore) }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(entry: InboxEntry, isNew: Boolean, state: InboxUiState, onClick: () -> Unit) {
    val now by rememberNow()
    val time = entry.at?.let { relativeTimeText(it, now) } ?: stringResource(R.string.inbox_just_now)
    val (icon, title, detail) = when (entry) {
        is InboxEntry.Update -> Triple(updateIcon(entry.item.type), stringResource(updateTitle(entry.item.type)), stringResource(updateBody(entry.item.type)))
        is InboxEntry.Alert -> {
            val a = entry.alert
            val group = BloodGroup.fromCode(a.bloodGroup)?.let { stringResource(it.labelRes) }
            val title = when {
                a.stillNeeded && group != null -> stringResource(R.string.notification_still_needed_title, group)
                a.stillNeeded -> stringResource(R.string.notification_still_needed_title_any)
                group != null -> stringResource(R.string.notification_new_request_title, group)
                else -> stringResource(R.string.notification_new_request_title_any)
            }
            val place = a.district?.let { state.geo?.placeLabel(it, null, currentAppLocale()) }
            val urgency = Urgency.fromCode(a.urgency)?.let { stringResource(it.labelRes) }
            Triple(LLIcons.BloodDrop, title, listOfNotNull(urgency, place).joinToString(" · "))
        }
    }
    val newLabel = stringResource(R.string.inbox_new)
    ListItem(
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = {
            Text(title, fontWeight = if (isNew) FontWeight.SemiBold else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(listOf(detail, time).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = if (isNew) ({ Badge() }) else null,
        modifier = Modifier
            .heightIn(min = Spacing.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { if (isNew) stateDescription = newLabel },
    )
}

@Composable
private fun Footer(state: InboxUiState, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(Spacing.md), contentAlignment = Alignment.Center) {
        when {
            state.loadingMore -> CircularProgressIndicator()
            state.error != null -> TextButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.inbox_load_failed, stringResource(state.error)))
            }
            !state.endReached -> TextButton(onClick = onLoadMore, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.inbox_load_more))
            }
        }
    }
}

@DrawableRes
private fun updateIcon(type: InboxType): Int = when (type) {
    InboxType.NEW_OFFER -> LLIcons.VolunteerActivism
    InboxType.OFFER_CONFIRMED -> LLIcons.CheckCircle
    InboxType.OFFER_DECLINED -> LLIcons.Cancel
    InboxType.DONATION_RECORDED -> LLIcons.Favorite
    InboxType.ASKED_TO_DONATE -> LLIcons.BloodDrop
}

private fun updateTitle(type: InboxType): Int = when (type) {
    InboxType.NEW_OFFER -> R.string.notification_new_offer_title
    InboxType.OFFER_CONFIRMED -> R.string.notification_offer_confirmed_title
    InboxType.OFFER_DECLINED -> R.string.notification_offer_declined_title
    InboxType.DONATION_RECORDED -> R.string.notification_donation_recorded_title
    InboxType.ASKED_TO_DONATE -> R.string.notification_asked_title
}

private fun updateBody(type: InboxType): Int = when (type) {
    InboxType.NEW_OFFER -> R.string.notification_new_offer_body
    InboxType.OFFER_CONFIRMED -> R.string.notification_offer_confirmed_body
    InboxType.OFFER_DECLINED -> R.string.notification_offer_declined_body
    InboxType.DONATION_RECORDED -> R.string.notification_donation_recorded_body
    InboxType.ASKED_TO_DONATE -> R.string.notification_asked_body
}
