package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.numberText
import io.github.liquidlyf.core.domain.Eligibility
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.EligibilityRules
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.Gender
import java.time.Duration
import java.time.Instant

/**
 * How much of the rest period has passed, full when eligible (Home and Profile). Decorative:
 * [eligibilityText] next to it says the same in words. [showDays]: during the rest period the
 * middle shows the days left instead of the drop (N1: "not full = the days until you can").
 * [dimmed]: grey, for a donor who switched "Ready to donate" off or isn't listed (N6).
 */
@Composable
fun EligibilityRing(eligibility: Eligibility, gender: Gender?, now: Instant, size: Dp = 48.dp, showDays: Boolean = false, dimmed: Boolean = false) {
    val total = Duration.ofDays(EligibilityRules().intervalDays(gender).toLong()).toMillis().toFloat()
    val left = Duration.between(now, eligibility.eligibleFrom).toMillis().coerceAtLeast(0).toFloat()
    val progress = if (IneligibleReason.REST_PERIOD in eligibility.reasons) (1f - left / total).coerceIn(0f, 1f) else 1f
    Box(Modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val tint = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(size),
            color = if (dimmed) MaterialTheme.colorScheme.outline else ProgressIndicatorDefaults.circularColor,
        )
        val resting = IneligibleReason.REST_PERIOD in eligibility.reasons
        if (showDays && resting) {
            val days = EligibilityCalculator.daysUntil(eligibility.eligibleFrom, now).toInt().coerceAtLeast(1)
            CapFontScale(MAX_LABEL_SCALE) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(numberText(days), style = MaterialTheme.typography.titleMedium, color = tint)
                    if (size >= 64.dp) {
                        Text(pluralStringResource(R.plurals.ring_days, days), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            Icon(painterResource(LLIcons.BloodDrop), contentDescription = null, tint = tint)
        }
    }
}

/** "You can donate now", "Eligible in 23 days", or why not. */
@Composable
fun eligibilityText(eligibility: Eligibility, now: Instant): String = when {
    eligibility.eligibleNow -> stringResource(R.string.home_eligible_now)
    IneligibleReason.REST_PERIOD in eligibility.reasons -> {
        val days = EligibilityCalculator.daysUntil(eligibility.eligibleFrom, now).toInt().coerceAtLeast(1)
        pluralStringResource(R.plurals.home_eligible_in, days, days)
    }
    IneligibleReason.LOW_WEIGHT in eligibility.reasons -> stringResource(R.string.detail_block_weight)
    else -> stringResource(R.string.detail_block_age)
}
