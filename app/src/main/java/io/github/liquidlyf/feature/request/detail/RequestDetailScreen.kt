package io.github.liquidlyf.feature.request.detail

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.backend.AlertStatus
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BloodGroupBadge
import io.github.liquidlyf.core.designsystem.components.ButtonLabel
import io.github.liquidlyf.core.designsystem.components.DialogBody
import io.github.liquidlyf.core.designsystem.components.DialogText
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.FirstLineIcon
import io.github.liquidlyf.core.designsystem.components.IconLine
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLNoticeCard
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.MAX_ACTION_WIDTH
import io.github.liquidlyf.core.designsystem.components.NoticeTone
import io.github.liquidlyf.core.designsystem.components.OfflineBanner
import io.github.liquidlyf.core.designsystem.components.RequestStatePill
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.components.SheetDragHandle
import io.github.liquidlyf.core.designsystem.components.StatusPill
import io.github.liquidlyf.core.designsystem.components.UnitDrops
import io.github.liquidlyf.core.designsystem.components.isLargeText
import io.github.liquidlyf.core.designsystem.components.showAppMessage
import io.github.liquidlyf.core.designsystem.components.unitsText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.dateTimeText
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.relativeTimeText
import io.github.liquidlyf.core.designsystem.rememberNow
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.designsystem.timeOrDateTimeText
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.domain.PhoneDisplay
import io.github.liquidlyf.core.domain.StatusTransitions
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.firebase.dto.RequestDoc
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.donors.DonorSearchHandoff
import io.github.liquidlyf.feature.request.create.PlannedAtPicker
import io.github.liquidlyf.feature.request.create.PostNotice
import io.github.liquidlyf.feature.request.create.UnitsStepper
import io.github.liquidlyf.feature.safety.ReportDialog
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

@Composable
fun RequestDetailRoute(
    onBack: () -> Unit,
    onOpenSafetyTips: () -> Unit = {},
    onBecomeDonor: () -> Unit = {},
    /** FLOW-06 part 1: to Find donors, which runs the search handed over. */
    onAskDonors: () -> Unit = {},
    viewModel: RequestDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is DetailEvent.Message -> launch { snackbar.showAppMessage(resources, e.text) }
                DetailEvent.Left -> onBack()
            }
        }
    }
    RequestDetailScreen(state, online, viewModel, onBack, onOpenSafetyTips, onBecomeDonor, onAskDonors)
}

