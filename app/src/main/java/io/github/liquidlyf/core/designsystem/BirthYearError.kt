package io.github.liquidlyf.core.designsystem

import androidx.annotation.StringRes
import io.github.liquidlyf.R
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.Validators
import java.time.Instant

/**
 * What's wrong with a typed year of birth, or null when it's fine: nothing typed, too early, or under
 * 18 each say so (D's part 2, Low 16: an empty box or 1880 both said "You must be 18 or older").
 */
@StringRes
fun birthYearErrorRes(text: String, now: Instant): Int? {
    val year = text.trim().toIntOrNull() ?: return R.string.error_birth_year_required
    if (Validators.isValidBirthYear(year, now)) return null
    return if (EligibilityCalculator.ageIn(year, now) > Validators.MAX_PROFILE_AGE) R.string.error_birth_year_check else R.string.error_birth_year
}
