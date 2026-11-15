package io.github.liquidlyf.feature.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BloodGroupBadge
import io.github.liquidlyf.core.designsystem.components.BloodGroupPicker
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.ErrorState
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.SectionHeader
import io.github.liquidlyf.core.designsystem.components.StatusPill
import io.github.liquidlyf.core.designsystem.components.UrgencyBadge
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppError

/**
 * Debug builds only (reached from Settings). Shows every design-system component, so the
 * light and dark themes, Bangla and large fonts can be checked on a real phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentGalleryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var bloodGroup by rememberSaveable { mutableStateOf<BloodGroup?>(BloodGroup.O_NEG) }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = { LLTopBar(title = stringResource(R.string.title_component_gallery), onBack = dropUnlessResumed(block = onBack)) },
    ) { padding ->
        val gutter = screenGutter()
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(padding),
        ) {
            item { SectionHeader(stringResource(R.string.gallery_section_buttons), Modifier.padding(horizontal = gutter)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(horizontal = gutter)) {
                    LLPrimaryButton(
                        text = stringResource(R.string.action_request_blood),
                        onClick = {},
                        icon = LLIcons.Add,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LLPrimaryButton(
                        text = stringResource(R.string.action_request_blood),
                        onClick = {},
                        loading = true,
                        loadingText = stringResource(R.string.gallery_button_posting),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.gallery_section_fields), Modifier.padding(horizontal = gutter)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(horizontal = gutter)) {
                    LLTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = stringResource(R.string.label_phone),
                        supportingText = stringResource(R.string.gallery_field_supporting),
                        errorText = if (phone.isNotEmpty() && !phone.matches(Regex("01\\d{9}"))) {
                            stringResource(R.string.gallery_field_error)
                        } else {
                            null
                        },
                        contentType = ContentType.PhoneNumber,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    )
                    LLTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.label_password),
                        isPassword = true,
                        contentType = ContentType.Password,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.gallery_section_blood_groups), Modifier.padding(horizontal = gutter)) }
            item {
                BloodGroupPicker(
                    selected = bloodGroup,
                    onSelect = { bloodGroup = it },
                    modifier = Modifier.padding(horizontal = gutter),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = gutter),
                ) {
                    bloodGroup?.let {
                        BloodGroupBadge(it)
                        BloodGroupBadge(it, size = Spacing.xxl * 2, hero = true)
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.gallery_section_status), Modifier.padding(horizontal = gutter)) }
            item { PillRow(Modifier.padding(horizontal = gutter)) }

            item { SectionHeader(stringResource(R.string.gallery_section_states), Modifier.padding(horizontal = gutter)) }
            item { LoadingState(Modifier.padding(horizontal = gutter)) }
            item {
                EmptyState(
                    icon = LLIcons.Inbox,
                    title = stringResource(R.string.gallery_empty_title),
                    body = stringResource(R.string.gallery_empty_body),
                    actionLabel = stringResource(R.string.action_request_blood),
                    onAction = {},
                )
            }
            item { ErrorState(error = AppError.Network, onRetry = {}) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillRow(modifier: Modifier = Modifier) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = modifier.fillMaxWidth(),
    ) {
        Urgency.entries.forEach { UrgencyBadge(it) }
        RequestStatus.entries.forEach { StatusPill(it) }
        ResponseStatus.entries.forEach { StatusPill(it) }
    }
}
