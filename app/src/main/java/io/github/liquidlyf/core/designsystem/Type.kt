package io.github.liquidlyf.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Type (doc 04 §3): the Material 3 type scale on the system font (Roboto). Bangla text
 * falls back to the system's Noto Sans Bengali. No bundled font in v1.
 * All sizes are in sp, so text follows the phone's font-size setting (test at 2.0x).
 */
internal val LiquidLyfTypography = Typography()

@Immutable
data class ExtendedTypography(
    /** Blood group in a badge or chip, e.g. "AB+". 18/24 bold. */
    val bloodGroupBadge: TextStyle,
    /** Blood group as the main element of a screen. 40/48 bold. */
    val bloodGroupHero: TextStyle,
)

internal val LiquidLyfExtendedTypography = ExtendedTypography(
    bloodGroupBadge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp),
    bloodGroupHero = TextStyle(fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp),
)

val LocalExtendedTypography = staticCompositionLocalOf { LiquidLyfExtendedTypography }
