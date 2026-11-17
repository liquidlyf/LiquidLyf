package io.github.liquidlyf.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BloodGroupBadge
import io.github.liquidlyf.core.designsystem.components.EligibilityRing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLExtendedFab
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.OfflineBanner
import io.github.liquidlyf.core.designsystem.components.RequestCard
import io.github.liquidlyf.core.designsystem.components.RequestStatePill
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.components.UnitDrops
import io.github.liquidlyf.core.designsystem.components.eligibilityText
import io.github.liquidlyf.core.designsystem.components.isLargeText
import io.github.liquidlyf.core.designsystem.components.neededByText
import io.github.liquidlyf.core.designsystem.components.unitsText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.dateText
import io.github.liquidlyf.core.designsystem.dateTimeText
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.rememberNow
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.profile.BecomeDonorCard
import io.github.liquidlyf.feature.profile.BloodGroupReminder
import io.github.liquidlyf.feature.settings.ListMeDialog
import io.github.liquidlyf.feature.settings.rememberNotificationsEnabled
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun HomeRoute(
    onOpenRequest: (String) -> Unit,
    onCreateRequest: () -> Unit,
    onOpenNotifications: () -> Unit,
    onEditProfile: () -> Unit,
    onBecomeDonor: () -> Unit = {},
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
    // Back on Home after posting a request: it shows as "Your request" (FLOW-03).
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    HomeScreen(
        state, online, viewModel::onAvailableToggled, onOpenRequest, onCreateRequest, onOpenNotifications, onEditProfile, unreadInbox,
        onListMe = viewModel::listMe, onRetryFeed = viewModel::retryFeed, onBecomeDonor = onBecomeDonor,
    )
}

/**
 * Home in the maintainer's order (N6, D8): the one-line donor status, then "Your request" while it's
 * open, then the requests you can help with. With notifications off, a small notice comes first.
 */
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
    onRetryFeed: () -> Unit = {},
    onBecomeDonor: () -> Unit = {},
) {
    val now by rememberNow()
    val launcher = rememberSafeIntentLauncher()
    val locale = currentAppLocale()
    // UI-08: with large text "Request blood" is a full-width bar above the navigation instead of
    // floating, where it covered a request's hospital and area at 200%.
    val actionAsBar = isLargeText()
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.app_name),
                brand = true,
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
                    // Phase 5.10, STATE-04: without notifications a donor never hears about a request.
                    if (!notificationsEnabled) {
                        item(key = "notifications-off") { NotificationsNotice(onTurnOn = launcher::openNotificationSettings) }
                    }
                    val p = state.profile?.takeIf { state.ownLoaded }
                    // D1: a donor has answered the donor questions; only then is there a line, a ring and a feed.
                    val donor = p?.hasDonorDetails == true
                    val group = p?.bloodGroup?.takeIf { donor }
                    val own = state.ownRequests
                    val becomeDonorBelow = BECOME_DONOR_BELOW_OWN_REQUEST && own.isNotEmpty()
                    when {
                        p == null -> Unit
                        group != null -> item(key = "donor-line") { DonorLine(state, notificationsEnabled, now, onAvailableToggled, onListMe) }
                        // Answered the questions but doesn't know the group yet (P1).
                        donor -> item(key = "no-group") { BloodGroupReminder(onAdd = onEditProfile) }
                        !becomeDonorBelow -> item(key = "become-donor") { BecomeDonorCard(onBecomeDonor = onBecomeDonor) }
                    }
                    items(own, key = { "own-${it.id}" }) { r -> OwnRequestCard(r, now, onOpen = { onOpenRequest(r.id) }) }
                    if (p != null && !donor && becomeDonorBelow) {
                        item(key = "become-donor") { BecomeDonorCard(onBecomeDonor = onBecomeDonor) }
                    }
                    val list = state.requests
                    when {
                        p == null -> item(key = "loading") { LoadingState(count = 2) }
                        // Not a donor yet: the card above says what to do; there's nothing to answer.
                        group == null -> Unit
                        else -> {
                            item(key = "header") { SectionHeader(stringResource(R.string.home_urgent_near_you)) }
                            when {
                                list == null && state.feedError != null -> item(key = "error") {
                                    ErrorState(state.feedError, onRetry = onRetryFeed, title = stringResource(R.string.home_load_error))
                                }
                                list == null -> item(key = "loading") { LoadingState(count = 2) }
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
                        }
                    }
                    if (!actionAsBar) item(key = "fab-space") { Spacer(Modifier.height(FAB_CLEARANCE)) }
                }
            }
        }
    }
}

private val FAB_CLEARANCE = 88.dp

/**
 * Where "Want to donate too?" goes while the person's own request is open: above it, in the donor
 * line's place (the maintainer, 18 Nov 2026, N6). True would move it below.
 */
private const val BECOME_DONOR_BELOW_OWN_REQUEST = false

/**
 * Notifications off (STATE-04, N6): a small amber notice with "Turn on" beside it, not a tall card.
 * With large text only its title and the button stay, so a request still starts on the first screen.
 */
