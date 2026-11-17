package io.github.liquidlyf.feature.profilesetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.DropdownField
import io.github.liquidlyf.core.designsystem.components.EdgeTextButton
import io.github.liquidlyf.core.designsystem.components.ErrorAnchors
import io.github.liquidlyf.core.designsystem.components.FieldError
import io.github.liquidlyf.core.designsystem.components.FormLoadingState
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.errorAnchor
import io.github.liquidlyf.core.designsystem.components.rememberErrorAnchors
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.auth.FormError

@Composable
fun ProfileSetupRoute(viewModel: ProfileSetupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentAppLocale()
    ProfileSetupScreen(
        state = state,
        locale = locale,
        onEdit = viewModel::edit,
        onDivision = viewModel::onDivision,
        onDistrict = viewModel::onDistrict,
        onNext = { viewModel.next(locale) },
    )
}

/**
 * The short setup as D drew it (N3, FLOW-01, D1 = a): "About you" with the name, the mobile number
 * and the area, one screen, then Continue. Donor questions come later, only for donors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    state: ProfileSetupUiState,
    locale: AppLocale,
    onEdit: (SetupDraft.() -> SetupDraft) -> Unit,
    onDivision: (String) -> Unit,
    onDistrict: (String) -> Unit,
    onNext: () -> Unit,
) {
    Scaffold(
        topBar = { LLTopBar(title = stringResource(R.string.setup_about_title)) },
        bottomBar = {
            LLBottomBar(
                primaryText = stringResource(R.string.action_continue),
                onPrimary = onNext,
                primaryEnabled = state.loaded,
                loading = state.saving,
                loadingText = stringResource(R.string.action_saving),
            )
        },
    ) { padding ->
        val geo = state.geo
        if (!state.loaded || geo == null) {
            FormLoadingState(Modifier.padding(padding))
            return@Scaffold
        }
        val anchors = rememberErrorAnchors<SetupField>()
        // FLOW-04 (D's re-audit 4): a failed Continue brings the first error into view and focus.
        LaunchedEffect(state.errorAttempt) {
            if (state.errorAttempt > 0) anchors.showFirst(state.errors, SHORT_SETUP_ORDER)
        }
        val d = state.draft
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter(), vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.setup_short_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LLTextField(
                    value = d.fullName,
                    onValueChange = { v -> onEdit { copy(fullName = v) } },
                    label = stringResource(R.string.label_full_name),
                    errorText = if (SetupField.NAME in state.errors) stringResource(R.string.error_name_invalid) else null,
                    contentType = ContentType.PersonFullName,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    fieldModifier = Modifier.errorAnchor(anchors, SetupField.NAME, textField = true),
                )
                LLTextField(
                    value = d.phone,
                    onValueChange = { v -> onEdit { copy(phone = v.take(20)) } },
                    label = stringResource(R.string.label_phone),
                    errorText = if (SetupField.PHONE in state.errors) stringResource(R.string.error_phone_invalid) else null,
                    contentType = ContentType.PhoneNumber,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    fieldModifier = Modifier.errorAnchor(anchors, SetupField.PHONE, textField = true),
                )
                Column(Modifier.errorAnchor(anchors, SetupField.LOCATION), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        stringResource(R.string.setup_location_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.xs).semantics { heading() },
                    )
                    Text(stringResource(R.string.setup_location_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AreaPickers(
                        geo = geo,
                        locale = locale,
                        division = d.division,
                        district = d.district,
                        upazila = d.upazila,
                        onDivision = onDivision,
                        onDistrict = onDistrict,
                        onUpazila = { code -> onEdit { copy(upazila = code) } },
                        isError = SetupField.LOCATION in state.errors,
                    )
                    FieldError(SetupField.LOCATION in state.errors, stringResource(R.string.error_location_required))
                }
                // D1: say why there's no blood group or age here.
                Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(painterResource(LLIcons.Info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.setup_donor_later), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.needsTerms) TermsBlock(d.termsAccepted, SetupField.TERMS in state.errors, anchors, onEdit)
                FormError(state.formError)
            }
        }
    }
}

/** 18+ and the Terms, for accounts that never saw the sign-up form's box (Google). */
@Composable
private fun TermsBlock(accepted: Boolean, isError: Boolean, anchors: ErrorAnchors<SetupField>, onEdit: (SetupDraft.() -> SetupDraft) -> Unit) {
    val links = rememberSafeIntentLauncher()
    val locale = currentAppLocale()
    Column(Modifier.errorAnchor(anchors, SetupField.TERMS)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = accepted, role = Role.Checkbox, onValueChange = { v -> onEdit { copy(termsAccepted = v) } }),
        ) {
            Checkbox(
                checked = accepted,
                onCheckedChange = null,
                colors = if (isError) CheckboxDefaults.colors(uncheckedColor = MaterialTheme.colorScheme.error) else CheckboxDefaults.colors(),
            )
            Text(stringResource(R.string.sign_up_consent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.xs))
        }
        FieldError(isError, stringResource(R.string.error_consent_required))
        // The second link moves below the first when both don't fit (200% text).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            EdgeTextButton(stringResource(R.string.action_read_terms), { links.openUrl(Links.terms(locale)) })
            TextButton(onClick = { links.openUrl(Links.privacy(locale)) }) { Text(stringResource(R.string.action_read_privacy)) }
        }
    }
}

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
    /** The area is needed and incomplete (FLOW-04): the empty pickers go red. */
    isError: Boolean = false,
) {
    // N3 / FORM-01: Division → District → Upazila or city corporation, each opening only after the
    // one above it and listing only the places inside it; labels above the boxes (a two-line label
    // can't run across the outline at 200%); a locked box says why.
    val divisions = geo.divisions.map { it.code to it.name.localized(locale) }.sortedBy { it.second }
    DropdownField(
        stringResource(R.string.label_division), divisions, division, onDivision,
        isError = isError && division == null, placeholder = stringResource(R.string.area_choose_division), labelAbove = true,
    )
    val divisionInfo = division?.let(geo::division)
    val districts = divisionInfo?.districts.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
    DropdownField(
        stringResource(R.string.label_district), districts, district, onDistrict, enabled = division != null,
        isError = isError && district == null, labelAbove = true,
        placeholder = stringResource(if (division == null) R.string.area_division_first else R.string.area_choose_district),
        menuTitle = divisionInfo?.let { pluralStringResource(R.plurals.area_districts_in, districts.size, districts.size, it.name.localized(locale)) },
    )
    val areas = district?.let(geo::district)?.areas.orEmpty().map { it.code to it.name.localized(locale) }.sortedBy { it.second }
    DropdownField(
        stringResource(R.string.label_upazila), areas, upazila, onUpazila, enabled = district != null,
        isError = isError && upazila == null, labelAbove = true,
        placeholder = stringResource(if (district == null) R.string.area_district_first else R.string.area_choose_upazila),
    )
}
