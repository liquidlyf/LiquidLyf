package io.github.liquidlyf.feature.request.detail

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BloodGroupBadge
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.IconLine
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.OfflineBanner
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.components.StatusPill
import io.github.liquidlyf.core.designsystem.components.UrgencyBadge
import io.github.liquidlyf.core.designsystem.components.neededByText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.dateTimeText
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.rememberNow
import io.github.liquidlyf.core.designsystem.relativeTimeText
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.domain.StatusTransitions
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.request.create.PlannedAtPicker
import io.github.liquidlyf.feature.request.create.PostNotice
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun RequestDetailRoute(onBack: () -> Unit, viewModel: RequestDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is DetailEvent.Message -> launch { snackbar.showSnackbar(resources.getString(e.text)) }
                DetailEvent.Left -> onBack()
            }
        }
    }
    RequestDetailScreen(state, online, viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestDetailScreen(state: RequestDetailUiState, online: Boolean, vm: RequestDetailViewModel, onBack: () -> Unit) {
    val request = state.request
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<DetailDialog?>(null) }
    // The donor a Decline / Mark donated dialog is about.
    var dialogDonorUid by rememberSaveable { mutableStateOf<String?>(null) }
    val dialogResponse = state.responses.firstOrNull { it.donorUid == dialogDonorUid }
    val launcher = rememberSafeIntentLauncher()
    val shareText = request?.let { shareText(it, state) }
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.title_request_detail),
                onBack = onBack,
                actions = {
                    if (shareText != null) {
                        IconButton(onClick = { launcher.shareText(shareText) }) {
                            Icon(painterResource(LLIcons.Share), contentDescription = stringResource(R.string.action_share))
                        }
                    }
                    if (request != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(LLIcons.MoreVert), contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (state.role == ViewerRole.REQUESTER) {
                                    if (request.status == RequestStatus.OPEN) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.detail_edit)) },
                                            leadingIcon = { Icon(painterResource(LLIcons.Edit), contentDescription = null) },
                                            onClick = { menuOpen = false; dialog = DetailDialog.EDIT },
                                        )
                                    }
                                } else {
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
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            request == null -> EmptyState(
                icon = LLIcons.Inbox,
                title = stringResource(R.string.detail_missing_title),
                body = stringResource(R.string.detail_missing_body),
                modifier = Modifier.padding(padding),
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
                        NoticeCard(LLIcons.WifiOff, stringResource(R.string.detail_action_waiting))
                    }
                    Header(request)
                    WhereCard(request, state, onOpenMaps = { r -> r.location?.let { launcher.openMaps(it.lat, it.lng, r.hospitalName) } })
                    SafetyBanner()
                    when (state.role) {
                        ViewerRole.DONOR -> DonorSection(state, vm, onCall = launcher::dial, onWithdraw = { dialog = DetailDialog.WITHDRAW })
                        ViewerRole.REQUESTER -> RequesterSection(
                            state = state,
                            onCall = launcher::dial,
                            onConfirm = vm::confirm,
                            onDecline = { r -> dialog = DetailDialog.DECLINE; dialogDonorUid = r.donorUid },
                            onDonated = { r -> dialog = DetailDialog.DONATED; dialogDonorUid = r.donorUid },
                            onClose = { dialog = DetailDialog.CLOSE },
                        )
                    }
                }
            }
        }
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
        DetailDialog.REPORT -> ReportDialog(onDismiss = { dialog = null }, onReport = { reason, note -> dialog = null; vm.report(reason, note) })
        DetailDialog.EDIT -> request?.let { r ->
            EditDialog(r, onDismiss = { dialog = null }, onSave = { notes, units, neededBy -> dialog = null; vm.edit(notes, units, neededBy) })
        }
        null -> Unit
    }
}

private enum class DetailDialog { WITHDRAW, DECLINE, DONATED, CLOSE, REPORT, EDIT }

// ---- Header and details ----

@Composable
private fun Banners(state: RequestDetailUiState) {
    if (state.waitingForNetwork) {
        NoticeCard(LLIcons.WifiOff, stringResource(R.string.detail_waiting_network))
    }
    if (state.notice == PostNotice.DAILY_ALERT_LIMIT) {
        NoticeCard(LLIcons.Warning, stringResource(R.string.error_daily_alert_limit))
    }
}

