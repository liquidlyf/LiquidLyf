package io.github.liquidlyf.feature.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.StatusTone
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLSwitchRow
import io.github.liquidlyf.core.designsystem.components.LLTag
import io.github.liquidlyf.core.designsystem.numberText
import io.github.liquidlyf.core.designsystem.components.BloodGroupBadge
import io.github.liquidlyf.core.designsystem.components.EligibilityRing
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.components.StatusPill
import io.github.liquidlyf.core.designsystem.components.eligibilityText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.dateText
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.domain.Badge
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.feature.home.readyText
import io.github.liquidlyf.feature.settings.ListMeDialog
import io.github.liquidlyf.feature.settings.rememberNotificationsEnabled
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun ProfileRoute(onEditProfile: () -> Unit, onOpenSettings: () -> Unit, onBecomeDonor: () -> Unit = {}, viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is ProfileEvent.Message -> launch { snackbar.showSnackbar(resources.getString(e.text)) }
                ProfileEvent.DonationSaved -> showAddSheet = false
            }
        }
    }
    ProfileScreen(
        state = state,
        onEditProfile = onEditProfile,
        onOpenSettings = onOpenSettings,
        onAddDonation = { showAddSheet = true },
        onRemoveDonation = viewModel::removeDonation,
        onRetry = viewModel::retry,
        onListedChange = viewModel::setListed,
        onAvailableChange = viewModel::setAvailable,
        onBecomeDonor = onBecomeDonor,
    )
    if (showAddSheet) {
        AddDonationSheet(
            earliest = viewModel.earliestDonationDate(),
            saving = state.saving,
            toDraft = { date, component, place -> DonationDraft(viewModel.donationInstant(date), component, place) },
            dateOf = viewModel::dateOf,
            onSave = viewModel::addDonation,
            onDismiss = { showAddSheet = false },
        )
    }
    state.pendingDonation?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::cancelEligibilityQuestion,
            title = { Text(stringResource(R.string.add_donation_update_title)) },
            text = {
                val after = viewModel.eligibleAfter(draft)
                val from = after?.let { dateText(it) }.orEmpty()
                // An old donation's rest period may be over already: then nothing pauses.
                val past = after != null && !after.isAfter(Instant.now())
                Text(stringResource(if (past) R.string.add_donation_update_body_past else R.string.add_donation_update_body, from))
            },
            confirmButton = { TextButton(onClick = { viewModel.answerEligibility(update = true) }) { Text(stringResource(R.string.add_donation_update_yes)) } },
            dismissButton = { TextButton(onClick = { viewModel.answerEligibility(update = false) }) { Text(stringResource(R.string.add_donation_update_no)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddDonation: () -> Unit,
    onRemoveDonation: (Donation) -> Unit,
    onRetry: () -> Unit,
    now: Instant = Instant.now(),
    onListedChange: (Boolean) -> Unit = {},
    onAvailableChange: (Boolean) -> Unit = {},
    notificationsOn: Boolean = rememberNotificationsEnabled(),
    onBecomeDonor: () -> Unit = {},
) {
    var toRemove by remember { mutableStateOf<Donation?>(null) }
    var askListing by rememberSaveable { mutableStateOf(false) }
    // N1: the last 3 donations first; "Load more" shows the older ones.
    var showAllDonations by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.nav_profile),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(LLIcons.Settings), contentDescription = stringResource(R.string.action_open_settings))
                    }
                },
            )
        },
    ) { padding ->
        val p = state.profile
        if (p == null) {
            LoadingState(Modifier.padding(padding).padding(horizontal = screenGutter(), vertical = Spacing.md))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = screenGutter(), vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // N1: you, your donor status, your donations, your impact, your badges: what you
                // change comes before what you only look at.
                // D1: someone who isn't a donor yet sees "Want to donate too?" instead of the donor parts.
                if (!p.hasDonorDetails) {
                    item { HeaderCard(state, onEditProfile) }
                    item { BecomeDonorCard(onBecomeDonor = onBecomeDonor) }
                    return@LazyColumn
                }
                if (p.bloodGroup == null) item { BloodGroupReminder(onAdd = onEditProfile) }
                item { HeaderCard(state, onEditProfile) }
                if (p.bloodGroup != null) {
                    item {
                        DonorCard(
                            state, notificationsOn, now,
                            onListedChange = { on -> if (on) askListing = true else onListedChange(false) },
                            onAvailableChange = onAvailableChange,
                        )
                    }
                }
                item { SectionHeader(stringResource(R.string.profile_history_title)) }
                // Adding a past donation sets the next date, so it comes first (N1).
                item {
                    FilledTonalButton(
                        onClick = onAddDonation,
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
                    ) {
                        Icon(painterResource(LLIcons.Add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.action_add_past_donation))
                    }
                }
                val donations = state.donations
                when {
                    // STATE-05: the shared error state, like every other screen.
                    donations == null && state.donationsFailed -> item {
                        ErrorState(onRetry = onRetry, title = stringResource(R.string.profile_history_error))
                    }
                    donations == null -> item { LoadingState(count = 2) }
                    donations.isEmpty() -> item {
                        Text(stringResource(R.string.profile_history_empty), style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        val shown = if (showAllDonations) donations else donations.take(RECENT_DONATIONS)
                        items(shown, key = { it.id }) { d ->
                            DonationRow(d, canRemove = !state.saving, onRemove = { toRemove = d })
                        }
                        if (!showAllDonations && donations.size > RECENT_DONATIONS) {
                            item(key = "load-more") {
                                TextButton(onClick = { showAllDonations = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
                                    Text(stringResource(R.string.action_load_more))
                                }
                            }
                        }
                    }
                }
                item { ImpactCard(state) }
                item { SectionHeader(stringResource(R.string.profile_badges_title)) }
                item { BadgesRow(state.badges) }
                // "Share LiquidLyf" moved to Settings → About (N5, OK'd 18 Nov 02:11).
            }
        }
    }
    val group = state.profile?.bloodGroup
    if (askListing && group != null) {
        ListMeDialog(
            bloodGroup = group,
            onConfirm = {
                askListing = false
                onListedChange(true)
            },
            onDismiss = { askListing = false },
        )
    }
    toRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text(stringResource(R.string.profile_remove_title)) },
            text = { Text(stringResource(R.string.profile_remove_body)) },
            confirmButton = {
                TextButton(onClick = { toRemove = null; onRemoveDonation(d) }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderCard(state: ProfileUiState, onEditProfile: () -> Unit) {
    val p = state.profile ?: return
    val locale = currentAppLocale()
    LLCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Spacing.md, top = Spacing.md, bottom = Spacing.md, end = Spacing.xs), verticalAlignment = Alignment.Top) {
            p.bloodGroup?.let { BloodGroupBadge(it, size = 64.dp, hero = true) }
            Column(Modifier.padding(start = if (p.bloodGroup != null) Spacing.md else 0.dp).weight(1f)) {
                Text(p.fullName, style = MaterialTheme.typography.titleLarge)
                state.geo?.let { Text(it.placeLabel(p.area.district, p.area.upazila, locale), style = MaterialTheme.typography.bodyMedium) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    VerifiedTag(R.string.profile_email_verified)
                    if (p.phoneVerified) VerifiedTag(R.string.profile_phone_verified)
                    if (!p.showInDonorSearch) {
                        StatusPill(stringResource(R.string.profile_not_listed), LLIcons.VisibilityOff, StatusTone.Neutral)
                    }
                }
            }
            // UI-07: a pencil on your profile opens Edit profile (48 dp; TalkBack: "Edit profile").
            IconButton(onClick = onEditProfile) {
                Icon(painterResource(LLIcons.Edit), contentDescription = stringResource(R.string.title_edit_profile))
            }
        }
    }
}

