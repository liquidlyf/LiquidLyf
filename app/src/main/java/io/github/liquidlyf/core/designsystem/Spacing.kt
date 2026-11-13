package io.github.liquidlyf.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale (doc 04 §3): 4 · 8 · 12 · 16 · 24 · 32 · 48. */
@Immutable
object Spacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp

    /** Smallest touch target (WCAG / Material). */
    val minTouchTarget: Dp = 48.dp
}

/** Width from which the layout switches to its large-screen form (tablets, unfolded foldables). */
val ExpandedLayoutMinWidth: Dp = 600.dp

/** Current window width. */
@Composable
fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** Screen side gutter: 16dp on phones, 24dp from 600dp wide. */
@Composable
fun screenGutter(): Dp = if (windowWidth() >= ExpandedLayoutMinWidth) Spacing.lg else Spacing.md
