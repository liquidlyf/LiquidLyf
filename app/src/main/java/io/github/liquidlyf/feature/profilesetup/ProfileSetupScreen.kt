package io.github.liquidlyf.feature.profilesetup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BloodGroupPicker
import io.github.liquidlyf.core.designsystem.components.ChoiceRow
import io.github.liquidlyf.core.designsystem.components.DropdownField
import io.github.liquidlyf.core.designsystem.components.FieldError
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.auth.FormError
import io.github.liquidlyf.core.designsystem.currentAppLocale
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ProfileSetupRoute(viewModel: ProfileSetupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentAppLocale()
    // Back steps inside the stepper; at the first step the system closes the app as usual.
    BackHandler(enabled = state.step > 0 && !state.saving) { viewModel.back() }
    ProfileSetupScreen(
        state = state,
        locale = locale,
        onEdit = viewModel::edit,
        onDivision = viewModel::onDivision,
        onDistrict = viewModel::onDistrict,
        onNext = { viewModel.next(locale) },
        onBack = { viewModel.back() },
        onSkipBloodGroup = { viewModel.skipBloodGroup(locale) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    state: ProfileSetupUiState,
    locale: AppLocale,
    onEdit: (SetupDraft.() -> SetupDraft) -> Unit,
    onDivision: (String) -> Unit,
    onDistrict: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkipBloodGroup: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.title_profile_setup),
                onBack = if (state.step > 0 && !state.saving) onBack else null,
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = screenGutter(), vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.step > 0) {
                        OutlinedButton(onClick = onBack, enabled = !state.saving) { Text(stringResource(R.string.action_back)) }
                    }
                    LLPrimaryButton(
                        text = stringResource(if (state.isLastStep) R.string.action_finish else R.string.action_next),
                        onClick = onNext,
                        enabled = state.loaded,
                        loading = state.saving,
                        loadingText = stringResource(R.string.action_saving),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { padding ->
        if (!state.loaded) {
            LoadingState(Modifier.padding(padding))
            return@Scaffold
        }
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
                    stringResource(R.string.setup_step, state.step + 1, ProfileSetupViewModel.STEPS),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { (state.step + 1f) / ProfileSetupViewModel.STEPS },
                    modifier = Modifier.fillMaxWidth(),
                )
                when (state.step) {
                    0 -> BloodGroupStep(state, onEdit, onSkipBloodGroup)
                    1 -> AboutStep(state, onEdit)
                    2 -> LocationStep(state, locale, onEdit, onDivision, onDistrict)
                    else -> ContactStep(state, onEdit)
                }
                FormError(state.formError)
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

// ---- Step 1: blood group ----

@Composable
private fun BloodGroupStep(state: ProfileSetupUiState, onEdit: (SetupDraft.() -> SetupDraft) -> Unit, onSkip: () -> Unit) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    StepTitle(stringResource(R.string.setup_blood_title))
    BloodGroupPicker(
        selected = BloodGroup.fromCode(state.draft.bloodGroup),
        onSelect = { group -> onEdit { copy(bloodGroup = group.code, bloodGroupUnknown = false) } },
        errorText = if (SetupField.BLOOD_GROUP in state.errors) stringResource(R.string.error_blood_group_required) else null,
    )
    TextButton(onClick = { showHelp = true }) { Text(stringResource(R.string.setup_blood_unknown)) }
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

// ---- Step 2: about you ----

@Composable
private fun AboutStep(state: ProfileSetupUiState, onEdit: (SetupDraft.() -> SetupDraft) -> Unit) {
    val d = state.draft
    StepTitle(stringResource(R.string.setup_about_title))
    LLTextField(
        value = d.fullName,
        onValueChange = { v -> onEdit { copy(fullName = v) } },
        label = stringResource(R.string.label_full_name),
        errorText = if (SetupField.NAME in state.errors) stringResource(R.string.error_name_invalid) else null,
        contentType = ContentType.PersonFullName,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Text(stringResource(R.string.label_gender), style = MaterialTheme.typography.labelLarge)
    ChoiceRow(
        options = Gender.entries.map { it.code to stringResource(it.labelRes) },
        selected = d.gender,
        onSelect = { code -> onEdit { copy(gender = code) } },
    )
    FieldError(SetupField.GENDER in state.errors, stringResource(R.string.error_answer_required))
    LLTextField(
        value = d.birthYear,
        onValueChange = { v -> onEdit { copy(birthYear = v.filter(Char::isDigit).take(4)) } },
        label = stringResource(R.string.label_birth_year),
        errorText = if (SetupField.BIRTH_YEAR in state.errors) stringResource(R.string.error_birth_year) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
    )
    Text(stringResource(R.string.setup_weight_question), style = MaterialTheme.typography.labelLarge)
    ChoiceRow(
        options = listOf("yes" to stringResource(R.string.answer_yes), "no" to stringResource(R.string.answer_no)),
        selected = d.weightOk?.let { if (it) "yes" else "no" },
        onSelect = { v -> onEdit { copy(weightOk = v == "yes") } },
    )
    FieldError(SetupField.WEIGHT in state.errors, stringResource(R.string.error_answer_required))
}

// ---- Step 3: area ----

@Composable
private fun LocationStep(
    state: ProfileSetupUiState,
    locale: AppLocale,
    onEdit: (SetupDraft.() -> SetupDraft) -> Unit,
    onDivision: (String) -> Unit,
    onDistrict: (String) -> Unit,
) {
    val geo: BdGeo = state.geo ?: return
    val d = state.draft
    StepTitle(stringResource(R.string.setup_location_title))
    Text(stringResource(R.string.setup_location_body), style = MaterialTheme.typography.bodyMedium)
    AreaPickers(
        geo = geo,
        locale = locale,
        division = d.division,
        district = d.district,
        upazila = d.upazila,
        onDivision = onDivision,
        onDistrict = onDistrict,
        onUpazila = { code -> onEdit { copy(upazila = code) } },
    )
    FieldError(SetupField.LOCATION in state.errors, stringResource(R.string.error_location_required))
}

// ---- Step 4: phone, last donation, listing consent ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactStep(state: ProfileSetupUiState, onEdit: (SetupDraft.() -> SetupDraft) -> Unit) {
    val d = state.draft
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    StepTitle(stringResource(R.string.setup_contact_title))
    LLTextField(
        value = d.phone,
        onValueChange = { v -> onEdit { copy(phone = v.take(20)) } },
        label = stringResource(R.string.label_phone),
        supportingText = stringResource(R.string.phone_hint),
        errorText = if (SetupField.PHONE in state.errors) stringResource(R.string.error_phone_invalid) else null,
        contentType = ContentType.PhoneNumber,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
    )

    Text(stringResource(R.string.setup_last_donation), style = MaterialTheme.typography.labelLarge)
    val pickedLabel = d.lastDonationEpochDay?.takeIf { d.neverDonated == false }?.let {
        stringResource(R.string.last_donation_on, LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    }
    ChoiceRow(
        options = listOf("never" to stringResource(R.string.last_donation_never), "date" to (pickedLabel ?: stringResource(R.string.last_donation_pick))),
        selected = when (d.neverDonated) {
            true -> "never"
            false -> "date"
            null -> null
        },
        onSelect = { v -> if (v == "never") onEdit { copy(neverDonated = true) } else pickingDate = true },
    )
    FieldError(SetupField.LAST_DONATION in state.errors, stringResource(R.string.error_last_donation_required))
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
        // No group yet (P1): nothing to list; the profile reminds them later.
        Text(stringResource(R.string.setup_no_group_listing), style = MaterialTheme.typography.bodyMedium)
    } else {
    val group = stringResource(knownGroup.labelRes)
    Text(stringResource(R.string.setup_consent_question, group), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_consent_info), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // No answer is pre-selected: being listed is an explicit choice (PDPA 2026).
    ChoiceRow(
        options = listOf("yes" to stringResource(R.string.consent_yes), "no" to stringResource(R.string.consent_not_now)),
        selected = d.listed?.let { if (it) "yes" else "no" },
        onSelect = { v -> onEdit { copy(listed = v == "yes") } },
    )
    FieldError(SetupField.CONSENT in state.errors, stringResource(R.string.error_consent_choice))
    }
    if (state.needsTerms) {
        val links = rememberSafeIntentLauncher()
        val locale = currentAppLocale()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = d.termsAccepted, role = Role.Checkbox, onValueChange = { v -> onEdit { copy(termsAccepted = v) } }),
        ) {
            Checkbox(checked = d.termsAccepted, onCheckedChange = null)
            Text(stringResource(R.string.sign_up_consent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.xs))
        }
        FieldError(SetupField.TERMS in state.errors, stringResource(R.string.error_consent_required))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = { links.openUrl(Links.terms(locale)) }) { Text(stringResource(R.string.action_read_terms)) }
            TextButton(onClick = { links.openUrl(Links.privacy(locale)) }) { Text(stringResource(R.string.action_read_privacy)) }
        }
    }
    if (d.listed == true) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.setup_available)) },
            trailingContent = { Switch(checked = d.available, onCheckedChange = { v -> onEdit { copy(available = v) } }) },
        )
    }
}

private const val DAY_MILLIS = 86_400_000L

/** Division → district → upazila or city corporation, each list narrowed by the one above. */
@Composable
internal fun AreaPickers(
    geo: BdGeo,
    locale: AppLocale,
    division: String?,
    district: String?,
    upazila: String?,
    onDivision: (String) -> Unit,
    onDistrict: (String) -> Unit,
    onUpazila: (String) -> Unit,
) {
    val divisions = geo.divisions.map { it.code to it.name.localized(locale) }.sortedBy { it.second }
    DropdownField(stringResource(R.string.label_division), divisions, division, onDivision)
    val districts = division?.let(geo::division)?.districts.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
    DropdownField(stringResource(R.string.label_district), districts, district, onDistrict, enabled = division != null)
    val areas = district?.let(geo::district)?.areas.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
    DropdownField(stringResource(R.string.label_upazila), areas, upazila, onUpazila, enabled = district != null)
}
