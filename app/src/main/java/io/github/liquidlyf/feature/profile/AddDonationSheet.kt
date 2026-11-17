package io.github.liquidlyf.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLFilterChip
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.SheetDragHandle
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.localDateText
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.BloodComponent
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 6.4: date (not in the future, not before the donor turned 18), what they gave, and where.
 * Always self-reported; the history tags it so.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddDonationSheet(
    earliest: LocalDate?,
    saving: Boolean,
    toDraft: (LocalDate, BloodComponent, String?) -> DonationDraft,
    dateOf: (Long) -> LocalDate,
    onSave: (DonationDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var dateEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var component by rememberSaveable { mutableStateOf(BloodComponent.WHOLE_BLOOD) }
    var place by rememberSaveable { mutableStateOf("") }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val date = dateEpochDay?.let(LocalDate::ofEpochDay)
    val today = LocalDate.now()
    val dateError = when {
        date == null -> R.string.add_donation_date_required
        date.isAfter(today) -> R.string.add_donation_error_future
        earliest != null && date.isBefore(earliest) -> R.string.add_donation_error_too_early
        else -> null
    }
    val placeTooLong = place.trim().length > Validators.HOSPITAL_NAME_MAX

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md).padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.add_donation_title), style = MaterialTheme.typography.titleLarge)

            Text(stringResource(R.string.add_donation_date), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
                Icon(painterResource(LLIcons.Event), contentDescription = null)
                Text(
                    date?.let { localDateText(it) } ?: stringResource(R.string.add_donation_pick_date),
                    Modifier.padding(start = Spacing.xs),
                )
            }
            if (showErrors && dateError != null) {
                Text(stringResource(dateError), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Text(stringResource(R.string.add_donation_component), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                BloodComponent.entries.forEach { c ->
                    LLFilterChip(c == component, { component = c }, stringResource(c.labelRes))
                }
            }

            LLTextField(
                value = place,
                onValueChange = { place = it },
                label = stringResource(R.string.add_donation_place),
                supportingText = stringResource(R.string.add_donation_place_hint),
                errorText = if (placeTooLong) stringResource(R.string.error_invalid_input) else null,
                leadingIcon = LLIcons.Hospital,
                modifier = Modifier.fillMaxWidth(),
            )

            LLPrimaryButton(
                text = stringResource(R.string.add_donation_save),
                loading = saving,
                enabled = !saving,
                onClick = {
                    showErrors = true
                    val d = date
                    if (d != null && dateError == null && !placeTooLong) onSave(toDraft(d, component, place))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showPicker) {
        val earliestMillis = earliest?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = remember(earliestMillis, todayMillis) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in earliestMillis..todayMillis

                    override fun isSelectableYear(year: Int) =
                        year <= today.year && (earliest == null || year >= earliest.year)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { dateEpochDay = dateOf(it).toEpochDay() }
                    showPicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}
