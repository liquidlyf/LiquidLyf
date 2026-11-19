package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing

/*
 * Large text as a first-class layout (D4 a, rule 10; A11Y-01…04). Most text simply grows with the
 * phone's font size and wraps. A few parts have a shape that can't grow with it (a pill, a tab
 * label, a top bar): their text grows up to a cap, then the part changes layout or shrinks to fit.
 */

/** True from Android's 130% font size up: option rows become lists, bottom bars stack, and so on. */
@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

/**
 * Lets the text in [content] follow the phone's font size only up to [max] (e.g. 1.5x), for parts
 * whose shape can't grow with it. TalkBack still reads the same words.
 */
@Composable
fun CapFontScale(max: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}

/** How far a pill's or a tab's label grows with large text (A11Y-01, A11Y-03). */
const val MAX_LABEL_SCALE = 1.5f

/**
 * A field's label above its box, used with large text instead of Material's label on the box's
 * border (A11Y-03, FORM-01: at 200% a two-line label like "Notes for donors (optional)" rose out
 * of the box and over the field above it). TalkBack hears it on the field itself, so this copy is
 * hidden from it. It turns red or (D24) green with its box.
 */
@Composable
internal fun FieldLabelAbove(label: String, isError: Boolean = false, isOk: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            isError -> MaterialTheme.colorScheme.error
            isOk -> LiquidLyfTheme.extendedColors.success
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .padding(bottom = Spacing.xxs)
            .clearAndSetSemantics { },
    )
}