/**
 * The request page as D drew it (N2, OK'd 18 Nov 02:11 and 02:20): one summary card (UI-03), then
 * what the viewer came for (the requester's offers first, FLOW-11; a donor's own offer), then the
 * details with the private card, and the main action pinned at the bottom (FLOW-02).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestDetailScreen(
    state: RequestDetailUiState,
    online: Boolean,
    vm: RequestDetailViewModel,
    onBack: () -> Unit,
    onOpenSafetyTips: () -> Unit,
    onBecomeDonor: () -> Unit,
    onAskDonors: () -> Unit,
) {
    val request = state.request
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<DetailDialog?>(null) }
    // The donor a Decline / Mark donated dialog is about.
    var dialogDonorUid by rememberSaveable { mutableStateOf<String?>(null) }
    val dialogResponse = state.responses.firstOrNull { it.donorUid == dialogDonorUid }
    // The check before offering (FORM-03), opened from the bar's "I can donate".
    var checking by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberSafeIntentLauncher()
    val copy = rememberCopy()
    val shareText = request?.let { shareText(it, state) }
    // FLOW-06 part 1, P2-8: hand this request's groups and area to Find donors, then go there.
    val askDonors = {
        request?.let(DonorSearchHandoff::hand)
        onAskDonors()
    }
    Scaffold(
        topBar = {
            LLTopBar(
                // UI-03: "B+ blood needed", not the generic "Request details".
                title = request?.let { stringResource(R.string.detail_title_needed, stringResource(it.bloodGroup.labelRes)) }
                    ?: stringResource(R.string.title_request_detail),
                onBack = onBack,
                actions = {
                    // P2-6 (D13 = a): the requester's open request has Share in its bar, and Edit and
                    // Close request here in ⋮, so the bar stays one row in Bangla and at large text.
                    val requesterOpen = request != null && state.role == ViewerRole.REQUESTER && request.status == RequestStatus.OPEN
                    if (shareText != null && !requesterOpen) {
                        IconButton(onClick = { launcher.shareText(shareText) }) {
                            Icon(painterResource(LLIcons.Share), contentDescription = stringResource(R.string.action_share))
                        }
                    }
                    if (requesterOpen) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(LLIcons.MoreVert), contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_edit)) },
                                    leadingIcon = { Icon(painterResource(LLIcons.Edit), contentDescription = null) },
                                    enabled = !state.working,
                                    onClick = { menuOpen = false; dialog = DetailDialog.EDIT },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_close_request)) },
                                    leadingIcon = { Icon(painterResource(LLIcons.Cancel), contentDescription = null) },
                                    enabled = !state.working,
                                    onClick = { menuOpen = false; dialog = DetailDialog.CLOSE },
                                )
                            }
                        }
                    }
                    // A donor's extras stay here too.
                    if (request != null && state.role == ViewerRole.DONOR) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(LLIcons.MoreVert), contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_not_for_me)) },
                                    leadingIcon = { Icon(painterResource(LLIcons.VisibilityOff), contentDescription = null) },
                                    onClick = { menuOpen = false; vm.hide() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_report)) },
                                    leadingIcon = { Icon(painterResource(LLIcons.Flag), contentDescription = null) },
                                    onClick = { menuOpen = false; dialog = DetailDialog.REPORT },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_block_person)) },
                                    leadingIcon = { Icon(painterResource(LLIcons.Block), contentDescription = null) },
                                    onClick = { menuOpen = false; dialog = DetailDialog.BLOCK_REQUESTER },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (request != null && !state.loading && state.loadError == null) {
                DetailBar(
                    r = request,
                    state = state,
                    onShare = { shareText?.let(launcher::shareText) },
                    // D1: someone who isn't a donor yet answers the donor questions first.
                    onOffer = { if (state.offerBlock == OfferBlock.NotADonor) onBecomeDonor() else checking = true },
                    onCall = launcher::dial,
                    onCopy = copy,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding).padding(horizontal = screenGutter(), vertical = Spacing.md))
            // STATE-05: couldn't load is not "removed": the shared error state with Retry.
            state.loadError != null -> ErrorState(state.loadError, onRetry = vm::retry, title = stringResource(R.string.detail_load_error), modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()))
            // STATE-02: surely gone, with a way out.
            request == null -> EmptyState(
                icon = LLIcons.Inbox,
                title = stringResource(R.string.detail_missing_title),
                body = stringResource(R.string.detail_missing_body),
                actionLabel = stringResource(R.string.action_back),
                onAction = onBack,
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()),
            )
            else -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                OfflineBanner(online)
                Column(
                    Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = screenGutter(), vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Banners(state)
                    if (state.working && !online) {
                        LLNoticeCard(stringResource(R.string.detail_action_waiting), LLIcons.WifiOff)
                    }
                    Summary(request, state, onOpenMaps = { request.location?.let { launcher.openMaps(it.lat, it.lng, request.hospitalName) } })
                    when (state.role) {
                        ViewerRole.REQUESTER -> {
                            OffersSection(
                                state = state,
                                onCall = launcher::dial,
                                onConfirm = vm::confirm,
                                onDecline = { r -> dialog = DetailDialog.DECLINE; dialogDonorUid = r.donorUid },
                                onDonated = { r -> dialog = DetailDialog.DONATED; dialogDonorUid = r.donorUid },
                                onReportPerson = { r -> dialog = DetailDialog.REPORT_PERSON; dialogDonorUid = r.donorUid },
                                onBlockPerson = { r -> dialog = DetailDialog.BLOCK_DONOR; dialogDonorUid = r.donorUid },
                                onAskDonors = askDonors,
                            )
                            DetailsSection(request, state)
                            PostedBy(request)
                        }
                        ViewerRole.DONOR -> {
                            val status = state.myStatus
                            if (status != null) {
                                YourOfferSection(state, status, request, online)
                            } else {
                                // Why not is in the bar now; a closed request says it here.
                                state.offerBlock?.takeIf { it == OfferBlock.Closed }?.let { IconLine(LLIcons.Info, blockText(it)) }
                            }
                            DetailsSection(request, state)
                            if (status != null && StatusTransitions.donorCanSet(status, ResponseStatus.WITHDRAWN)) {
                                TextButton(
                                    onClick = { dialog = DetailDialog.WITHDRAW },
                                    enabled = !state.working,
                                    modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                                ) { Text(stringResource(R.string.detail_withdraw)) }
                            }
                            PostedBy(request)
                            SafetyBanner(onOpenSafetyTips)
                        }
                    }
                }
            }
        }
    }

    if (checking) {
        OfferSheet(onDismiss = { checking = false }, onOffer = { message -> checking = false; vm.offer(message) })
    }
    when (dialog) {
        DetailDialog.WITHDRAW -> ConfirmDialog(R.string.detail_withdraw_title, R.string.detail_withdraw_body, R.string.detail_withdraw, onDismiss = { dialog = null }) {
            vm.withdraw()
        }
        DetailDialog.DECLINE -> dialogResponse?.let { r ->
            ConfirmDialog(R.string.detail_decline_title, R.string.detail_decline_body, R.string.detail_decline, onDismiss = { dialog = null }) { vm.decline(r) }
        }
        DetailDialog.DONATED -> dialogResponse?.let { r ->
            ConfirmDialog(R.string.detail_donated_title, R.string.detail_donated_body, R.string.detail_mark_donated, onDismiss = { dialog = null }) { vm.markDonated(r) }
        }
        DetailDialog.CLOSE -> CloseDialog(onDismiss = { dialog = null }, onClose = { status, reason -> dialog = null; vm.close(status, reason) })
        DetailDialog.REPORT -> ReportDialog(R.string.detail_report_title, onDismiss = { dialog = null }, onReport = { reason, note -> dialog = null; vm.report(reason, note) })
        DetailDialog.BLOCK_REQUESTER -> ConfirmDialog(R.string.block_title, R.string.block_body, R.string.action_block, onDismiss = { dialog = null }) {
            vm.blockRequester()
        }
        DetailDialog.REPORT_PERSON -> dialogDonorUid?.let { uid ->
            ReportDialog(R.string.report_person_title, onDismiss = { dialog = null }, onReport = { reason, note -> dialog = null; vm.reportPerson(uid, reason, note) })
        }
        DetailDialog.BLOCK_DONOR -> dialogResponse?.let { r ->
            // An open offer is declined too, so they no longer see my number.
            val open = r.status == ResponseStatus.OFFERED || r.status == ResponseStatus.CONFIRMED
            ConfirmDialog(R.string.block_title, if (open) R.string.block_body_offer else R.string.block_body, R.string.action_block, onDismiss = { dialog = null }) {
                vm.blockDonor(r)
            }
        }
        DetailDialog.EDIT -> request?.let { r ->
            EditDialog(r, onDismiss = { dialog = null }, onSave = { notes, units, neededBy -> dialog = null; vm.edit(notes, units, neededBy) })
        }
        null -> Unit
    }
}

private enum class DetailDialog { WITHDRAW, DECLINE, DONATED, CLOSE, REPORT, EDIT, BLOCK_REQUESTER, REPORT_PERSON, BLOCK_DONOR }

/** Copies a number and says so. */
@Composable
private fun rememberCopy(): (RequestContact) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    val copied = stringResource(R.string.detail_copied)
    return { c ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(c.contactName, c.contactPhone)))
            snackbar.showSnackbar(copied)
        }
    }
}

