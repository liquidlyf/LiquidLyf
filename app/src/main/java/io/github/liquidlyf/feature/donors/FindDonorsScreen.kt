package io.github.liquidlyf.feature.donors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.StatusTone
import io.github.liquidlyf.core.designsystem.components.BloodGroupBadge
import io.github.liquidlyf.core.designsystem.components.BloodGroupChip
import io.github.liquidlyf.core.designsystem.components.DropdownField
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLSwitchRow
import io.github.liquidlyf.core.designsystem.components.LLTag
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.SheetDragHandle
import io.github.liquidlyf.core.designsystem.components.StatusPill
import io.github.liquidlyf.core.designsystem.components.bloodGroupsPerRow
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.dateTimeText
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.feature.safety.ReportDialog
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
fun FindDonorsRoute(onCreateRequest: () -> Unit, viewModel: FindDonorsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            if (e is FindDonorsEvent.Message) {
                val text = if (e.arg != null) resources.getString(e.text, e.arg) else resources.getString(e.text)
                launch { snackbar.showSnackbar(text) }
            }
        }
    }
    FindDonorsScreen(
        state = state,
        actions = FindDonorsActions(
            onToggleGroup = viewModel::toggleGroup,
            onDivision = viewModel::setDivision,
            onDistrict = viewModel::setDistrict,
            onUpazila = viewModel::setUpazila,
            onEligibleNowOnly = viewModel::setEligibleNowOnly,
            onSearch = viewModel::search,
            onLoadMore = viewModel::loadMore,
            onOpenDonor = viewModel::openAsk,
            onCreateRequest = onCreateRequest,
        ),
    )
    var reporting by rememberSaveable { mutableStateOf(false) }
    var blocking by rememberSaveable { mutableStateOf(false) }
    state.ask?.let { sheet ->
        AskToDonateSheet(
            sheet = sheet,
            asking = state.asking,
            isAsked = { r -> viewModel.isAsked(r, sheet.donor) },
            onSelect = viewModel::selectRequest,
            onAsk = viewModel::ask,
            onCreateRequest = { viewModel.closeAsk(); onCreateRequest() },
            onReport = { reporting = true },
            onBlock = { blocking = true },
            onDismiss = viewModel::closeAsk,
        )
        if (reporting) {
            ReportDialog(R.string.report_person_title, onDismiss = { reporting = false }, onReport = { reason, note ->
                reporting = false
                viewModel.reportDonor(reason, note)
            })
        }
        if (blocking) {
            AlertDialog(
                onDismissRequest = { blocking = false },
                title = { Text(stringResource(R.string.block_title)) },
                text = { Text(stringResource(R.string.block_body)) },
                confirmButton = { TextButton(onClick = { blocking = false; viewModel.blockDonor() }) { Text(stringResource(R.string.action_block)) } },
                dismissButton = { TextButton(onClick = { blocking = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}

/** Everything the screen can ask for, kept in one place for previews and tests. */
data class FindDonorsActions(
    val onToggleGroup: (BloodGroup) -> Unit = {},
    val onDivision: (String) -> Unit = {},
    val onDistrict: (String) -> Unit = {},
    val onUpazila: (String?) -> Unit = {},
    val onEligibleNowOnly: (Boolean) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onOpenDonor: (DonorCard) -> Unit = {},
    val onCreateRequest: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FindDonorsScreen(state: FindDonorsUiState, actions: FindDonorsActions, now: Instant = Instant.now()) {
    val locale = currentAppLocale()
    // Open until the first search; then a one-line summary with "Change filters".
    var filtersOpen by rememberSaveable { mutableStateOf(true) }
    Scaffold(topBar = { LLTopBar(title = stringResource(R.string.nav_donors)) }) { padding ->
        when (state.enabled) {
            null -> {
                LoadingState(Modifier.padding(padding).padding(horizontal = screenGutter(), vertical = Spacing.md))
                return@Scaffold
            }
            false -> {
                EmptyState(
                    icon = LLIcons.Donors,
                    title = stringResource(R.string.donors_paused_title),
                    body = stringResource(R.string.donors_paused_body),
                    actionLabel = stringResource(R.string.action_request_blood),
                    onAction = actions.onCreateRequest,
                    modifier = Modifier.padding(padding),
                )
                return@Scaffold
            }
            true -> Unit
        }
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = screenGutter(), vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    if (filtersOpen || state.applied == null) {
                        FiltersCard(state, locale, actions, onSearch = { filtersOpen = false; actions.onSearch() })
                    } else {
                        FiltersSummary(state, locale, onChange = { filtersOpen = true })
                    }
                }
                val results = state.results
                when {
                    state.applied == null -> Unit
                    state.searching -> item { LoadingState(count = 3) }
                    state.error != null && results == null -> item { ErrorState(state.error, onRetry = actions.onSearch) }
                    results.isNullOrEmpty() -> item {
                        EmptyState(
                            icon = LLIcons.Donors,
                            title = stringResource(R.string.donors_empty_title),
                            body = stringResource(R.string.donors_empty_body),
                            actionLabel = stringResource(R.string.action_request_blood),
                            onAction = actions.onCreateRequest,
                        )
                    }
                    else -> {
                        state.total?.let { total ->
                            item {
                                val n = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                Text(pluralStringResource(R.plurals.donors_count, n, n), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        items(results, key = { it.uid }) { card -> DonorRow(card, state.geo, locale, now, onClick = { actions.onOpenDonor(card) }) }
                        if (!state.endReached) {
                            item {
                                TextButton(
                                    onClick = actions.onLoadMore,
                                    enabled = !state.loadingMore,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
                                ) { Text(stringResource(R.string.action_show_more)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersCard(state: FindDonorsUiState, locale: AppLocale, actions: FindDonorsActions, onSearch: () -> Unit) {
    val f = state.filters
    val geo = state.geo
    LLCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.donors_filter_groups), style = MaterialTheme.typography.labelLarge)
            // A 4×2 grid, like the profile's picker, but several groups can be picked.
            BloodGroup.entries.chunked(bloodGroupsPerRow()).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    row.forEach { g ->
                        BloodGroupChip(
                            g, selected = g in f.groups, onClick = { actions.onToggleGroup(g) },
                            modifier = Modifier.weight(1f), role = Role.Checkbox,
                        )
                    }
                }
            }
            if (geo != null) {
                val divisions = geo.divisions.map { it.code to it.name.localized(locale) }.sortedBy { it.second }
                DropdownField(stringResource(R.string.label_division), divisions, f.division, actions.onDivision)
                val districts = f.division?.let(geo::division)?.districts.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
                DropdownField(stringResource(R.string.label_district), districts, f.district, actions.onDistrict, enabled = f.division != null)
                val any = ANY to stringResource(R.string.donors_any_upazila)
                val areas = listOf(any) + f.district?.let(geo::district)?.areas.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
                DropdownField(
                    stringResource(R.string.label_upazila), areas, f.upazila ?: ANY,
                    { code -> actions.onUpazila(code.takeUnless { it == ANY }) }, enabled = f.district != null,
                )
            }
            LLSwitchRow(
                stringResource(R.string.donors_filter_eligible), f.eligibleNowOnly, actions.onEligibleNowOnly,
                summary = stringResource(R.string.donors_filter_eligible_summary),
            )
            if (f.groups.isEmpty()) {
                Text(stringResource(R.string.donors_pick_group), style = MaterialTheme.typography.bodySmall)
            }
            LLPrimaryButton(
                text = stringResource(R.string.action_search),
                onClick = onSearch,
                enabled = f.ready && !state.searching,
                loading = state.searching,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FiltersSummary(state: FindDonorsUiState, locale: AppLocale, onChange: () -> Unit) {
    val f = state.applied ?: return
    val groups = f.groups.sortedBy { it.ordinal }.map { stringResource(it.labelRes) }
    val place = f.district?.let { d -> state.geo?.placeLabel(d, f.upazila, locale) ?: d }
    val parts = listOfNotNull(groups.joinToString(", "), place, if (f.eligibleNowOnly) stringResource(R.string.donors_summary_eligible) else null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onChange, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) { Text(stringResource(R.string.action_change_filters)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DonorRow(card: DonorCard, geo: BdGeo?, locale: AppLocale, now: Instant, onClick: () -> Unit) {
    val place = geo?.placeLabel(card.area.district, card.area.upazila, locale) ?: card.area.district
    val eligibleText = if (!now.isBefore(card.eligibleFrom)) {
        stringResource(R.string.donors_eligible_now)
    } else {
        val days = EligibilityCalculator.daysUntil(card.eligibleFrom, now).toInt().coerceAtLeast(1)
        pluralStringResource(R.plurals.donors_eligible_in, days, days)
    }
    val activeThisWeek = card.lastActiveAt?.let { Duration.between(it, now) <= Duration.ofDays(7) } == true
    // Clipped, so the ripple keeps the card's round corners.
    LLCard(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClickLabel = stringResource(R.string.action_ask_to_donate), onClick = onClick)) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            BloodGroupBadge(card.bloodGroup, size = 48.dp)
            Column(Modifier.padding(start = Spacing.md).weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(card.displayName, style = MaterialTheme.typography.titleMedium)
                Text(place, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOf(eligibleText, pluralStringResource(R.plurals.donors_donations, card.donationCount, card.donationCount)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (card.phoneVerified || activeThisWeek) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (card.phoneVerified) {
                            StatusPill(stringResource(R.string.profile_phone_verified), LLIcons.Verified, StatusTone.Success)
                        }
                        // A tag with its own dot, not Urgent's clock (COLOR-01).
                        if (activeThisWeek) LLTag(stringResource(R.string.donors_active_week), LLIcons.ActiveDot)
                    }
                }
            }
        }
    }
}

/** 6.2: pick one of my open requests the donor's group can give to, then ask. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskToDonateSheet(
    sheet: AskSheet,
    asking: Boolean,
    isAsked: (io.github.liquidlyf.core.model.BloodRequest) -> Boolean,
    onSelect: (String) -> Unit,
    onAsk: () -> Unit,
    onCreateRequest: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val donor = sheet.donor
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.donors_ask_title, donor.displayName), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.donors_ask_body), style = MaterialTheme.typography.bodyMedium)
            val requests = sheet.requests
            when {
                requests == null -> LoadingState(count = 1)
                requests.isEmpty() -> {
                    val text = if (sheet.noneCompatible) {
                        stringResource(R.string.donors_ask_none_compatible, stringResource(donor.bloodGroup.labelRes))
                    } else {
                        stringResource(R.string.donors_ask_no_requests)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    LLPrimaryButton(text = stringResource(R.string.action_request_blood), onClick = onCreateRequest, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Text(stringResource(R.string.donors_ask_pick), style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        requests.forEach { r ->
                            ListItem(
                                headlineContent = { Text(listOf(stringResource(r.bloodGroup.labelRes), r.hospitalName).joinToString(" · ")) },
                                supportingContent = { Text(dateTimeText(r.neededBy)) },
                                leadingContent = { RadioButton(selected = r.id == sheet.selected, onClick = null) },
                                trailingContent = if (isAsked(r)) {
                                    { Text(stringResource(R.string.donors_asked_label), style = MaterialTheme.typography.labelMedium) }
                                } else {
                                    null
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.selectable(selected = r.id == sheet.selected, role = Role.RadioButton, onClick = { onSelect(r.id) }),
                            )
                        }
                    }
                    val selected = requests.firstOrNull { it.id == sheet.selected }
                    LLPrimaryButton(
                        text = stringResource(if (selected != null && isAsked(selected)) R.string.donors_asked_label else R.string.action_ask_to_donate),
                        onClick = onAsk,
                        enabled = selected != null && !isAsked(selected) && !asking,
                        loading = asking,
                        loadingText = stringResource(R.string.action_asking),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // 7.1/7.2: report or block this person: below a divider, in grey, so they aren't tapped
            // instead of "Ask to donate" (UI-07); a flow row, so with large text they never break
            // letter by letter (A11Y-03).
            HorizontalDivider(Modifier.padding(top = Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val quiet = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onReport, colors = quiet, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                    Text(stringResource(R.string.action_report_person))
                }
                TextButton(onClick = onBlock, colors = quiet, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                    Text(stringResource(R.string.action_block_person))
                }
            }
        }
    }
}

/** The "whole district" choice in the upazila dropdown. */
private const val ANY = ""
