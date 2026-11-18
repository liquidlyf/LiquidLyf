package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.screenGutter

/**
 * The bar pinned under a screen with its main action (UI-05: one main-button layout everywhere).
 * The main action is full width, up to 480 dp; an optional second one (Back) sits before it. The
 * bar rides above the keyboard. The two buttons stack, the main one on top, with large text
 * (A11Y-03: at 200% "Post request" was squeezed into a 3-line oval next to Back) and whenever both
 * labels don't fit side by side on one line (P2-7: in Bangla "Call Karim H." took 3 lines next to Copy).
 */
@Composable
fun LLBottomBar(
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String? = null,
    @DrawableRes primaryIcon: Int? = null,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    secondaryEnabled: Boolean = true,
    @DrawableRes secondaryIcon: Int? = null,
) {
    val stacked = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    Surface(color = LiquidLyfTheme.extendedColors.card, modifier = modifier) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = screenGutter(), vertical = Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                val primary = @Composable { m: Modifier ->
                    LLPrimaryButton(
                        text = primaryText,
                        onClick = onPrimary,
                        enabled = primaryEnabled,
                        loading = loading,
                        loadingText = loadingText,
                        icon = primaryIcon,
                        modifier = m,
                    )
                }
                val secondary = @Composable { m: Modifier ->
                    if (secondaryText != null) {
                        OutlinedButton(
                            onClick = onSecondary,
                            enabled = secondaryEnabled && !loading,
                            modifier = m.heightIn(min = Spacing.minTouchTarget),
                        ) {
                            secondaryIcon?.let {
                                Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            }
                            Text(secondaryText)
                        }
                    }
                }
                BarButtons(stackAlways = stacked, secondary = { secondary(Modifier) }, primary = { primary(Modifier) })
            }
        }
    }
}

/**
 * The second button (as wide as its label) then the main one (the rest of the row), or, when
 * [stackAlways] or the two labels don't fit on one line side by side, the main one on top and the
 * second under it, both full width.
 */
@Composable
private fun BarButtons(stackAlways: Boolean, secondary: @Composable () -> Unit, primary: @Composable () -> Unit) {
    Layout(contents = listOf(secondary, primary), modifier = Modifier.widthIn(max = MAX_ACTION_WIDTH).fillMaxWidth()) { (seconds, primaries), constraints ->
        val width = constraints.maxWidth
        val second = seconds.firstOrNull()
        val main = primaries.first()
        val rowGap = Spacing.sm.roundToPx()
        val fits = second == null ||
            second.maxIntrinsicWidth(Constraints.Infinity) + rowGap + main.maxIntrinsicWidth(Constraints.Infinity) <= width
        if (second != null && (stackAlways || !fits)) {
            val m = main.measure(Constraints.fixedWidth(width))
            val s = second.measure(Constraints.fixedWidth(width))
            val gap = Spacing.xs.roundToPx()
            layout(width, m.height + gap + s.height) {
                m.place(0, 0)
                s.place(0, m.height + gap)
            }
        } else {
            val s = second?.measure(Constraints(maxWidth = width))
            val mainWidth = width - (s?.let { it.width + rowGap } ?: 0)
            val m = main.measure(Constraints.fixedWidth(mainWidth))
            val height = maxOf(m.height, s?.height ?: 0)
            layout(width, height) {
                s?.place(0, (height - s.height) / 2)
                m.place(width - mainWidth, (height - m.height) / 2)
            }
        }
    }
}

/** The widest a main action gets, on tablets and in landscape too (UI-05). */
val MAX_ACTION_WIDTH = 480.dp
