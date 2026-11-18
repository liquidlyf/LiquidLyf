package io.github.liquidlyf.feature.donors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.StatusTone
import io.github.liquidlyf.core.designsystem.components.BloodGroupBadge
import io.github.liquidlyf.core.designsystem.components.BloodGroupChip
import io.github.liquidlyf.core.designsystem.components.ButtonLabel
import io.github.liquidlyf.core.designsystem.components.ChooseHint
import io.github.liquidlyf.core.designsystem.components.DialogText
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
import io.github.liquidlyf.core.designsystem.components.isLargeText
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
    // Back after posting or closing a request: "post a request first" follows (FLOW-06).
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
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
            place = state.geo?.placeLabel(sheet.donor.area.district, sheet.donor.area.upazila, currentAppLocale()),
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
                text = { DialogText(stringResource(R.string.block_body)) },
                confirmButton = {
                    // Low 7: blocking can't be undone here, so it's a filled error button, unlike Cancel.
                    Button(
                        onClick = { blocking = false; viewModel.blockDonor() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.action_block)) }
                },
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
    // FLOW-06 part 1: a search from a request page shows its results, with the filters folded.
    LaunchedEffect(state.handedSearches) { if (state.handedSearches > 0) filtersOpen = false }
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
                    modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()), // P2-2: scrolls when it doesn't fit
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
                // FLOW-06: said up front, not only after picking a donor.
                if (state.hasOpenRequest == false) item(key = "post-first") { PostFirstCard(actions.onCreateRequest) }
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
                    state.error != null && results == null -> item { ErrorState(state.error, onRetry = actions.onSearch, title = stringResource(R.string.donors_load_error)) }
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
                        items(results, key = { it.uid }) { card ->
                            DonorRow(card, state.geo, locale, now, searchedUpazila = state.applied?.upazila, onClick = { actions.onOpenDonor(card) })
                        }
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
                // N3 / FORM-01: the same one-at-a-time pickers as the setup, with "Whole district" last.
                val divisions = geo.divisions.map { it.code to it.name.localized(locale) }.sortedBy { it.second }
                DropdownField(
                    stringResource(R.string.label_division), divisions, f.division, actions.onDivision,
                    placeholder = stringResource(R.string.area_choose_division), labelAbove = true,
                )
                val divisionInfo = f.division?.let(geo::division)
                val districts = divisionInfo?.districts.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
                DropdownField(
                    stringResource(R.string.label_district), districts, f.district, actions.onDistrict, enabled = f.division != null, labelAbove = true,
                    placeholder = stringResource(if (f.division == null) R.string.area_division_first else R.string.area_choose_district),
                    menuTitle = divisionInfo?.let { pluralStringResource(R.plurals.area_districts_in, districts.size, districts.size, it.name.localized(locale)) },
                )
                val any = ANY to stringResource(R.string.donors_any_upazila)
                val areas = listOf(any) + f.district?.let(geo::district)?.areas.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
                DropdownField(
                    stringResource(R.string.label_upazila), areas, if (f.district == null) null else f.upazila ?: ANY,
                    { code -> actions.onUpazila(code.takeUnless { it == ANY }) }, enabled = f.district != null, labelAbove = true,
                    placeholder = stringResource(R.string.area_district_first),
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

/**
 * One donor, compact (UI-06: rows were about 148 dp, so 3 fitted): the badge, the name, "Eligible
 * now · 3 donations" and the tags, with "Ask" at the end, so it's clear what a tap does. The area
 * shows only when it isn't the one searched. The whole row is one tap and one TalkBack stop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DonorRow(card: DonorCard, geo: BdGeo?, locale: AppLocale, now: Instant, searchedUpazila: String?, onClick: () -> Unit) {
    // The district is the search's own, so only the upazila or city corporation, and only when the
    // search was the whole district.
    val place = if (searchedUpazila != null && card.area.upazila == searchedUpazila) {
        null
    } else {
        geo?.area(card.area.upazila)?.name?.localized(locale) ?: geo?.placeLabel(card.area.district, card.area.upazila, locale)
    }
    val eligibleText = if (!now.isBefore(card.eligibleFrom)) {
        stringResource(R.string.donors_eligible_now)
    } else {
        val days = EligibilityCalculator.daysUntil(card.eligibleFrom, now).toInt().coerceAtLeast(1)
        pluralStringResource(R.plurals.donors_eligible_in, days, days)
    }
    val activeThisWeek = card.lastActiveAt?.let { Duration.between(it, now) <= Duration.ofDays(7) } == true
    val large = isLargeText()
    Column {
        val details = @Composable { modifier: Modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(card.displayName, style = MaterialTheme.typography.titleMedium)
                // Low 3: with large text the count gets its own line (a line began "· ২ বার রক্তদান").
                val count = pluralStringResource(R.plurals.donors_donations, card.donationCount, card.donationCount)
                Text(
                    if (large) eligibleText + "\n" + count else "$eligibleText · $count",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                place?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (card.phoneVerified || activeThisWeek) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        if (card.phoneVerified) {
                            StatusPill(stringResource(R.string.profile_phone_verified), LLIcons.Verified, StatusTone.Success)
                        }
                        // A tag with its own dot, not Urgent's clock (COLOR-01).
                        if (activeThisWeek) LLTag(stringResource(R.string.donors_active_week), LLIcons.ActiveDot)
                    }
                }
            }
        }
        // Says what a tap does; the row itself is the one TalkBack stop.
        val ask = @Composable { TextButton(onClick = onClick, modifier = Modifier.clearAndSetSemantics { }) { Text(stringResource(R.string.donors_ask)) } }
        val row = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.action_ask_to_donate), onClick = onClick)
            .padding(vertical = Spacing.sm)
        if (large) {
            // With large text "Ask" goes under the details, so it can't squeeze the name letter by letter.
            Column(row) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    BloodGroupBadge(card.bloodGroup, size = 40.dp)
                    details(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { ask() }
            }
        } else {
            Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BloodGroupBadge(card.bloodGroup, size = 40.dp)
                details(Modifier.weight(1f))
                ask()
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** 6.2: pick one of my open requests the donor's group can give to, then ask. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskToDonateSheet(
    sheet: AskSheet,
    place: String?,
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
            // Low 28: whose group and where, so the right request is easy to pick.
            Text(
                listOfNotNull(stringResource(donor.bloodGroup.labelRes), place).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    if (selected == null && requests.size > 1) ChooseHint() // Low 8
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

/** N3 / FLOW-06: asking a donor needs an open request; say so before the search, with the way to post one. */
@Composable
private fun PostFirstCard(onCreateRequest: () -> Unit) {
    LLCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.donors_post_first_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.donors_post_first_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onCreateRequest, modifier = Modifier.padding(top = Spacing.xs).heightIn(min = Spacing.minTouchTarget)) {
                Icon(painterResource(LLIcons.Add), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                ButtonLabel(stringResource(R.string.action_request_blood))
            }
        }
    }
}

/** The "whole district" choice in the upazila dropdown. */
private const val ANY = ""
