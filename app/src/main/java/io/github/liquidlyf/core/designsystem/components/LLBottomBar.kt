package io.github.liquidlyf.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.screenGutter

/**
 * The bar pinned under a screen with its main action (UI-05: one main-button layout everywhere).
 * The main action is full width, up to 480 dp; an optional second one (Back) sits before it. The
 * bar rides above the keyboard. With large text the two buttons stack, the main one on top
 * (A11Y-03: at 200% "Post request" was squeezed into a 3-line oval next to Back).
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
                if (stacked) {
                    Column(Modifier.widthIn(max = MAX_ACTION_WIDTH).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        primary(Modifier.fillMaxWidth())
                        secondary(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        Modifier.widthIn(max = MAX_ACTION_WIDTH).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        secondary(Modifier)
                        primary(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The widest a main action gets, on tablets and in landscape too (UI-05). */
val MAX_ACTION_WIDTH = 480.dp
