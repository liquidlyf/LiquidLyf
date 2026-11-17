package io.github.liquidlyf.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * What a pill or tag means, and so its colour (COLOR-01, UI/UX review 17 Nov 2026). One colour
 * per meaning, the same everywhere; each pill also has its own icon and a word, so colour is never
 * the only signal (doc 04 §3). Red is for Critical only.
 */
enum class StatusTone {
    /** Critical urgency: needed within hours. Red (error). */
    Critical,

    /** Urgent: needed soon, or needs a look (a flag in moderation). Amber (warning). */
    Urgent,

    /** Normal, past or stopped: Planned, Expired, Cancelled, Withdrawn, Declined, tags. */
    Neutral,

    /** Done: Found blood, Donated, verified. Green (success). */
    Success,

    /** On its way: Offered, Confirmed. Neutral grey, told apart by its icon. */
    Progress,
}

@Immutable
data class ToneColors(val container: Color, val content: Color)

/** Light and dark contrast of each pair is 6.5:1 or more (Color.kt, D's COLOR-01 table). */
@Composable
@ReadOnlyComposable
fun StatusTone.colors(): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val extended = LiquidLyfTheme.extendedColors
    return when (this) {
        StatusTone.Critical -> ToneColors(scheme.error, scheme.onError)
        StatusTone.Urgent -> ToneColors(extended.warningContainer, extended.onWarningContainer)
        StatusTone.Neutral -> ToneColors(scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
        StatusTone.Success -> ToneColors(extended.successContainer, extended.onSuccessContainer)
        StatusTone.Progress -> ToneColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
}
