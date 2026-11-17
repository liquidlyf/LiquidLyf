package io.github.liquidlyf.feature.profilesetup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BloodGroupPicker
import io.github.liquidlyf.core.designsystem.components.ChoiceRow
import io.github.liquidlyf.core.designsystem.components.EdgeTextButton
import io.github.liquidlyf.core.designsystem.components.ErrorAnchors
import io.github.liquidlyf.core.designsystem.components.FieldError
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLSwitchRow
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.ShowFirstErrorOnAttempt
import io.github.liquidlyf.core.designsystem.components.errorAnchor
import io.github.liquidlyf.core.designsystem.components.rememberErrorAnchors
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.localDateText
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.feature.auth.FormError
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun BecomeDonorRoute(onDone: () -> Unit, viewModel: BecomeDonorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e -> if (e == BecomeDonorEvent.Done) onDone() }
    }
    // Back steps inside; on the first step it leaves, as usual.
    BackHandler(enabled = state.step > 0 && !state.saving) { viewModel.back() }
    BecomeDonorScreen(
        state = state,
        onEdit = viewModel::edit,
        onNext = viewModel::next,
        onBack = { if (!viewModel.back()) onDone() },
        onSkipBloodGroup = viewModel::skipBloodGroup,
    )
}

/** The donor questions, 3 short steps (D1, N3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BecomeDonorScreen(
    state: BecomeDonorUiState,
    onEdit: (SetupDraft.() -> SetupDraft) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkipBloodGroup: () -> Unit = {},
) {
    Scaffold(
        topBar = { LLTopBar(title = stringResource(R.string.action_become_donor), onBack = if (!state.saving) onBack else null) },
        bottomBar = {
            LLBottomBar(
                primaryText = stringResource(if (state.isLastStep) R.string.action_finish else R.string.action_next),
                onPrimary = onNext,
                loading = state.saving,
                loadingText = stringResource(R.string.action_saving),
                secondaryText = if (state.step > 0) stringResource(R.string.action_back) else null,
                onSecondary = onBack,
            )
        },
    ) { padding ->
        val anchors = rememberErrorAnchors<SetupField>()
        // FLOW-04: a failed Next brings the first error into view.
        ShowFirstErrorOnAttempt(anchors, state.errorAttempt, state.errors, DONOR_ORDER)
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter(), vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    stringResource(R.string.setup_step, state.step + 1, BecomeDonorViewModel.STEPS),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { (state.step + 1f) / BecomeDonorViewModel.STEPS }, modifier = Modifier.fillMaxWidth())
                when (state.step) {
                    0 -> BloodGroupStep(state, anchors, onEdit, onSkipBloodGroup)
                    1 -> DetailsStep(state, anchors, onEdit)
                    else -> HistoryStep(state, anchors, onEdit)
                }
                FormError(state.formError)
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

// ---- Step 1: blood group ----

@Composable
private fun BloodGroupStep(
    state: BecomeDonorUiState,
    anchors: ErrorAnchors<SetupField>,
    onEdit: (SetupDraft.() -> SetupDraft) -> Unit,
    onSkip: () -> Unit,
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    StepTitle(stringResource(R.string.setup_blood_title))
    BloodGroupPicker(
        selected = BloodGroup.fromCode(state.draft.bloodGroup),
        onSelect = { group -> onEdit { copy(bloodGroup = group.code, bloodGroupUnknown = false) } },
        errorText = if (SetupField.BLOOD_GROUP in state.errors) stringResource(R.string.error_blood_group_required) else null,
        modifier = Modifier.errorAnchor(anchors, SetupField.BLOOD_GROUP),
    )
    EdgeTextButton(stringResource(R.string.setup_blood_unknown), { showHelp = true })
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = {
                TextButton(onClick = {
                    showHelp = false
                    onSkip()
                }) { Text(stringResource(R.string.setup_blood_skip)) }
            },
            dismissButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.setup_blood_unknown)) },
            text = { Text(stringResource(R.string.setup_blood_unknown_body)) },
        )
    }
}

// ---- Step 2: gender, birth year, weight ----

@Composable
private fun DetailsStep(state: BecomeDonorUiState, anchors: ErrorAnchors<SetupField>, onEdit: (SetupDraft.() -> SetupDraft) -> Unit) {
    val d = state.draft
    StepTitle(stringResource(R.string.become_donor_details_title))
    Column(Modifier.errorAnchor(anchors, SetupField.GENDER), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.label_gender), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            options = Gender.entries.map { it.code to stringResource(it.labelRes) },
            selected = d.gender,
            onSelect = { code -> onEdit { copy(gender = code) } },
            isError = SetupField.GENDER in state.errors,
        )
        FieldError(SetupField.GENDER in state.errors, stringResource(R.string.error_answer_required))
    }
    LLTextField(
        value = d.birthYear,
        onValueChange = { v -> onEdit { copy(birthYear = v.filter(Char::isDigit).take(4)) } },
        label = stringResource(R.string.label_birth_year),
        errorText = if (SetupField.BIRTH_YEAR in state.errors) stringResource(R.string.error_birth_year) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        fieldModifier = Modifier.errorAnchor(anchors, SetupField.BIRTH_YEAR, textField = true),
    )
    Column(Modifier.errorAnchor(anchors, SetupField.WEIGHT), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.setup_weight_question), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            options = listOf("yes" to stringResource(R.string.answer_yes), "no" to stringResource(R.string.answer_no)),
            selected = d.weightOk?.let { if (it) "yes" else "no" },
            onSelect = { v -> onEdit { copy(weightOk = v == "yes") } },
            isError = SetupField.WEIGHT in state.errors,
        )
        FieldError(SetupField.WEIGHT in state.errors, stringResource(R.string.error_answer_required))
    }
}

// ---- Step 3: last donation and the listing consent ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryStep(state: BecomeDonorUiState, anchors: ErrorAnchors<SetupField>, onEdit: (SetupDraft.() -> SetupDraft) -> Unit) {
    val d = state.draft
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    StepTitle(stringResource(R.string.become_donor_history_title))
    Column(Modifier.errorAnchor(anchors, SetupField.LAST_DONATION), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.setup_last_donation), style = MaterialTheme.typography.labelLarge)
        val pickedLabel = d.lastDonationEpochDay?.takeIf { d.neverDonated == false }?.let {
            stringResource(R.string.last_donation_on, localDateText(LocalDate.ofEpochDay(it)))
        }
        ChoiceRow(
            options = listOf("never" to stringResource(R.string.last_donation_never), "date" to (pickedLabel ?: stringResource(R.string.last_donation_pick))),
            selected = when (d.neverDonated) {
                true -> "never"
                false -> "date"
                null -> null
            },
            onSelect = { v -> if (v == "never") onEdit { copy(neverDonated = true) } else pickingDate = true },
            isError = SetupField.LAST_DONATION in state.errors,
        )
        FieldError(SetupField.LAST_DONATION in state.errors, stringResource(R.string.error_last_donation_required))
    }
    if (pickingDate) {
        val today = LocalDate.now(ZoneOffset.UTC)
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = d.lastDonationEpochDay?.let { it * DAY_MILLIS },
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MILLIS <= today.toEpochDay()
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let { millis ->
                            onEdit { copy(neverDonated = false, lastDonationEpochDay = millis / DAY_MILLIS) }
                        }
                        pickingDate = false
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = dateState) }
    }

    val knownGroup = BloodGroup.fromCode(d.bloodGroup)
    if (knownGroup == null) {
        // No group yet (P1): nothing to list; Edit profile takes it later.
        Text(stringResource(R.string.settings_list_me_needs_group), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(Modifier.errorAnchor(anchors, SetupField.CONSENT), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.setup_consent_question, stringResource(knownGroup.labelRes)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.setup_consent_info), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // No answer is pre-selected: being listed is an explicit choice (PDPA 2026).
        ChoiceRow(
            options = listOf("yes" to stringResource(R.string.consent_yes), "no" to stringResource(R.string.consent_not_now)),
            selected = d.listed?.let { if (it) "yes" else "no" },
            onSelect = { v -> onEdit { copy(listed = v == "yes") } },
            isError = SetupField.CONSENT in state.errors,
        )
        FieldError(SetupField.CONSENT in state.errors, stringResource(R.string.error_consent_choice))
    }
    if (d.listed == true) {
        LLSwitchRow(
            title = stringResource(R.string.setup_available),
            checked = d.available,
            onCheckedChange = { v -> onEdit { copy(available = v) } },
        )
    }
}

private const val DAY_MILLIS = 86_400_000L