// ---- The bar ----

/**
 * The main action, pinned so it never scrolls away (FLOW-02): "I can donate" for a donor, then
 * "Call <name>" with Copy number once they offered; Share for the requester (Edit and Close request are in ⋮).
 */
@Composable
private fun DetailBar(
    r: BloodRequest,
    state: RequestDetailUiState,
    onShare: () -> Unit,
    onOffer: () -> Unit,
    onCall: (String) -> Unit,
    onCopy: (RequestContact) -> Unit,
) {
    when (state.role) {
        ViewerRole.REQUESTER -> if (r.status == RequestStatus.OPEN) RequesterBar(onShare)
        ViewerRole.DONOR -> {
            val contact = state.contact
            val block = state.offerBlock
            when {
                // FLOW-02: can't offer (wrong group, resting, no group): say why, and offer what still
                // helps, sharing it, instead of a grey button. A closed request needs nothing.
                state.myStatus == null && block != null && block != OfferBlock.NotADonor && block != OfferBlock.Closed ->
                    CantOfferBar(blockText(block), onShare)
                state.myStatus == null && block == OfferBlock.Closed -> Unit
                state.myStatus == null -> LLBottomBar(
                    primaryText = stringResource(R.string.detail_i_can_donate),
                    onPrimary = onOffer,
                    primaryEnabled = true,
                    loading = state.working,
                    primaryIcon = LLIcons.VolunteerActivism,
                )
                contact != null -> LLBottomBar(
                    // P2-7: the short name, as everywhere else on the page; "Copy number" says what it copies.
                    primaryText = stringResource(R.string.detail_call_donor, Validators.displayName(contact.contactName)),
                    onPrimary = { onCall(contact.contactPhone) },
                    primaryIcon = LLIcons.Call,
                    secondaryText = stringResource(R.string.action_copy_number),
                    onSecondary = { onCopy(contact) },
                    secondaryIcon = LLIcons.Copy,
                )
                else -> Unit
            }
        }
    }
}

