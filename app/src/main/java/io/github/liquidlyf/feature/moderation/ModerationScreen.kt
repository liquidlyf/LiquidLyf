package io.github.liquidlyf.feature.moderation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.moderation.RequestAction
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.StatusTone
import io.github.liquidlyf.core.designsystem.components.DialogBody
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLCard
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.StatusPill
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.relativeTimeText
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.ReportTarget
import io.github.liquidlyf.core.model.RequestStatus
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun ModerationRoute(onBack: () -> Unit, onOpenRequest: (String) -> Unit, viewModel: ModerationViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e -> if (e is ModerationEvent.Message) launch { snackbar.showSnackbar(resources.getString(e.text)) } }
    }
    ModerationScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::load,
        onOpenRequest = onOpenRequest,
        onDo = { item, kind, note ->
            when (kind) {
                Kind.DISMISS -> viewModel.dismiss(item, note)
                Kind.HANDLED -> viewModel.markHandled(item, note)
                Kind.HIDE -> viewModel.act(item, RequestAction.HIDE, note)
                Kind.RESTORE -> viewModel.act(item, RequestAction.RESTORE, note)
                Kind.CANCEL -> viewModel.act(item, RequestAction.CANCEL, note)
            }
        },
    )
}

/** The actions a card offers; each asks first, with an optional note for the admin log. */
internal enum class Kind(@StringRes val label: Int, @StringRes val confirm: Int) {
    DISMISS(R.string.action_dismiss, R.string.moderation_confirm_dismiss),
    HIDE(R.string.action_hide, R.string.moderation_confirm_hide),
    RESTORE(R.string.action_restore, R.string.moderation_confirm_restore),
    CANCEL(R.string.action_cancel_request, R.string.moderation_confirm_cancel),
    HANDLED(R.string.action_mark_handled, R.string.moderation_confirm_handled),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModerationScreen(
    state: ModerationUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onDo: (ModerationItem, Kind, String?) -> Unit,
    now: Instant = Instant.now(),
) {
    var pending by remember { mutableStateOf<Pair<ModerationItem, Kind>?>(null) }
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.title_moderation),
                onBack = onBack,
                actions = {
                    if (state.admin) {
                        IconButton(onClick = onRefresh) { Icon(painterResource(LLIcons.Refresh), contentDescription = stringResource(R.string.action_refresh)) }
                    }
                },
            )
        },
    ) { padding ->
        val items = state.items
        when {
            !state.admin -> EmptyState(LLIcons.Shield, stringResource(R.string.title_moderation), stringResource(R.string.moderation_admins_only), Modifier.padding(padding).verticalScroll(rememberScrollState()))
            state.error != null && items == null -> ErrorState(state.error, onRetry = onRefresh, modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()), title = stringResource(R.string.moderation_load_error))
            items == null -> LoadingState(Modifier.padding(padding).padding(horizontal = screenGutter(), vertical = Spacing.md))
            items.isEmpty() -> EmptyState(
                LLIcons.CheckCircle, stringResource(R.string.moderation_empty_title), stringResource(R.string.moderation_empty_body), Modifier.padding(padding).verticalScroll(rememberScrollState()),
            )
            else -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                LazyColumn(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = screenGutter(), vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(items, key = { it.report.id }) { item ->
                        ReportCard(item, now, enabled = !state.working, onOpenRequest = onOpenRequest, onPick = { kind -> pending = item to kind })
                    }
                }
            }
        }
    }
    pending?.let { (item, kind) ->
        var note by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(kind.label)) },
            text = {
                DialogBody {
                    Text(stringResource(kind.confirm))
                    LLTextField(value = note, onValueChange = { note = it.take(NOTE_MAX) }, label = stringResource(R.string.moderation_note), singleLine = false)
                }
            },
            confirmButton = { TextButton(onClick = { pending = null; onDo(item, kind, note) }) { Text(stringResource(kind.label)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(item: ModerationItem, now: Instant, enabled: Boolean, onOpenRequest: (String) -> Unit, onPick: (Kind) -> Unit) {
    val r = item.report
    LLCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                listOfNotNull(stringResource(r.reason.labelRes), r.createdAt?.let { relativeTimeText(it, now) }).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
            )
            when (r.targetType) {
                ReportTarget.REQUEST -> {
                    val req = item.request
                    Text(
                        if (req == null) {
                            stringResource(R.string.moderation_target_request_gone)
                        } else {
                            stringResource(R.string.moderation_target_request, stringResource(req.bloodGroup.labelRes), req.hospitalName)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (req != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            StatusPill(req.status)
                            Text(pluralStringResource(R.plurals.moderation_report_count, req.reportCount, req.reportCount), style = MaterialTheme.typography.labelMedium)
                            if (req.flagged) Pill(R.string.moderation_flagged)
                            if (item.reporterOffered) Pill(R.string.moderation_reporter_offered)
                        }
                    }
                }
                ReportTarget.USER -> Text(
                    stringResource(R.string.moderation_target_person, item.personName ?: stringResource(R.string.moderation_unknown_person)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            r.note?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
            if (r.targetType == ReportTarget.USER) {
                Text(stringResource(R.string.moderation_ban_hint), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val req = item.request
                if (req != null) Button(R.string.action_open_request, enabled = true) { onOpenRequest(req.id) }
                // A hidden request is decided with Restore or Cancel, so it can't be left hidden by a dismiss.
                if (req?.status != RequestStatus.REMOVED) Button(Kind.DISMISS.label, enabled) { onPick(Kind.DISMISS) }
                if (r.targetType == ReportTarget.USER) Button(Kind.HANDLED.label, enabled) { onPick(Kind.HANDLED) }
                if (req != null) {
                    if (RequestAction.HIDE.allowedFrom(req.status)) Button(Kind.HIDE.label, enabled) { onPick(Kind.HIDE) }
                    if (RequestAction.RESTORE.allowedFrom(req.status)) Button(Kind.RESTORE.label, enabled) { onPick(Kind.RESTORE) }
                    if (RequestAction.CANCEL.allowedFrom(req.status)) Button(Kind.CANCEL.label, enabled) { onPick(Kind.CANCEL) }
                }
            }
        }
    }
}

@Composable
private fun Button(@StringRes label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) { Text(stringResource(label)) }
}

@Composable
private fun Pill(@StringRes label: Int) {
    StatusPill(stringResource(label), LLIcons.Warning, StatusTone.Urgent)
}

/** The rules' limit for an adminLog note. */
private const val NOTE_MAX = 500
