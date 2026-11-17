package io.github.liquidlyf.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.EligibilityRing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.LARGE_FONT_SCALE
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLExtendedFab
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.OfflineBanner
import io.github.liquidlyf.core.designsystem.components.RequestCard
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.components.eligibilityText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.rememberNow
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.profile.BloodGroupReminder
import io.github.liquidlyf.feature.settings.ListMeDialog
import io.github.liquidlyf.feature.settings.NotificationsOffBanner
import io.github.liquidlyf.feature.settings.rememberNotificationsEnabled
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun HomeRoute(
    onOpenRequest: (String) -> Unit,
    onCreateRequest: () -> Unit,
    onOpenNotifications: () -> Unit,
    onEditProfile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val unreadInbox by viewModel.unreadInbox.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e -> if (e is HomeEvent.Message) launch { snackbar.showSnackbar(resources.getString(e.text)) } }
    }
    HomeScreen(state, online, viewModel::onAvailableToggled, onOpenRequest, onCreateRequest, onOpenNotifications, onEditProfile, unreadInbox, onListMe = viewModel::listMe)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    online: Boolean,
    onAvailableToggled: (Boolean) -> Unit,
    onOpenRequest: (String) -> Unit,
    onCreateRequest: () -> Unit,
    onOpenNotifications: () -> Unit,
    onEditProfile: () -> Unit,
    unreadInbox: Boolean = false,
    notificationsEnabled: Boolean = rememberNotificationsEnabled(),
    onListMe: () -> Unit = {},
) {
    val now by rememberNow()
    val launcher = rememberSafeIntentLauncher()
    val locale = currentAppLocale()
    // UI-08: with large text "Request blood" is a full-width bar above the navigation instead of
    // floating, where it covered a request's hospital and area at 200%.
    val actionAsBar = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.nav_home),
                actions = {
                    IconButton(onClick = onOpenNotifications) {
                        BadgedBox(badge = { if (unreadInbox) Badge() }) {
                            Icon(
                                painterResource(if (unreadInbox) LLIcons.NotificationsFilled else LLIcons.Notifications),
                                contentDescription = stringResource(
                                    if (unreadInbox) R.string.action_open_notifications_unread else R.string.action_open_notifications,
                                ),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!actionAsBar) LLExtendedFab(text = stringResource(R.string.action_request_blood), icon = LLIcons.Add, onClick = onCreateRequest)
        },
        bottomBar = {
            if (actionAsBar) LLBottomBar(stringResource(R.string.action_request_blood), onCreateRequest, primaryIcon = LLIcons.Add)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OfflineBanner(online)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = screenGutter(), vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // Phase 5.10: without notifications a donor never hears about a request.
                    if (!notificationsEnabled) {
                        item(key = "notifications-off") { NotificationsOffBanner(onTurnOn = launcher::openNotificationSettings) }
                    }
                    item(key = "availability") { AvailabilityCard(state, onAvailableToggled, onEditProfile, onListMe) }
                    item(key = "header") { SectionHeader(stringResource(R.string.home_urgent_near_you)) }
                    val list = state.requests
                    val group = state.profile?.bloodGroup
                    when {
                        list == null -> item(key = "loading") { LoadingState(count = 2) }
                        group == null -> item(key = "no-group") {
                            EmptyState(
                                icon = LLIcons.BloodDrop,
                                title = stringResource(R.string.home_no_group_title),
                                body = stringResource(R.string.home_no_group_body),
                            )
                        }
                        list.isEmpty() -> item(key = "empty") {
                            EmptyState(
                                icon = LLIcons.Favorite,
                                title = stringResource(R.string.home_empty_title, stringResource(group.labelRes)),
                                body = stringResource(R.string.home_empty_body),
                                actionLabel = stringResource(R.string.home_change_area),
                                onAction = onEditProfile,
                            )
                        }
                        else -> items(list, key = { it.id }) { r ->
                            RequestCard(
                                request = r,
                                placeText = state.geo?.placeLabel(r.district, r.upazila, locale) ?: r.district,
                                now = now,
                                onClick = { onOpenRequest(r.id) },
                            )
                        }
                    }
                    if (!actionAsBar) item(key = "fab-space") { Spacer(Modifier.height(FAB_CLEARANCE)) }
                }
            }
        }
    }
}

private val FAB_CLEARANCE = 88.dp

/** Availability and eligibility (doc 04 §4): the whole row toggles, for TalkBack too. */
@Composable
private fun AvailabilityCard(state: HomeUiState, onAvailableToggled: (Boolean) -> Unit, onEditProfile: () -> Unit, onListMe: () -> Unit) {
    val p = state.profile ?: return
    when {
        p.bloodGroup == null -> BloodGroupReminder(onAdd = onEditProfile)
        !p.showInDonorSearch -> LLCard {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.home_not_listed_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_not_listed_body), style = MaterialTheme.typography.bodyMedium)
                var asking by remember { mutableStateOf(false) }
                TextButton(onClick = { asking = true }) { Text(stringResource(R.string.home_list_me)) }
                val group = p.bloodGroup
                if (asking && group != null) {
                    ListMeDialog(
                        bloodGroup = group,
                        onConfirm = {
                            asking = false
                            onListMe()
                        },
                        onDismiss = { asking = false },
                    )
                }
            }
        }
        else -> LLCard {
            Row(Modifier.padding(start = Spacing.md, top = Spacing.md, end = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                state.eligibility?.let { EligibilityRing(it, p.gender, Instant.now()) }
                Text(
                    state.eligibility?.let { eligibilityText(it, Instant.now()) }.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = Spacing.md).weight(1f),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_ready_to_donate)) },
                supportingContent = {
                    Text(stringResource(if (state.available) R.string.home_ready_on else R.string.home_ready_off))
                },
                trailingContent = { Switch(checked = state.available, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.toggleable(
                    value = state.available,
                    enabled = !state.savingAvailability,
                    role = Role.Switch,
                    onValueChange = onAvailableToggled,
                ),
            )
        }
    }
}