/** A donor who can't offer: the reason, then Share (they can still help find a donor). */
@Composable
private fun CantOfferBar(reason: String, onShare: () -> Unit) {
    Surface(color = LiquidLyfTheme.extendedColors.card) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = screenGutter(), vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                IconLine(LLIcons.Info, reason)
                FilledTonalButton(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
                    Icon(painterResource(LLIcons.Share), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    ButtonLabel(stringResource(R.string.detail_share_instead))
                }
            }
        }
    }
}

/**
 * The requester's bar: Share alone, full width, always one row (P2-6, D13 = a: with Edit and Close
 * request beside it, it took 2 rows in Bangla and 3 at 200%). Edit and Close request are in ⋮ up top.
 */
@Composable
private fun RequesterBar(onShare: () -> Unit) {
    Surface(color = LiquidLyfTheme.extendedColors.card) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = screenGutter(), vertical = Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                FilledTonalButton(
                    onClick = onShare,
                    modifier = Modifier.widthIn(max = MAX_ACTION_WIDTH).fillMaxWidth().heightIn(min = Spacing.minTouchTarget),
                ) {
                    Icon(painterResource(LLIcons.Share), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    ButtonLabel(stringResource(R.string.action_share))
                }
            }
        }
    }
}

// ---- Summary and details ----

@Composable
private fun Banners(state: RequestDetailUiState) {
    if (state.waitingForNetwork) {
        LLNoticeCard(stringResource(R.string.detail_waiting_network), LLIcons.WifiOff)
    }
    // STATE-03: from the request itself too, so it's still true after a restart or opened later.
    val heldBack = state.role == ViewerRole.REQUESTER && state.request?.let { it.status == RequestStatus.OPEN && it.alertHeldBack } == true
    if (heldBack || state.notice == PostNotice.DAILY_ALERT_LIMIT || state.alertStatus == AlertStatus.DailyLimit) {
        // P2-8: a way forward, not just "donors weren't notified": Share is in the bar, and "Ask donors
        // near this hospital" right under Offers.
        val open = state.role == ViewerRole.REQUESTER && state.request?.status == RequestStatus.OPEN
        LLNoticeCard(
            stringResource(R.string.error_daily_alert_limit) + if (open) " " + stringResource(R.string.detail_limit_way_forward) else "",
            LLIcons.Warning,
            tone = NoticeTone.Warning,
        )
    }
    // 7.8: the notes mention money (the request is flagged for review).
    if (state.request?.flagged == true) {
        LLNoticeCard(stringResource(R.string.detail_flagged_caution), LLIcons.Warning, tone = NoticeTone.Warning)
    }
}

