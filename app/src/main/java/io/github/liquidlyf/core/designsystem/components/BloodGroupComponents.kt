package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.spokenLabelRes
import io.github.liquidlyf.core.model.BloodGroup

/** One selectable blood group. TalkBack reads "A negative", not "A minus". */
@Composable
fun BloodGroupChip(
    group: BloodGroup,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken = stringResource(group.spokenLabelRes)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = stringResource(group.labelRes),
                style = LiquidLyfTheme.extendedTypography.bloodGroupBadge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        leadingIcon = if (selected) {
            { Icon(painterResource(LLIcons.Check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = modifier
            .heightIn(min = Spacing.minTouchTarget)
            .semantics {
                contentDescription = spoken
                role = Role.RadioButton
                this.selected = selected
            },
    )
}

/**
 * Pick one blood group from a 4×2 grid (replaces v0's spinner, whose placeholder counted as
 * a value). [errorText] shows under the grid.
 */
@Composable
fun BloodGroupPicker(
    selected: BloodGroup?,
    onSelect: (BloodGroup) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.blood_group_picker_label),
    errorText: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            BloodGroup.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    row.forEach { group ->
                        BloodGroupChip(
                            group = group,
                            selected = group == selected,
                            onClick = { onSelect(group) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (errorText != null) {
            Text(errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** A blood group shown as a circle badge (doc 04 §3), e.g. on a request card. */
@Composable
fun BloodGroupBadge(
    group: BloodGroup,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    hero: Boolean = false,
) {
    val spoken = stringResource(group.spokenLabelRes)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(
            text = stringResource(group.labelRes),
            style = if (hero) LiquidLyfTheme.extendedTypography.bloodGroupHero else LiquidLyfTheme.extendedTypography.bloodGroupBadge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
