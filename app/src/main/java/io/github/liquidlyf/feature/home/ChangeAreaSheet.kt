package io.github.liquidlyf.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BarButtons
import io.github.liquidlyf.core.designsystem.components.ButtonLabel
import io.github.liquidlyf.core.designsystem.components.FieldError
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.SheetDragHandle
import io.github.liquidlyf.core.designsystem.components.isLargeText
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.feature.auth.FormError
import io.github.liquidlyf.feature.profilesetup.AreaPickers

/**
 * D27: "Change area" from Home's empty state, the area only, as the maintainer picked it from D's
 * picture (ui-audit/change-area.html): the profile's area already chosen, one line on what it
 * changes, Cancel and Save. An incomplete area shows the empty boxes red with the usual line; a
 * failed save shows inside the sheet and keeps the choices. The choices survive turning the phone.
 * Tapping outside, swiping down and Back close it too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangeAreaSheet(
    geo: BdGeo,
    locale: AppLocale,
    current: Area,
    state: AreaSheetState,
    onSave: (Area) -> Unit,
    onDismiss: () -> Unit,
) {
    var division by rememberSaveable { mutableStateOf<String?>(current.division) }
    var district by rememberSaveable { mutableStateOf<String?>(current.district) }
    var upazila by rememberSaveable { mutableStateOf<String?>(current.upazila) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val area = if (division != null && district != null && upazila != null) Area(division!!, district!!, upazila!!) else null
    val invalid = area == null || !geo.isValid(area)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), dragHandle = { SheetDragHandle() }) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = screenGutter())
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.home_change_area), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.home_change_area_line), style = MaterialTheme.typography.bodyMedium)
            AreaPickers(
                geo = geo,
                locale = locale,
                division = division,
                district = district,
                upazila = upazila,
                onDivision = { code ->
                    division = code
                    district = null
                    upazila = null
                },
                onDistrict = { code ->
                    district = code
                    upazila = null
                },
                onUpazila = { code -> upazila = code },
                isError = showErrors && invalid,
                enabled = !state.saving,
            )
            FieldError(showErrors && invalid, stringResource(R.string.error_location_required))
            FormError(state.error)
            BarButtons(
                stackAlways = isLargeText(),
                secondary = {
                    OutlinedButton(onClick = onDismiss, enabled = !state.saving, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                        ButtonLabel(stringResource(R.string.action_cancel))
                    }
                },
                primary = {
                    // FLOW-04: Save stays enabled; an incomplete area says what's missing.
                    LLPrimaryButton(
                        text = stringResource(R.string.action_save),
                        loading = state.saving,
                        loadingText = stringResource(R.string.action_saving),
                        onClick = {
                            showErrors = true
                            if (!invalid && area != null) onSave(area)
                        },
                    )
                },
            )
        }
    }
}