/**
 * Everything that decides "can I help?" in one card (UI-03): the group and units, urgency, when
 * ("Needed by 9:46 AM · in 4 hours"), the bags received as drops, the hospital with Open in Maps
 * (FORM-02, only with a map point), and the area.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(r: BloodRequest, state: RequestDetailUiState, onOpenMaps: () -> Unit) {
    val now by rememberNow()
    val locale = currentAppLocale()
    LLCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                BloodGroupBadge(r.bloodGroup, size = 64.dp, hero = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        unitsText(r.unitsNeeded, stringResource(r.component.labelRes)),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        RequestStatePill(r.status, r.urgency)
                        if (state.role == ViewerRole.DONOR) state.myStatus?.let { StatusPill(it) }
                    }
                }
            }
            Spacer(Modifier.size(Spacing.xxs))
            SummaryLine(LLIcons.Schedule) {
                Text(
                    buildAnnotatedString {
                        when {
                            r.status != RequestStatus.OPEN -> append(dateTimeText(r.neededBy))
                            r.urgency == Urgency.PLANNED -> append(stringResource(R.string.request_needed_on, dateTimeText(r.neededBy)))
                            else -> {
                                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                                    append(stringResource(R.string.detail_needed_by_time, timeOrDateTimeText(r.neededBy, now)))
                                }
                                append(" · ")
                                append(relativeTimeText(r.neededBy, now, long = true))
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                UnitDrops(r.unitsFulfilled, r.unitsNeeded)
                Text(
                    pluralStringResource(R.plurals.detail_units_progress, r.unitsNeeded, r.unitsFulfilled, r.unitsNeeded),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SummaryLine(LLIcons.Hospital) {
                Column {
                    Text(r.hospitalName, style = MaterialTheme.typography.bodyMedium)
                    r.hospitalAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            state.geo?.let { IconLine(LLIcons.Location, it.placeLabel(r.district, r.upazila, locale)) }
            if (r.location != null) {
                OutlinedButton(onClick = onOpenMaps, modifier = Modifier.padding(top = Spacing.xxs).heightIn(min = Spacing.minTouchTarget)) {
                    Icon(painterResource(LLIcons.Map), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.detail_open_maps))
                }
            }
        }
    }
}

/** A small icon on the first line of its text, for lines that may wrap (Low 21). */
@Composable
private fun SummaryLine(icon: Int, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FirstLineIcon(icon, MaterialTheme.typography.bodyMedium)
        content()
    }
}

/**
 * The condition, the note and the private card: contact, number, patient and ward, which only the
 * requester and donors who offered can read (the rules; L10N-01, N2 and D9 for the donor's side).
 */
@Composable
private fun DetailsSection(r: BloodRequest, state: RequestDetailUiState) {
    val contact = state.contact
    if (r.condition == null && r.notes == null && contact == null) return
    SectionHeader(stringResource(R.string.detail_details))
    r.condition?.let { IconLine(LLIcons.Info, stringResource(it.labelRes)) }
    r.notes?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    contact?.let { c ->
        val requester = state.role == ViewerRole.REQUESTER
        LLCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(if (requester) R.string.detail_private_requester else R.string.detail_private_donor),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Icon(painterResource(LLIcons.Visibility), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                val isMe = requester && c.contactPhone == state.myPhone
                IconLine(LLIcons.Profile, stringResource(if (isMe) R.string.detail_contact_you else R.string.detail_contact_line, c.contactName))
                // L10N-01: "+88 01700-000000" (Bangla digits on Bangla screens); Call and Copy use the full number.
                IconLine(LLIcons.Call, PhoneDisplay.format(c.contactPhone, banglaDigits = currentAppLocale() == AppLocale.BN))
                c.patientName?.let { IconLine(LLIcons.Favorite, stringResource(R.string.detail_patient, it)) }
                c.wardOrBed?.let { IconLine(LLIcons.Hospital, stringResource(R.string.detail_ward, it)) }
            }
        }
    }
}

@Composable
private fun PostedBy(r: BloodRequest) {
    val now by rememberNow()
    val posted = r.createdAt?.let { relativeTimeText(it, now) }
    Text(
        if (posted != null) stringResource(R.string.detail_posted_by, posted, shownName(r.requesterDisplayName)) else stringResource(R.string.detail_posted_by_unsent, shownName(r.requesterDisplayName)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SafetyBanner(onOpenSafetyTips: () -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(LLIcons.Shield), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.detail_safety), style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onOpenSafetyTips, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.action_safety_tips))
        }
    }
}

// ---- Donor ----

