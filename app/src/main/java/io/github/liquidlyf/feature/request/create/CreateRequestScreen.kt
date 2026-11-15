package io.github.liquidlyf.feature.request.create

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.numberText
import io.github.liquidlyf.core.designsystem.components.BloodGroupPicker
import io.github.liquidlyf.core.designsystem.components.ChoiceRow
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.FieldError
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.OfflineBanner
import io.github.liquidlyf.core.designsystem.components.unitsText
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.dateTimeText
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.domain.BloodCompatibility
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.feature.profilesetup.AreaPickers
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun CreateRequestRoute(
    onClose: () -> Unit,
    onPosted: (requestId: String, notice: PostNotice?) -> Unit,
    onOpenMyRequests: () -> Unit,
    viewModel: CreateRequestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e -> if (e is CreateRequestEvent.Posted) onPosted(e.requestId, e.notice) }
    }
    if (state.moneyWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::changeNote,
            title = { Text(stringResource(R.string.money_warning_title)) },
            text = { Text(stringResource(R.string.money_warning_body)) },
            confirmButton = { TextButton(onClick = viewModel::postAnyway) { Text(stringResource(R.string.action_post_anyway)) } },
            dismissButton = { TextButton(onClick = viewModel::changeNote) { Text(stringResource(R.string.action_change_note)) } },
        )
    }
    BackHandler(enabled = state.step > 0 && !state.posting) { viewModel.back() }
    CreateRequestScreen(
        state = state,
        online = online,
        onEdit = viewModel::edit,
        onDivision = viewModel::onDivision,
        onDistrict = viewModel::onDistrict,
        onHospitalTyped = viewModel::onHospitalTyped,
        onHospitalPicked = viewModel::onHospitalPicked,
        onNext = viewModel::next,
        onBack = { if (!viewModel.back()) onClose() },
        onOpenMyRequests = onOpenMyRequests,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateRequestScreen(
    state: CreateRequestUiState,
    online: Boolean,
    onEdit: (RequestForm.() -> RequestForm) -> Unit,
    onDivision: (String) -> Unit,
    onDistrict: (String) -> Unit,
    onHospitalTyped: (String) -> Unit,
    onHospitalPicked: (io.github.liquidlyf.core.data.hospital.Hospital, String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onOpenMyRequests: () -> Unit,
) {
    val limit = state.limit
    Scaffold(
        topBar = {
            LLTopBar(
                title = stringResource(R.string.title_create_request),
                onBack = if (!state.posting) onBack else null,
            )
        },
        bottomBar = {
            if (state.loaded && limit == null) {
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
                            OutlinedButton(onClick = onBack, enabled = !state.posting) { Text(stringResource(R.string.action_back)) }
                        }
                        LLPrimaryButton(
                            text = stringResource(if (state.isLastStep) R.string.action_post_request else R.string.action_next),
                            onClick = onNext,
                            loading = state.posting,
                            loadingText = stringResource(R.string.action_posting),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            !state.loaded -> LoadingState(Modifier.padding(padding))
            limit != null -> EmptyState(
                icon = LLIcons.HourglassTop,
                title = stringResource(R.string.create_limit_title),
                body = if (limit.newAccount) {
                    stringResource(R.string.create_limit_body_new)
                } else {
                    pluralStringResource(R.plurals.create_limit_body, limit.max, limit.max)
                },
                actionLabel = stringResource(R.string.create_limit_action),
                onAction = onOpenMyRequests,
                modifier = Modifier.padding(padding),
            )
            else -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                OfflineBanner(online)
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = screenGutter(), vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        stringResource(R.string.create_step, state.step + 1, CreateRequestViewModel.STEPS),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (state.step) {
                        0 -> PatientStep(state, onEdit)
                        1 -> WhereWhenStep(state, onEdit, onDivision, onDistrict, onHospitalTyped, onHospitalPicked)
                        else -> ContactStep(state, onEdit)
                    }
                    state.formError?.let {
                        Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

// ---- Step 1: patient ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatientStep(state: CreateRequestUiState, onEdit: (RequestForm.() -> RequestForm) -> Unit) {
    val f = state.form
    StepTitle(stringResource(R.string.create_patient_title))
    BloodGroupPicker(
        selected = BloodGroup.fromCode(f.bloodGroup),
        onSelect = { g -> onEdit { copy(bloodGroup = g.code) } },
        label = stringResource(R.string.create_patient_group),
        errorText = if (RequestField.BLOOD_GROUP in state.errors) stringResource(R.string.create_error_group) else null,
    )

    Text(stringResource(R.string.create_component), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        BloodComponent.entries.forEach { c ->
            FilterChip(
                selected = f.component == c.code,
                onClick = { onEdit { copy(component = c.code) } },
                label = { Text(stringResource(c.labelRes)) },
                modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
            )
        }
    }

    UnitsStepper(units = f.units, onChange = { n -> onEdit { copy(units = n) } })

    Text(stringResource(R.string.create_condition), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PatientCondition.entries.forEach { c ->
            FilterChip(
                selected = f.condition == c.code,
                onClick = { onEdit { copy(condition = if (condition == c.code) null else c.code) } },
                label = { Text(stringResource(c.labelRes)) },
                modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
            )
        }
    }

    val group = BloodGroup.fromCode(f.bloodGroup)
    val component = BloodComponent.fromCode(f.component) ?: BloodComponent.WHOLE_BLOOD
    ListItem(
        headlineContent = { Text(stringResource(R.string.create_accept_compatible)) },
        supportingContent = { Text(stringResource(R.string.create_accept_compatible_info)) },
        trailingContent = { Switch(checked = f.acceptCompatible, onCheckedChange = null) },
        modifier = Modifier.toggleable(
            value = f.acceptCompatible,
            role = Role.Switch,
            onValueChange = { v -> onEdit { copy(acceptCompatible = v) } },
        ),
    )
    if (group != null && f.acceptCompatible) {
        val others = BloodCompatibility.compatibleDonors(group, component).filter { it != group }
        val names = others.map { stringResource(it.labelRes) }
        Text(
            if (names.isEmpty()) stringResource(R.string.create_compatible_none) else stringResource(R.string.create_compatible_list, names.joinToString(", ")),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnitsStepper(units: Int, onChange: (Int) -> Unit) {
    val description = pluralStringResource(R.plurals.create_units_spoken, units, units)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.create_units), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        // 8.5: the whole 48 dp is the button, next to large text too.
        IconButton(onClick = { onChange(units - 1) }, enabled = units > Validators.UNITS_MIN, modifier = Modifier.size(Spacing.minTouchTarget)) {
            Icon(painterResource(LLIcons.Remove), contentDescription = stringResource(R.string.create_units_less))
        }
        Text(
            numberText(units),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        )
        IconButton(onClick = { onChange(units + 1) }, enabled = units < Validators.UNITS_MAX, modifier = Modifier.size(Spacing.minTouchTarget)) {
            Icon(painterResource(LLIcons.Add), contentDescription = stringResource(R.string.create_units_more))
        }
    }
}

// ---- Step 2: where and when ----

@Composable
private fun WhereWhenStep(
    state: CreateRequestUiState,
    onEdit: (RequestForm.() -> RequestForm) -> Unit,
    onDivision: (String) -> Unit,
    onDistrict: (String) -> Unit,
    onHospitalTyped: (String) -> Unit,
    onHospitalPicked: (io.github.liquidlyf.core.data.hospital.Hospital, String) -> Unit,
) {
    val f = state.form
    val locale = currentAppLocale()
    StepTitle(stringResource(R.string.create_where_title))
    state.geo?.let { geo ->
        AreaPickers(
            geo = geo,
            locale = locale,
            division = f.division,
            district = f.district,
            upazila = f.upazila,
            onDivision = onDivision,
            onDistrict = onDistrict,
            onUpazila = { code -> onEdit { copy(upazila = code) } },
        )
    }
    FieldError(RequestField.LOCATION in state.errors, stringResource(R.string.error_location_required))

    LLTextField(
        value = f.hospitalName,
        onValueChange = onHospitalTyped,
        label = stringResource(R.string.create_hospital),
        supportingText = stringResource(R.string.create_hospital_hint),
        errorText = if (RequestField.HOSPITAL in state.errors) stringResource(R.string.create_error_hospital) else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    )
    if (state.hospitals.isNotEmpty()) {
        Card {
            state.hospitals.forEachIndexed { i, h ->
                if (i > 0) HorizontalDivider()
                val name = h.name.localized(locale)
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = h.address?.let { { Text(it) } },
                    modifier = Modifier.clickable(role = Role.Button, onClick = { onHospitalPicked(h, name) }),
                )
            }
        }
    }
    LLTextField(
        value = f.wardOrBed,
        onValueChange = { v -> onEdit { copy(wardOrBed = v.take(Validators.WARD_OR_BED_MAX)) } },
        label = stringResource(R.string.create_ward),
        supportingText = stringResource(R.string.create_private_hint),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )

    Text(stringResource(R.string.create_urgency), style = MaterialTheme.typography.labelLarge)
    ChoiceRow(
        options = Urgency.entries.map { it.code to stringResource(it.labelRes) },
        selected = f.urgency,
        onSelect = { code -> onEdit { copy(urgency = code) } },
    )
    val urgencyHelp = when (Urgency.fromCode(f.urgency)) {
        Urgency.CRITICAL -> R.string.create_urgency_critical_help
        Urgency.URGENT -> R.string.create_urgency_urgent_help
        Urgency.PLANNED -> R.string.create_urgency_planned_help
        null -> null
    }
    urgencyHelp?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    FieldError(RequestField.URGENCY in state.errors, stringResource(R.string.create_error_urgency))
    if (f.urgency == Urgency.PLANNED.code) PlannedAtPicker(f.plannedAtMillis, RequestField.PLANNED_AT in state.errors) { millis ->
        onEdit { copy(plannedAtMillis = millis) }
    }
}

/** Date, then time; the rules accept 15 minutes to 30 days ahead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannedAtPicker(plannedAtMillis: Long?, showError: Boolean, onPicked: (Long) -> Unit) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
        Icon(painterResource(LLIcons.Event), contentDescription = null)
        Text(
            plannedAtMillis?.let { dateTimeText(Instant.ofEpochMilli(it)) } ?: stringResource(R.string.create_pick_time),
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
    FieldError(showError, stringResource(R.string.create_error_planned))

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    if (pickingDate) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = plannedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !day.isBefore(today) && !day.isAfter(today.plusDays(PLANNED_MAX_DAYS))
                }
                override fun isSelectableYear(year: Int) = year in today.year..today.plusDays(PLANNED_MAX_DAYS).year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDay = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    pickingDate = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = dateState) }
    }
    val day = pickedDay
    if (day != null) {
        val initial = plannedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(10, 0)
        val timeState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
        AlertDialog(
            onDismissRequest = { pickedDay = null },
            title = { Text(stringResource(R.string.create_pick_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val at = LocalDate.ofEpochDay(day).atTime(timeState.hour, timeState.minute).atZone(zone).toInstant()
                    onPicked(at.toEpochMilli())
                    pickedDay = null
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickedDay = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private const val PLANNED_MAX_DAYS = 29L

// ---- Step 3: contact and review ----

@Composable
private fun ContactStep(state: CreateRequestUiState, onEdit: (RequestForm.() -> RequestForm) -> Unit) {
    val f = state.form
    StepTitle(stringResource(R.string.create_contact_title))
    Text(stringResource(R.string.create_contact_info), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LLTextField(
        value = f.contactName,
        onValueChange = { v -> onEdit { copy(contactName = v.take(Validators.NAME_MAX)) } },
        label = stringResource(R.string.create_contact_name),
        errorText = if (RequestField.CONTACT_NAME in state.errors) stringResource(R.string.error_name_invalid) else null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    )
    LLTextField(
        value = f.contactPhone,
        onValueChange = { v -> onEdit { copy(contactPhone = v.take(20)) } },
        label = stringResource(R.string.create_contact_phone),
        // The requester's number, not the donor's own (that one's hint is phone_hint).
        supportingText = stringResource(R.string.create_private_hint),
        errorText = if (RequestField.CONTACT_PHONE in state.errors) stringResource(R.string.error_phone_invalid) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
    )
    LLTextField(
        value = f.patientName,
        onValueChange = { v -> onEdit { copy(patientName = v.take(Validators.PATIENT_NAME_MAX)) } },
        label = stringResource(R.string.create_patient_name),
        supportingText = stringResource(R.string.create_private_hint),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
    )
    LLTextField(
        value = f.notes,
        onValueChange = { v -> onEdit { copy(notes = v.take(Validators.NOTES_MAX)) } },
        label = stringResource(R.string.create_notes),
        supportingText = stringResource(R.string.create_notes_counter, f.notes.length, Validators.NOTES_MAX),
        singleLine = false,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    if (Validators.hasMoneyWords(f.notes)) {
        Text(stringResource(R.string.create_money_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    ReviewCard(state)

    // 8.3: one line of the medical disclaimer, where the request is made.
    Text(
        stringResource(R.string.create_disclaimer),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .toggleable(value = f.genuine, role = Role.Checkbox, onValueChange = { v -> onEdit { copy(genuine = v) } }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = f.genuine, onCheckedChange = null)
        Text(stringResource(R.string.create_genuine), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.xs))
    }
    FieldError(RequestField.GENUINE in state.errors, stringResource(R.string.create_error_genuine))
}

/** What will be posted, in one card, so a mistake is caught before donors are alerted. */
@Composable
private fun ReviewCard(state: CreateRequestUiState) {
    val f = state.form
    val locale = currentAppLocale()
    val group = BloodGroup.fromCode(f.bloodGroup)?.let { stringResource(it.labelRes) }.orEmpty()
    val component = BloodComponent.fromCode(f.component)?.let { stringResource(it.labelRes) }.orEmpty()
    val place = f.district?.let { state.geo?.placeLabel(it, f.upazila, locale) }.orEmpty()
    val urgency = Urgency.fromCode(f.urgency)?.let { stringResource(it.labelRes) }.orEmpty()
    Card {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(stringResource(R.string.create_review), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("$group · ${unitsText(f.units, component)}")
            Text(f.hospitalName.trim())
            Text(place)
            Text(urgency)
        }
    }
}
