package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.Gender
import java.time.Duration
import java.time.Instant

/** Phase 6 badges, shown on the profile in this order. */
enum class Badge {
    /** 1 donation. */
    FIRST_DROP,

    /** 3 donations. */
    LIFE_SAVER,

    /** 5 donations. */
    HERO,

    /** 10 donations. */
    LEGEND,

    /** Offered within 15 minutes of a request being posted. */
    RAPID_RESPONDER,

    /** Donated in each of the last 3 eligible windows (see [BadgeCalculator.isRegular]). */
    REGULAR,
}

/** The profile's impact numbers. */
data class Impact(
    val donations: Int,
    val verifiedDonations: Int,
    /** "Up to N lives helped": one whole-blood unit can help up to 3 people. */
    val livesHelped: Int,
    /** Distinct requests this donor gave blood for through the app. */
    val requestsHelped: Int,
)

/**
 * Badges and impact from the donation history (doc 09 phase 6, 6.5). Pure, so the profile can
 * recompute them on every change at no cost. Self-reported donations count too: badges are
 * encouragement, not proof; the "verified" tag on each history row is the proof.
 */
class BadgeCalculator(private val rules: EligibilityRules = EligibilityRules()) {

    fun badges(donations: List<Donation>, gender: Gender, rapidResponder: Boolean, now: Instant): List<Badge> {
        val count = donations.size
        return buildList {
            COUNT_BADGES.forEach { (badge, needed) -> if (count >= needed) add(badge) }
            if (rapidResponder) add(Badge.RAPID_RESPONDER)
            if (isRegular(donations, gender, now)) add(Badge.REGULAR)
        }
    }

    fun impact(donations: List<Donation>): Impact = Impact(
        donations = donations.size,
        verifiedDonations = donations.count { it.verified },
        livesHelped = donations.size * LIVES_PER_DONATION,
        requestsHelped = donations.mapNotNull { it.requestId }.distinct().size,
    )

    /**
     * A donor becomes eligible again one rest period after donating, and that eligible window lasts
     * one more rest period. "Regular" = the last 3 donations each fell inside the window opened by
     * the one before (gap < 2 rest periods), and the window opened by the latest isn't over yet.
     */
    fun isRegular(donations: List<Donation>, gender: Gender, now: Instant): Boolean {
        if (donations.size < REGULAR_DONATIONS) return false
        val window = Duration.ofDays(2L * rules.intervalDays(gender))
        val latest = donations.map { it.donatedAt }.sortedDescending().take(REGULAR_DONATIONS)
        val gapsOk = latest.zipWithNext().all { (newer, older) -> Duration.between(older, newer) < window }
        return gapsOk && Duration.between(latest.first(), now) < window
    }

    companion object {
        const val LIVES_PER_DONATION = 3
        const val REGULAR_DONATIONS = 3
        private val COUNT_BADGES = listOf(Badge.FIRST_DROP to 1, Badge.LIFE_SAVER to 3, Badge.HERO to 5, Badge.LEGEND to 10)
    }
}
