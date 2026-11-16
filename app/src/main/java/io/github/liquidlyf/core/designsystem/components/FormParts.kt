package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

/** A row of mutually exclusive answers. None is selected until the user picks (no defaults). */
@Composable
fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedChoices(options, options.firstOrNull { it.first == selected }, { onSelect(it.first) }, modifier) { it.second }
}

/**
 * Segmented buttons that keep every word whole (8.5). Each segment is at least as wide as its
 * longest word, a label wraps only between words, and all segments take the tallest one's height,
 * so the outline stays one pill. When they can't fit side by side (Bangla at 200% text), the row
 * scrolls sideways instead of breaking "কাছাকাছি" in two.
 */
@Composable
fun <T> SegmentedChoices(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> String,
) {
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
                    modifier = Modifier.fillMaxHeight(),
                ) { Text(label(option), textAlign = TextAlign.Center) }
            }
        }
    }
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
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second.orEmpty()
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
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
            modifier = modifier.semantics { error(text) },
        )
    }
}