@Composable
private fun NoticeCard(icon: Int, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Header(r: BloodRequest) {
    val now by rememberNow()
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        BloodGroupBadge(r.bloodGroup, size = 72.dp, hero = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(stringResource(r.component.labelRes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                pluralStringResource(R.plurals.detail_units_progress, r.unitsNeeded, r.unitsFulfilled, r.unitsNeeded),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { if (r.unitsNeeded == 0) 0f else r.unitsFulfilled.toFloat() / r.unitsNeeded },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusPill(r.status)
                if (r.status == RequestStatus.OPEN) UrgencyBadge(r.urgency)
            }
        }
    }
    IconLine(LLIcons.Schedule, "${neededByText(r.neededBy, now)} · ${dateTimeText(r.neededBy)}")
    r.condition?.let { IconLine(LLIcons.Info, stringResource(it.labelRes)) }
    r.notes?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    val posted = r.createdAt?.let { relativeTimeText(it, now) }
    Text(
        if (posted != null) stringResource(R.string.detail_posted_by, posted, r.requesterDisplayName) else stringResource(R.string.detail_posted_by_unsent, r.requesterDisplayName),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WhereCard(r: BloodRequest, state: RequestDetailUiState, onOpenMaps: (BloodRequest) -> Unit) {
    val locale = currentAppLocale()
    Card {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            IconLine(LLIcons.Hospital, r.hospitalName)
            r.hospitalAddress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            state.geo?.let { IconLine(LLIcons.Location, it.placeLabel(r.district, r.upazila, locale)) }
            state.contact?.wardOrBed?.let { Text(stringResource(R.string.detail_ward, it), style = MaterialTheme.typography.bodyMedium) }
            if (r.location != null) {
                OutlinedButton(onClick = { onOpenMaps(r) }, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                    Icon(painterResource(LLIcons.Map), contentDescription = null)
                    Text(stringResource(R.string.detail_open_maps), modifier = Modifier.padding(start = Spacing.xs))
                }
            }
        }
    }
}

@Composable
private fun SafetyBanner() {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(LLIcons.Shield), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.detail_safety), style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- Donor ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DonorSection(state: RequestDetailUiState, vm: RequestDetailViewModel, onCall: (String) -> Unit, onWithdraw: () -> Unit) {
    var checking by rememberSaveable { mutableStateOf(false) }
    val status = state.myStatus
    if (status == null) {
        val block = state.offerBlock
        LLPrimaryButton(
            text = stringResource(R.string.detail_i_can_donate),
            onClick = { checking = true },
            enabled = block == null && !state.working,
            loading = state.working,
            icon = LLIcons.VolunteerActivism,
            modifier = Modifier.fillMaxWidth(),
        )
        block?.let { Text(blockText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.detail_your_offer), style = MaterialTheme.typography.titleMedium)
            StatusPill(status)
        }
        when (status) {
            ResponseStatus.DONATED -> Text(stringResource(R.string.detail_donated_thanks), style = MaterialTheme.typography.bodyMedium)
            ResponseStatus.DECLINED -> Text(stringResource(R.string.detail_declined_info), style = MaterialTheme.typography.bodyMedium)
            else -> Unit
        }
        state.contact?.let { ContactCard(it, onCall) }
        if (StatusTransitions.donorCanSet(status, ResponseStatus.WITHDRAWN)) {
            OutlinedButton(onClick = onWithdraw, enabled = !state.working, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                Text(stringResource(R.string.detail_withdraw))
            }
        }
    }
    if (checking) {
        var message by rememberSaveable { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { checking = false }) {
            Column(
                Modifier.padding(horizontal = screenGutter()).padding(bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(stringResource(R.string.detail_check_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                listOf(
                    R.string.detail_check_well, R.string.detail_check_interval, R.string.detail_check_weight,
                    R.string.detail_check_illness, R.string.detail_check_meal,
                ).forEach { IconLine(LLIcons.Check, stringResource(it)) }
                Text(stringResource(R.string.detail_check_guidance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LLTextField(
                    value = message,
                    onValueChange = { message = it.take(Validators.OFFER_MESSAGE_MAX) },
                    label = stringResource(R.string.detail_offer_message),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text(stringResource(R.string.detail_offer_consent), style = MaterialTheme.typography.bodyMedium)
                LLPrimaryButton(
                    text = stringResource(R.string.detail_offer_confirm),
                    onClick = { checking = false; vm.offer(message) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun blockText(block: OfferBlock): String = when (block) {
    OfferBlock.Closed -> stringResource(R.string.detail_block_closed)
    OfferBlock.NoBloodGroup -> stringResource(R.string.detail_block_no_group)
    OfferBlock.Incompatible -> stringResource(R.string.detail_block_incompatible)
    is OfferBlock.NotEligible -> when {
        IneligibleReason.REST_PERIOD in block.reasons -> {
            val days = EligibilityCalculator.daysUntil(block.eligibleFrom, Instant.now()).toInt().coerceAtLeast(1)
            pluralStringResource(R.plurals.detail_block_rest, days, days)
        }
        IneligibleReason.LOW_WEIGHT in block.reasons -> stringResource(R.string.detail_block_weight)
        else -> stringResource(R.string.detail_block_age)
    }
}

@Composable
private fun ContactCard(c: RequestContact, onCall: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    val copied = stringResource(R.string.detail_copied)
    Card {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(R.string.detail_contact), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(c.contactName, style = MaterialTheme.typography.bodyLarge)
            Text(c.contactPhone, style = MaterialTheme.typography.bodyLarge)
            c.patientName?.let { Text(stringResource(R.string.detail_patient, it), style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                LLPrimaryButton(text = stringResource(R.string.action_call), onClick = { onCall(c.contactPhone) }, icon = LLIcons.Call)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(c.contactName, c.contactPhone)))
                            snackbar.showSnackbar(copied)
                        }
                    },
                    modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                ) {
                    Icon(painterResource(LLIcons.Copy), contentDescription = null)
                    Text(stringResource(R.string.action_copy), modifier = Modifier.padding(start = Spacing.xs))
                }
            }
        }
    }
}

// ---- Requester ----

@Composable
private fun RequesterSection(
    state: RequestDetailUiState,
    onCall: (String) -> Unit,
    onConfirm: (DonorResponse) -> Unit,
    onDecline: (DonorResponse) -> Unit,
    onDonated: (DonorResponse) -> Unit,
    onClose: () -> Unit,
) {
    val r = state.request ?: return
    SectionHeader(pluralStringResource(R.plurals.detail_offers_title, state.responses.size, state.responses.size))
    if (state.responses.isEmpty()) {
        Text(
            stringResource(if (r.status == RequestStatus.OPEN) R.string.detail_no_offers_open else R.string.detail_no_offers),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.responses.forEachIndexed { i, response ->
        if (i > 0) HorizontalDivider()
        ResponderRow(response, enabled = !state.working, onCall, onConfirm, onDecline, onDonated)
    }
    if (r.status == RequestStatus.OPEN) {
        OutlinedButton(onClick = onClose, enabled = !state.working, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
            Text(stringResource(R.string.detail_close_request))
        }
    }
}

@Composable
private fun ResponderRow(
    response: DonorResponse,
    enabled: Boolean,
    onCall: (String) -> Unit,
    onConfirm: (DonorResponse) -> Unit,
    onDecline: (DonorResponse) -> Unit,
    onDonated: (DonorResponse) -> Unit,
) {
    val next = StatusTransitions.requesterNext(response.status)
    Column(Modifier.padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BloodGroupBadge(response.donorBloodGroup, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(response.donorDisplayName, style = MaterialTheme.typography.titleMedium)
                response.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            StatusPill(response.status)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            response.donorPhone?.takeIf { response.status != ResponseStatus.WITHDRAWN }?.let { phone ->
                IconButton(onClick = { onCall(phone) }) {
                    Icon(painterResource(LLIcons.Call), contentDescription = stringResource(R.string.detail_call_donor, response.donorDisplayName))
                }
            }
            if (ResponseStatus.CONFIRMED in next) {
                TextButton(onClick = { onConfirm(response) }, enabled = enabled) { Text(stringResource(R.string.detail_confirm)) }
            }
            if (ResponseStatus.DONATED in next) {
                TextButton(onClick = { onDonated(response) }, enabled = enabled) { Text(stringResource(R.string.detail_mark_donated)) }
            }
            if (ResponseStatus.DECLINED in next) {
                TextButton(onClick = { onDecline(response) }, enabled = enabled) { Text(stringResource(R.string.detail_decline)) }
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
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CloseDialog(onDismiss: () -> Unit, onClose: (RequestStatus, String?) -> Unit) {
    var choice by rememberSaveable { mutableStateOf(RequestStatus.FULFILLED) }
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_close_title)) },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                RadioRow(stringResource(R.string.detail_close_found), choice == RequestStatus.FULFILLED) { choice = RequestStatus.FULFILLED }
                RadioRow(stringResource(R.string.detail_close_not_needed), choice == RequestStatus.CANCELLED) { choice = RequestStatus.CANCELLED }
                LLTextField(
                    value = reason,
                    onValueChange = { reason = it.take(Validators.CLOSED_REASON_MAX) },
                    label = stringResource(R.string.detail_close_reason),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onClose(choice, reason) }) { Text(stringResource(R.string.detail_close_request)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onReport: (ReportReason, String?) -> Unit) {
    var choice by rememberSaveable { mutableStateOf<ReportReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_report_title)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                ReportReason.entries.forEach { reason ->
                    RadioRow(stringResource(reason.labelRes), choice == reason) { choice = reason }
                }
                LLTextField(
                    value = note,
                    onValueChange = { note = it.take(Validators.REPORT_NOTE_MAX) },
                    label = stringResource(R.string.detail_report_note),
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { choice?.let { onReport(it, note) } }, enabled = choice != null) { Text(stringResource(R.string.detail_report)) }
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.create_units), modifier = Modifier.weight(1f))
                    IconButton(onClick = { units-- }, enabled = units > minUnits) {
                        Icon(painterResource(LLIcons.Remove), contentDescription = stringResource(R.string.create_units_less))
                    }
                    Text(units.toString(), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { units++ }, enabled = units < Validators.UNITS_MAX) {
                        Icon(painterResource(LLIcons.Add), contentDescription = stringResource(R.string.create_units_more))
                    }
                }
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
