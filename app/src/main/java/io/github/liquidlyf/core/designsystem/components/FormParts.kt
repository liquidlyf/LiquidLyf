package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing

/** A row of mutually exclusive answers. None is selected until the user picks (no defaults). */
@Composable
fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    SegmentedChoices(options, options.firstOrNull { it.first == selected }, { onSelect(it.first) }, modifier, isError) { it.second }
}

/**
 * Segmented buttons that keep every word whole (8.5). Each segment is at least as wide as its
 * longest word, a label wraps only between words, and all segments take the tallest one's height,
 * so the outline stays one pill. If they still can't fit side by side, the row scrolls sideways
 * instead of breaking "কাছাকাছি" in two.
 *
 * With large text (Android's 130% and up) the options are a vertical list of radio buttons
 * instead (A11Y-02): at 200% a sideways row hid options off the edge, even the chosen one
 * ("✓ ব", "Ot" for Other), with nothing to show it could scroll.
 */
@Composable
fun <T> SegmentedChoices(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** Nothing picked yet and it's needed (FLOW-04): red outlines, next to the error line below. */
    isError: Boolean = false,
    /** What TalkBack reads when the short [label] alone isn't clear ("Phone" → "Same as phone", N5). */
    spokenLabel: @Composable (T) -> String? = { null },
    /** A smaller second line under the label, e.g. "within 6 hours" (VT-04). */
    sublabel: @Composable (T) -> String? = { null },
    label: @Composable (T) -> String,
) {
    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
        RadioChoices(options, selected, onSelect, modifier, isError, spokenLabel, sublabel, label)
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fill = if (constraints.hasBoundedWidth) Modifier.widthIn(min = maxWidth) else Modifier
        SingleChoiceSegmentedButtonRow(
            Modifier
                .horizontalScroll(rememberScrollState())
                .then(fill)
                // The width first (3 × the widest word, or the full row), then the height for that width.
                .width(IntrinsicSize.Min)
                .height(IntrinsicSize.Min),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = chosenSegmentColors(isError),
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    val sub = sublabel(option)
                    if (sub == null) {
                        Text(label(option), textAlign = TextAlign.Center, modifier = Modifier.spoken(spokenLabel(option)))
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label(option), textAlign = TextAlign.Center, modifier = Modifier.spoken(spokenLabel(option)))
                            Text(sub, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.spoken(text: String?): Modifier = if (text == null) this else semantics { contentDescription = text }

/** One option per line, each a whole 48 dp row to tap; TalkBack reads them as one radio group. */
@Composable
private fun <T> RadioChoices(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier,
    isError: Boolean,
    spokenLabel: @Composable (T) -> String?,
    sublabel: @Composable (T) -> String?,
    label: @Composable (T) -> String,
) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.minTouchTarget)
                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) }),
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = null,
                    colors = if (isError) RadioButtonDefaults.colors(unselectedColor = MaterialTheme.colorScheme.error) else RadioButtonDefaults.colors(),
                )
                Column(Modifier.weight(1f)) {
                    Text(label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.spoken(spokenLabel(option)))
                    sublabel(option)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/*
 * A chosen option looks the same everywhere (COLOR-02, D4 a): solid red with white text and a
 * check, for chips, blood-group tiles and segmented rows alike. Not chosen = outlined. The old
 * pink fill was 1.23:1 against the background, and the form's chips had no check at all.
 */

/** Material's FilterChip colours for a chosen option. */
@Composable
fun chosenChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
)

/** Material's SegmentedButton colours for a chosen option (its own check stays); red outlines for an error. */
@Composable
fun chosenSegmentColors(isError: Boolean = false): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary,
    activeContentColor = MaterialTheme.colorScheme.onPrimary,
    inactiveBorderColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
)

/** A chip to pick (what's needed, the reason, a component): a check when chosen, 48 dp tall. */
@Composable
fun LLFilterChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = MaterialTheme.colorScheme.outline),
        leadingIcon = if (selected) {
            { Icon(painterResource(LLIcons.Check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = chosenChipColors(),
        modifier = modifier.heightIn(min = Spacing.minTouchTarget),
    )
}

/** A read-only field that opens a list of [options] (key to label). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    /** Shown in the box while nothing is picked; when disabled, why ("Choose a division first", FORM-01). */
    placeholder: String? = null,
    /** The label above the box, not on its border (always for the area pickers, N3; else with large text). */
    labelAbove: Boolean = isLargeText(),
    /** A heading at the top of the open list, e.g. "13 districts in Dhaka division". */
    menuTitle: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Column(modifier) {
        if (labelAbove) FieldLabelAbove(label, isError)
        ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                isError = isError && enabled,
                label = if (labelAbove) null else { { Text(label) } },
                placeholder = placeholder?.let { p -> { Text(p) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                // A locked box stays readable: its words say why ("Choose a division first", D's re-audit).
                colors = OutlinedTextFieldDefaults.colors(
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                    .semantics {
                        when {
                            !enabled && placeholder != null -> contentDescription = "$label, $placeholder"
                            labelAbove -> contentDescription = label
                        }
                    },
            )
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                menuTitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                }
                options.forEach { (key, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            // The same place again changes nothing (it used to clear the boxes below it).
                            if (key != selected) onSelect(key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * A text button whose text starts at the content edge, like the fields above it (UI-07: "Forgot
 * password?", "Read the Terms" sat 12 dp in). Still 48 dp tall. [icon] goes before the text (D23:
 * "✎ Edit profile").
 */
@Composable
fun EdgeTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(start = 0.dp, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
        modifier = modifier.heightIn(min = Spacing.minTouchTarget),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text)
    }
}

/** An error line under a group of controls, read out by TalkBack. Shows nothing when [show] is false. */
@Composable
fun FieldError(show: Boolean, text: String, modifier: Modifier = Modifier) {
    if (show) {
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.semantics {
                error(text)
                liveRegion = LiveRegionMode.Polite // FLOW-04: TalkBack hears it when it appears
            },
        )
    }
}