@Composable
private fun NotificationsNotice(onTurnOn: () -> Unit) {
    val colors = LiquidLyfTheme.extendedColors
    val large = isLargeText()
    Surface(color = colors.warningContainer, contentColor = colors.onWarningContainer, shape = MaterialTheme.shapes.medium) {
        val icon = @Composable { Icon(painterResource(LLIcons.NotificationsOff), contentDescription = null) }
        val button = @Composable { modifier: Modifier ->
            Button(onClick = onTurnOn, modifier = modifier.heightIn(min = Spacing.minTouchTarget)) { Text(stringResource(R.string.notif_off_turn_on)) }
        }
        if (large) {
            Column(Modifier.fillMaxWidth().padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    icon()
                    Text(stringResource(R.string.home_notif_off_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                }
                button(Modifier.padding(start = 36.dp))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, top = Spacing.xs, end = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                icon()
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_notif_off_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.home_notif_off_body), style = MaterialTheme.typography.bodySmall)
                }
                button(Modifier)
            }
        }
    }
}

/**
 * The one-line donor status (UI-04, COPY-04, N6): the ring (full = can donate now, the days left
 * inside it while resting, grey when switched off), what it means, and "Ready to donate" as the
 * switch. Each line is true for its state. The whole card toggles, for TalkBack too.
 */
@Composable
private fun DonorLine(
    state: HomeUiState,
    notificationsOn: Boolean,
    now: Instant,
    onAvailableToggled: (Boolean) -> Unit,
    onListMe: () -> Unit,
) {
    val p = state.profile ?: return
    val e = state.eligibility ?: return
    if (!p.showInDonorSearch) {
        NotListedLine(state, now, onListMe)
        return
    }
    val on = state.available
    val large = isLargeText()
    val resting = IneligibleReason.REST_PERIOD in e.reasons
    val title = if (on) eligibilityText(e, now) else stringResource(R.string.home_not_ready_title)
    val line = when {
        !on -> stringResource(R.string.home_ready_off)
        // With large text the notice above already says why: only "Ready to donate" here.
        !notificationsOn -> stringResource(if (large) R.string.home_ready_to_donate else R.string.home_line_notifications_off)
        resting -> stringResource(R.string.home_line_resting, dateText(e.eligibleFrom))
        else -> stringResource(R.string.home_line_ready)
    }
    val ring = @Composable { EligibilityRing(e, p.gender, now, size = 48.dp, showDays = true, dimmed = !on) }
    val switch = @Composable { Switch(checked = on, onCheckedChange = null, enabled = !state.savingAvailability) }
    LLCard(Modifier.fillMaxWidth()) {
        val toggle = Modifier
            .fillMaxWidth()
            .toggleable(value = on, enabled = !state.savingAvailability, role = Role.Switch, onValueChange = onAvailableToggled)
            .padding(Spacing.md)
        if (large) {
            Column(toggle, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ring()
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    switch()
                }
            }
        } else {
            Row(toggle, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ring()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                switch()
            }
        }
    }
}

/** A donor who isn't listed: the grey ring and "List me as a donor", which asks for consent first (8.1). */
@Composable
private fun NotListedLine(state: HomeUiState, now: Instant, onListMe: () -> Unit) {
    val p = state.profile ?: return
    val e = state.eligibility ?: return
    var asking by remember { mutableStateOf(false) }
    LLCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            EligibilityRing(e, p.gender, now, size = 48.dp, showDays = true, dimmed = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.home_not_listed_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_not_listed_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { asking = true }) { Text(stringResource(R.string.home_list_me)) }
            }
        }
    }
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

/**
 * "Your request" (FLOW-03, N6) while it's open: what's received, how many offered and when it's
 * needed, with "See offers". It goes when the request is closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnRequestCard(r: BloodRequest, now: Instant, onOpen: () -> Unit) {
    val label = stringResource(R.string.home_your_request)
    LLCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                // Capitals help English scan; Bangla has none.
                if (currentAppLocale() == AppLocale.BN) label else label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                BloodGroupBadge(r.bloodGroup)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    ) {
                        Text(
                            unitsText(r.unitsNeeded, stringResource(r.component.labelRes)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(end = Spacing.xs),
                        )
                        RequestStatePill(r.status, r.urgency, Modifier.align(Alignment.CenterVertically))
                    }
                    Text(r.hospitalName, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        UnitDrops(r.unitsFulfilled, r.unitsNeeded)
                        Text(
                            pluralStringResource(R.plurals.detail_units_progress, r.unitsNeeded, r.unitsFulfilled, r.unitsNeeded),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val offers = if (r.liveOffers > 0) {
                        pluralStringResource(R.plurals.request_offers_count, r.liveOffers, r.liveOffers)
                    } else {
                        stringResource(R.string.home_no_offers_yet)
                    }
                    val needed = if (r.urgency == Urgency.PLANNED) {
                        stringResource(R.string.request_needed_on, dateTimeText(r.neededBy))
                    } else {
                        neededByText(r.neededBy, now)
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(offers) }
                            append(" · ")
                            append(needed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            FilledTonalButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) { Text(stringResource(R.string.home_see_offers)) }
        }
    }
}

/**
 * What "Ready to donate" means right now (Profile and Settings): no promise of alerts while
 * notifications are off (STATE-04).
 */
@StringRes
internal fun readyText(available: Boolean, notificationsOn: Boolean): Int = when {
    !available -> R.string.home_ready_off
    !notificationsOn -> R.string.home_ready_on_notifications_off
    else -> R.string.home_ready_on
}
