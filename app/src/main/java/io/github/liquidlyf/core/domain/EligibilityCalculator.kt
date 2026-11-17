package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.UserProfile
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Whole-blood donation rules (doc 07 §4). These are the defaults; Remote Config overrides them
 * (Phase 3). NEEDS MEDICAL-ADVISOR SIGN-OFF before launch.
 */
data class EligibilityRules(
    val wholeBloodIntervalDays: Int = MIN_INTERVAL_DAYS,
    /** Doc 07 §4 allows a longer interval for women (e.g. 180) through Remote Config. */
    val femaleWholeBloodIntervalDays: Int = MIN_INTERVAL_DAYS,
    val minAge: Int = 18,
    val maxAge: Int = 60,
) {
    /**
     * Rest period after a whole-blood donation. Never below [MIN_INTERVAL_DAYS]: firestore.rules
     * rejects a donor card whose `eligibleFrom` is earlier, so a bad Remote Config value can't
     * break profile saves.
     */
    fun intervalDays(gender: Gender?): Int =
        (if (gender == Gender.FEMALE) femaleWholeBloodIntervalDays else wholeBloodIntervalDays)
            .coerceAtLeast(MIN_INTERVAL_DAYS)

    companion object {
        /** The floor that firestore.rules checks (`donorMatches`). */
        const val MIN_INTERVAL_DAYS = 120
    }
}

/** Why someone can't donate right now. The UI shows one line per reason. */
enum class IneligibleReason { TOO_YOUNG, TOO_OLD, LOW_WEIGHT, REST_PERIOD }

data class Eligibility(
    /** When the rest period ends. [EligibilityCalculator.ELIGIBLE_NOW] if they never donated. */
    val eligibleFrom: Instant,
    val reasons: Set<IneligibleReason>,
) {
    val eligibleNow: Boolean get() = reasons.isEmpty()
}

/**
 * eligibleFrom = lastDonationAt + interval(gender);
 * eligibleNow = age in minAge..maxAge && weightOk && now >= eligibleFrom.
 *
 * Only the birth year is stored, so age is "this year minus birth year", in UTC like the rules.
 */
class EligibilityCalculator(private val rules: EligibilityRules = EligibilityRules()) {

    /** The donor card's `eligibleFrom`. Never null: [ELIGIBLE_NOW] means "no rest period". */
    fun eligibleFrom(lastDonationAt: Instant?, gender: Gender?): Instant =
        lastDonationAt?.plus(Duration.ofDays(rules.intervalDays(gender).toLong())) ?: ELIGIBLE_NOW

    fun evaluate(
        birthYear: Int,
        weightOk: Boolean,
        gender: Gender,
        lastDonationAt: Instant?,
        now: Instant,
    ): Eligibility {
        val from = eligibleFrom(lastDonationAt, gender)
        val age = ageIn(birthYear, now)
        val reasons = buildSet {
            if (age < rules.minAge) add(IneligibleReason.TOO_YOUNG)
            if (age > rules.maxAge) add(IneligibleReason.TOO_OLD)
            if (!weightOk) add(IneligibleReason.LOW_WEIGHT)
            if (now < from) add(IneligibleReason.REST_PERIOD)
        }
        return Eligibility(from, reasons)
    }

    /** A donor's eligibility; null for someone who hasn't answered the donor questions (D1). */
    fun evaluate(p: UserProfile, now: Instant): Eligibility? {
        if (!p.hasDonorDetails) return null
        return evaluate(p.birthYear ?: return null, p.weightOk ?: return null, p.gender ?: return null, p.lastDonationAt, now)
    }

    companion object {
        /** 1970-01-01: "eligible now". The rules and range queries need a timestamp, not null. */
        val ELIGIBLE_NOW: Instant = Instant.EPOCH

        fun ageIn(birthYear: Int, now: Instant): Int = now.atOffset(ZoneOffset.UTC).year - birthYear

        /** Whole days left until [eligibleFrom], rounded up ("Eligible in 3 days"); 0 if already eligible. */
        fun daysUntil(eligibleFrom: Instant, now: Instant): Long {
            if (!now.isBefore(eligibleFrom)) return 0
            val millis = Duration.between(now, eligibleFrom).toMillis()
            val day = Duration.ofDays(1).toMillis()
            return (millis + day - 1) / day
        }
    }
}