/** "Your offer" (FLOW-07, D9): the status, the message, and who can see it now. The contact is in the private card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YourOfferSection(state: RequestDetailUiState, status: ResponseStatus, r: BloodRequest, online: Boolean) {
    SectionHeader(stringResource(R.string.detail_your_offer))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        StatusPill(status)
        state.myMessage?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically)) }
    }
    val line = when (status) {
        ResponseStatus.DONATED -> stringResource(R.string.detail_donated_thanks)
        ResponseStatus.DECLINED -> stringResource(R.string.detail_declined_info)
        // Offline, the offer is on this phone only (D's re-audit): say when it goes.
        ResponseStatus.OFFERED -> stringResource(
            if (online) R.string.detail_offer_seen else R.string.detail_offer_waiting,
            shownName(r.requesterDisplayName),
        )
        ResponseStatus.CONFIRMED -> stringResource(R.string.detail_offer_seen, shownName(r.requesterDisplayName))
        else -> null
    }
    line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** The check before offering (FORM-03), fully open and scrollable so the offer button is reachable with large text or in landscape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfferSheet(onDismiss: () -> Unit, onOffer: (String) -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenGutter())
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.detail_check_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            // FORM-03: a lead-in and plain bullets; with ✓ icons the list looked already ticked.
            Text(stringResource(R.string.detail_check_lead), style = MaterialTheme.typography.bodyMedium)
            listOf(
                R.string.detail_check_well, R.string.detail_check_interval, R.string.detail_check_weight,
                R.string.detail_check_illness, R.string.detail_check_meal,
            ).forEach { IconLine(LLIcons.Bullet, stringResource(it)) }
            Text(stringResource(R.string.detail_check_guidance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LLTextField(
                value = message,
                onValueChange = { message = it.take(Validators.OFFER_MESSAGE_MAX) },
                label = stringResource(R.string.detail_offer_message),
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            // 8.3: the donor's part of the medical disclaimer.
            IconLine(LLIcons.Hospital, stringResource(R.string.detail_offer_disclaimer))
            Text(stringResource(R.string.detail_offer_consent), style = MaterialTheme.typography.bodyMedium)
            LLPrimaryButton(
                text = stringResource(R.string.detail_offer_confirm),
                onClick = { onOffer(message) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun blockText(block: OfferBlock): String = when (block) {
    OfferBlock.Closed -> stringResource(R.string.detail_block_closed)
    OfferBlock.NoBloodGroup -> stringResource(R.string.detail_block_no_group)
    OfferBlock.Incompatible -> stringResource(R.string.detail_block_incompatible)
    OfferBlock.NotADonor -> stringResource(R.string.home_become_donor_body)
    is OfferBlock.NotEligible -> when {
        IneligibleReason.REST_PERIOD in block.reasons -> {
            val days = EligibilityCalculator.daysUntil(block.eligibleFrom, Instant.now()).toInt().coerceAtLeast(1)
            pluralStringResource(R.plurals.detail_block_rest, days, days)
        }
        IneligibleReason.LOW_WEIGHT in block.reasons -> stringResource(R.string.detail_block_weight)
        else -> stringResource(R.string.detail_block_age)
    }
}

// ---- Requester ----

/** FLOW-11: the offers right after the summary, the reason the requester opens the page. */
@Composable
private fun OffersSection(
    state: RequestDetailUiState,
    onCall: (String) -> Unit,
    onConfirm: (DonorResponse) -> Unit,
    onDecline: (DonorResponse) -> Unit,
    onDonated: (DonorResponse) -> Unit,
    onReportPerson: (DonorResponse) -> Unit,
    onBlockPerson: (DonorResponse) -> Unit,
    onAskDonors: () -> Unit,
) {
    val r = state.request ?: return
    SectionHeader(stringResource(R.string.detail_offers))
    if (state.responses.isEmpty()) AlertLine(r, state)
    state.responses.forEach { response ->
        OfferCard(response, enabled = !state.working, onCall, onConfirm, onDecline, onDonated, onReportPerson, onBlockPerson)
    }
    // FLOW-06 part 1: don't only wait for offers; ask listed donors near the hospital directly.
    if (r.status == RequestStatus.OPEN) {
        OutlinedButton(onClick = onAskDonors, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
            Icon(painterResource(LLIcons.Donors), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            ButtonLabel(stringResource(R.string.detail_ask_donors))
        }
    }
}

/**
 * Where the first alert is (STATE-03, PERF-01): "Alerting B+ donors near you…" right after
 * posting, then "We alerted B+ donors in <area>". It reads the request's own fan-out fields, which
 * the Worker writes, so it stays true after a restart or when the page opens later (D's re-audit).
 */
@Composable
private fun AlertLine(r: BloodRequest, state: RequestDetailUiState) {
    val now by rememberNow(periodMillis = 15_000)
    val group = stringResource(r.bloodGroup.labelRes)
    val locale = currentAppLocale()
    val alert = state.alertStatus
    val tier = maxOf(r.fanoutTier, (alert as? AlertStatus.Sent)?.tier ?: 0)
    val heldBack = r.alertHeldBack || alert == AlertStatus.DailyLimit
    val fresh = r.createdAt?.let { Duration.between(it, now) < ALERT_WAIT } ?: false
    val sending = r.status == RequestStatus.OPEN && !state.waitingForNetwork && tier == 0 && !heldBack &&
        alert != AlertStatus.Failed && (alert == AlertStatus.Sending || fresh)
    val text = when {
        r.status != RequestStatus.OPEN -> stringResource(R.string.detail_no_offers)
        state.waitingForNetwork -> stringResource(R.string.detail_no_offers_unsent)
        tier >= 1 -> {
            // Tiers 1–2 are the request's upazila, 3 and up its district (backend/worker/src/tiers.ts).
            val place = state.geo?.placeLabel(r.district, r.upazila.takeIf { tier <= 2 }, locale)
            if (place != null) stringResource(R.string.detail_alerted_in, group, place) else stringResource(R.string.detail_no_offers_open)
        }
        heldBack -> stringResource(R.string.detail_no_offers_yet) // the notice at the top says why
        sending -> stringResource(R.string.detail_alerting, group)
        // The app couldn't send it: the Worker's round every 5 minutes does.
        else -> stringResource(R.string.detail_alert_soon)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (sending) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                painterResource(if (tier >= 1) LLIcons.Notifications else LLIcons.Schedule),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** How long a new request may take to reach its first tier before the line says "within a few minutes". */
private val ALERT_WAIT: Duration = Duration.ofMinutes(1)

/**
 * One offer as a card (FLOW-07): name, message and status, then Call (with its word) and the next
 * step, filled. Decline is in the card's ⋮ menu, away from Confirm. The buttons wrap at large text
 * instead of squeezing a word letter by letter (A11Y-03).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferCard(
    response: DonorResponse,
    enabled: Boolean,
    onCall: (String) -> Unit,
    onConfirm: (DonorResponse) -> Unit,
    onDecline: (DonorResponse) -> Unit,
    onDonated: (DonorResponse) -> Unit,
    onReportPerson: (DonorResponse) -> Unit,
    onBlockPerson: (DonorResponse) -> Unit,
) {
    val next = StatusTransitions.requesterNext(response.status)
    var menuOpen by remember { mutableStateOf(false) }
    val name = shownName(response.donorDisplayName)
    LLCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Spacing.md, top = Spacing.sm, end = Spacing.xs, bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BloodGroupBadge(response.donorBloodGroup, modifier = Modifier.padding(top = Spacing.xxs), size = 40.dp)
                Column(Modifier.weight(1f).padding(top = Spacing.xxs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    response.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    StatusPill(response.status)
                }
                // 7.1/7.2: decline, report or block this person.
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = enabled, modifier = Modifier.size(Spacing.minTouchTarget)) {
                        Icon(painterResource(LLIcons.MoreVert), contentDescription = stringResource(R.string.detail_more_about, name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (ResponseStatus.DECLINED in next) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_decline)) },
                                leadingIcon = { Icon(painterResource(LLIcons.Cancel), contentDescription = null) },
                                onClick = { menuOpen = false; onDecline(response) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_report_person)) },
                            leadingIcon = { Icon(painterResource(LLIcons.Flag), contentDescription = null) },
                            onClick = { menuOpen = false; onReportPerson(response) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_block_person)) },
                            leadingIcon = { Icon(painterResource(LLIcons.Block), contentDescription = null) },
                            onClick = { menuOpen = false; onBlockPerson(response) },
                        )
                    }
                }
            }
            val phone = response.donorPhone?.takeIf { response.status != ResponseStatus.WITHDRAWN }
            val step: Pair<Int, () -> Unit>? = when {
                ResponseStatus.CONFIRMED in next -> R.string.detail_confirm to { onConfirm(response) }
                ResponseStatus.DONATED in next -> R.string.detail_mark_donated to { onDonated(response) }
                else -> null
            }
            if (phone != null || step != null) {
                FlowRow(
                    Modifier.padding(end = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    phone?.let {
                        val callName = stringResource(R.string.detail_call_donor, name)
                        FilledTonalButton(
                            onClick = { onCall(it) },
                            modifier = Modifier.heightIn(min = Spacing.minTouchTarget).semantics { contentDescription = callName },
                        ) {
                            Icon(painterResource(LLIcons.Call), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.action_call))
                        }
                    }
                    step?.let { (label, action) ->
                        Button(onClick = action, enabled = enabled, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) { Text(stringResource(label)) }
                    }
                }
            }
        }
    }
}

// ---- Dialogs ----

@Composable
private fun ConfirmDialog(title: Int, body: Int, confirm: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { DialogText(stringResource(body)) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CloseDialog(onDismiss: () -> Unit, onClose: (RequestStatus, String?) -> Unit) {
    // COPY-04: nothing preselected; the person says which it is.
    var choice by rememberSaveable { mutableStateOf<RequestStatus?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_close_title)) },
        text = {
            DialogBody(Modifier.selectableGroup(), spacing = Spacing.xs) {
                Text(stringResource(R.string.detail_close_body), style = MaterialTheme.typography.bodyMedium)
                RadioRow(stringResource(R.string.detail_close_found), choice == RequestStatus.FULFILLED) { choice = RequestStatus.FULFILLED }
                RadioRow(stringResource(R.string.detail_close_not_needed), choice == RequestStatus.CANCELLED) { choice = RequestStatus.CANCELLED }
                LLTextField(
                    value = reason,
                    onValueChange = { reason = it.take(Validators.CLOSED_REASON_MAX) },
                    label = stringResource(R.string.detail_close_reason),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { choice?.let { onClose(it, reason) } }, enabled = choice != null) { Text(stringResource(R.string.detail_close_request)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}


@Composable
private fun EditDialog(r: BloodRequest, onDismiss: () -> Unit, onSave: (String?, Int?, Instant?) -> Unit) {
    var notes by rememberSaveable { mutableStateOf(r.notes.orEmpty()) }
    var units by rememberSaveable { mutableIntStateOf(r.unitsNeeded) }
    var neededByMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    val minUnits = maxOf(Validators.UNITS_MIN, r.unitsFulfilled + 1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_edit)) },
        text = {
            DialogBody {
                UnitsStepper(units, onChange = { units = it }, min = minUnits)
                Text(stringResource(R.string.detail_edit_needed_by), style = MaterialTheme.typography.labelLarge)
                PlannedAtPicker(neededByMillis ?: r.neededBy.toEpochMilli(), showError = false) { neededByMillis = it }
                LLTextField(
                    value = notes,
                    onValueChange = { notes = it.take(Validators.NOTES_MAX) },
                    label = stringResource(R.string.create_notes),
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    notes.takeIf { it.trim() != r.notes.orEmpty() },
                    units.takeIf { it != r.unitsNeeded },
                    neededByMillis?.let(Instant::ofEpochMilli),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = Spacing.xs))
    }
}

/** WhatsApp/Messenger text, in the app's language: how requests spread in Bangladesh today. */
@Composable
private fun shareText(r: BloodRequest, state: RequestDetailUiState): String {
    val locale = currentAppLocale()
    val place = state.geo?.placeLabel(r.district, r.upazila, locale) ?: r.district
    return stringResource(
        R.string.detail_share_text,
        stringResource(r.bloodGroup.labelRes),
        pluralStringResource(R.plurals.request_units_plain, r.unitsNeeded, r.unitsNeeded),
        stringResource(r.component.labelRes),
        r.hospitalName,
        place,
        dateTimeText(r.neededBy),
    )
}

/** A deleted account's name is stored as the English "Deleted user" (contract 16): show it in the app's language. */
@Composable
private fun shownName(name: String): String =
    if (name == RequestDoc.DELETED_NAME) stringResource(R.string.deleted_user) else name