/**
 * N1 / NAV-01: your donor status in one card: the ring (full = you can donate now; not full = the
 * days left), and both donor switches, each line true for the state it's in (COPY-04). Settings
 * keeps the switches too.
 */
@Composable
private fun DonorCard(
    state: ProfileUiState,
    notificationsOn: Boolean,
    now: Instant,
    onListedChange: (Boolean) -> Unit,
    onAvailableChange: (Boolean) -> Unit,
) {
    val p = state.profile ?: return
    val e = state.eligibility ?: return
    val resting = IneligibleReason.REST_PERIOD in e.reasons
    LLCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            EligibilityRing(e, p.gender, now, size = 72.dp, showDays = true)
            Column(Modifier.padding(start = Spacing.md).weight(1f)) {
                Text(stringResource(R.string.profile_eligibility_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(eligibilityText(e, now), style = MaterialTheme.typography.titleMedium)
                if (resting) {
                    Text(stringResource(R.string.profile_next_eligible, dateText(e.eligibleFrom)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LLSwitchRow(
            title = stringResource(R.string.settings_list_me),
            checked = p.showInDonorSearch,
            onCheckedChange = onListedChange,
            summary = stringResource(if (p.showInDonorSearch) R.string.profile_listed_on else R.string.profile_listed_off),
            enabled = !state.saving,
            horizontalPadding = Spacing.md,
        )
        if (p.showInDonorSearch) {
            LLSwitchRow(
                title = stringResource(R.string.home_ready_to_donate),
                checked = p.available,
                onCheckedChange = onAvailableChange,
                summary = when {
                    p.available && notificationsOn && resting -> stringResource(R.string.profile_alerts_again_on, dateText(e.eligibleFrom))
                    else -> stringResource(readyText(p.available, notificationsOn))
                },
                enabled = !state.saving,
                horizontalPadding = Spacing.md,
            )
        }
    }
}

@Composable
private fun VerifiedTag(@StringRes label: Int) {
    StatusPill(stringResource(label), LLIcons.Verified, StatusTone.Success)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImpactCard(state: ProfileUiState) {
    val i = state.impact
    LLCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.profile_impact_title), style = MaterialTheme.typography.titleMedium)
            // Three equal columns when they fit; with large text a stat whose label won't fit moves to
            // the next line instead of breaking the word ("Donat/ions" at 200%).
            FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm), maxItemsInEachRow = 3) {
                Stat(i.donations, R.string.profile_impact_donations, Modifier.weight(1f))
                Stat(i.verifiedDonations, R.string.profile_impact_verified, Modifier.weight(1f))
                Stat(i.requestsHelped, R.string.profile_impact_requests, Modifier.weight(1f))
            }
            Text(
                if (i.livesHelped == 0) stringResource(R.string.profile_impact_first) else pluralStringResource(R.plurals.profile_impact_lives, i.livesHelped, i.livesHelped),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(R.string.profile_impact_lives_info), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One number with its label; read as one item ("3, Donations"). */
@Composable
private fun Stat(value: Int, @StringRes label: Int, modifier: Modifier = Modifier) {
    val text = stringResource(label)
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(numberText(value), style = MaterialTheme.typography.headlineSmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgesRow(badges: List<Badge>) {
    if (badges.isEmpty()) {
        Text(stringResource(R.string.profile_badges_none), style = MaterialTheme.typography.bodyMedium)
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        badges.forEach { b ->
            val name = stringResource(b.nameRes)
            val desc = stringResource(b.descriptionRes)
            LLTag(name, b.icon, modifier = Modifier.clearAndSetSemantics { contentDescription = "$name: $desc" })
        }
    }
}

@Composable
private fun DonationRow(d: Donation, canRemove: Boolean, onRemove: () -> Unit) {
    val component = stringResource(d.component.labelRes)
    ListItem(
        headlineContent = { Text(dateText(d.donatedAt)) },
        supportingContent = { Text(listOfNotNull(component, d.hospitalName).joinToString(" · ")) },
        overlineContent = {
            Text(stringResource(if (d.verified) R.string.profile_history_verified else R.string.profile_history_self))
        },
        leadingContent = {
            Icon(painterResource(if (d.verified) LLIcons.Verified else LLIcons.History), contentDescription = null)
        },
        trailingContent = if (d.source == DonationSource.SELF) {
            {
                IconButton(onClick = onRemove, enabled = canRemove) {
                    Icon(painterResource(LLIcons.Delete), contentDescription = stringResource(R.string.action_remove_donation))
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private val Badge.nameRes: Int
    get() = when (this) {
        Badge.FIRST_DROP -> R.string.badge_first_drop
        Badge.LIFE_SAVER -> R.string.badge_life_saver
        Badge.HERO -> R.string.badge_hero
        Badge.LEGEND -> R.string.badge_legend
        Badge.RAPID_RESPONDER -> R.string.badge_rapid_responder
        Badge.REGULAR -> R.string.badge_regular
    }

private val Badge.descriptionRes: Int
    get() = when (this) {
        Badge.FIRST_DROP -> R.string.badge_first_drop_desc
        Badge.LIFE_SAVER -> R.string.badge_life_saver_desc
        Badge.HERO -> R.string.badge_hero_desc
        Badge.LEGEND -> R.string.badge_legend_desc
        Badge.RAPID_RESPONDER -> R.string.badge_rapid_responder_desc
        Badge.REGULAR -> R.string.badge_regular_desc
    }

/** Each badge its own icon (COLOR-01: all six used the same heart on a cyan pill). */
private val Badge.icon: Int
    get() = when (this) {
        Badge.FIRST_DROP -> LLIcons.BloodDrop
        Badge.LIFE_SAVER -> LLIcons.Favorite
        Badge.HERO -> LLIcons.Shield
        Badge.LEGEND -> LLIcons.Premium
        Badge.RAPID_RESPONDER -> LLIcons.Bolt
        Badge.REGULAR -> LLIcons.EventRepeat
    }

/** N1: the donation history shows this many before "Load more". */
private const val RECENT_DONATIONS = 3
