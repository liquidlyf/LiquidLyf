package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
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

/**
 * One selectable blood group. TalkBack reads "A negative", not "A minus". The check mark sits in
 * the corner, not before the label: on a 360 dp phone a leading icon wrapped "AB−" onto two lines.
 */
@Composable
fun BloodGroupChip(
    group: BloodGroup,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** [Role.Checkbox] where several groups can be picked (Find donors). */
    role: Role = Role.RadioButton,
) {
    val spoken = stringResource(group.spokenLabelRes)
    Box(modifier) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = {
                Text(
                    text = stringResource(group.labelRes),
                    style = LiquidLyfTheme.extendedTypography.bloodGroupBadge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.minTouchTarget)
                .semantics {
                    contentDescription = spoken
                    this.role = role
                    this.selected = selected
                },
        )
        if (selected) {
            Icon(
                painterResource(LLIcons.Check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(14.dp),
            )
        }
    }
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
            BloodGroup.entries.chunked(bloodGroupsPerRow()).forEach { row ->
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

/** 8.5: four chips a row, or two with large text, so "AB+" is never cut. */
@Composable
fun bloodGroupsPerRow(): Int = if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) 2 else 4

/** From here on, text is "large" (Android's 130% step and above). */
const val LARGE_FONT_SCALE = 1.3f

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
