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
 *
 * TYPE-01 (UI/UX review, 17 Nov 2026):
 * - titles and headlines at Medium (500), so a card's title stands out from its body text
 *   (titleMedium and titleSmall already are);
 * - body text and the pill label at 1.5x line height or more, so Bangla's marks above and
 *   below the letters aren't clipped, inside pills especially (they clip to their shape).
 */
private val Base = Typography()

internal val LiquidLyfTypography = Typography(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Medium),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Medium),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 21.sp),
    bodySmall = Base.bodySmall.copy(lineHeight = 18.sp),
    labelMedium = Base.labelMedium.copy(lineHeight = 18.sp),
)

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
