package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import io.github.liquidlyf.core.domain.Eligibility
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.EligibilityRules
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.Gender
import java.time.Duration
import java.time.Instant

/**
 * How much of the rest period has passed, full when eligible (Home and Profile). Decorative:
 * [eligibilityText] next to it says the same in words.
 */
@Composable
fun EligibilityRing(eligibility: Eligibility, gender: Gender, now: Instant, size: Dp = 48.dp) {
    val total = Duration.ofDays(EligibilityRules().intervalDays(gender).toLong()).toMillis().toFloat()
    val left = Duration.between(now, eligibility.eligibleFrom).toMillis().coerceAtLeast(0).toFloat()
    val progress = if (IneligibleReason.REST_PERIOD in eligibility.reasons) (1f - left / total).coerceIn(0f, 1f) else 1f
    Box(Modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(size))
        Icon(painterResource(LLIcons.BloodDrop), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
