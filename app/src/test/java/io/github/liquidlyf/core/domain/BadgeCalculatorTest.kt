package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.core.model.Gender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class BadgeCalculatorTest {
    private val now = Instant.parse("2026-11-15T06:00:00Z")
    private val calc = BadgeCalculator()

    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private fun donation(daysAgo: Long, requestId: String? = null) = Donation(
        id = requestId ?: "self-$daysAgo",
        donatedAt = daysAgo(daysAgo),
        component = BloodComponent.WHOLE_BLOOD,
        hospitalName = null,
        requestId = requestId,
        verified = requestId != null,
        source = if (requestId != null) DonationSource.REQUEST else DonationSource.SELF,
        createdAt = daysAgo(daysAgo),
    )

    /** One donation every [gapDays], the latest [latestDaysAgo] days ago. */
    private fun every(gapDays: Long, count: Int, latestDaysAgo: Long = 10) =
        (0 until count).map { donation(latestDaysAgo + it * gapDays) }

    @Test
    fun `no donations, no badges`() {
        assertEquals(emptyList<Badge>(), calc.badges(emptyList(), Gender.MALE, rapidResponder = false, now))
    }

    @Test
    fun `the count badges follow the number of donations`() {
        // Spread wide (400 days apart) so "Regular" stays out of it.
        fun countBadges(n: Int) = calc.badges(every(400, n), Gender.MALE, false, now)
        assertEquals(listOf(Badge.FIRST_DROP), countBadges(1))
        assertEquals(listOf(Badge.FIRST_DROP), countBadges(2))
        assertEquals(listOf(Badge.FIRST_DROP, Badge.LIFE_SAVER), countBadges(3))
        assertEquals(listOf(Badge.FIRST_DROP, Badge.LIFE_SAVER, Badge.HERO), countBadges(5))
        assertEquals(listOf(Badge.FIRST_DROP, Badge.LIFE_SAVER, Badge.HERO, Badge.LEGEND), countBadges(10))
    }

    @Test
    fun `rapid responder comes from the input`() {
        assertEquals(listOf(Badge.RAPID_RESPONDER), calc.badges(emptyList(), Gender.MALE, rapidResponder = true, now))
    }

    @Test
    fun `regular means donating in each of the last 3 eligible windows`() {
        // Rest period 120 days, so each window ends 240 days after the donation before.
        assertTrue(calc.isRegular(every(130, 3), Gender.MALE, now))
        assertTrue(calc.isRegular(every(239, 3), Gender.MALE, now))
        assertFalse("a window was missed", calc.isRegular(every(240, 3), Gender.MALE, now))
        assertFalse("only 2 donations", calc.isRegular(every(130, 2), Gender.MALE, now))
        assertFalse("the current window is over", calc.isRegular(every(130, 3, latestDaysAgo = 240), Gender.MALE, now))
        // Older gaps don't matter, only the last 3 donations.
        val withOldGap = every(130, 3) + donation(10 + 2 * 130 + 1000)
        assertTrue(calc.isRegular(withOldGap, Gender.MALE, now))
        // Order of the list doesn't matter.
        assertTrue(calc.isRegular(every(130, 3).reversed(), Gender.MALE, now))
    }

    @Test
    fun `a longer rest period for women widens their window`() {
        val calc180 = BadgeCalculator(EligibilityRules(femaleWholeBloodIntervalDays = 180))
        assertTrue(calc180.isRegular(every(300, 3), Gender.FEMALE, now))
        assertFalse(calc180.isRegular(every(300, 3), Gender.MALE, now))
    }

    @Test
    fun `impact counts lives and distinct requests`() {
        val history = listOf(donation(10, "r1"), donation(200, "r2"), donation(400), donation(600, "r1"))
        assertEquals(Impact(donations = 4, verifiedDonations = 3, livesHelped = 12, requestsHelped = 2), calc.impact(history))
        assertEquals(Impact(0, 0, 0, 0), calc.impact(emptyList()))
    }
}
