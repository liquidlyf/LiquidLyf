package io.github.liquidlyf.core.domain

import io.github.liquidlyf.core.model.Gender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class EligibilityCalculatorTest {

    private val calc = EligibilityCalculator()
    private val now = Instant.parse("2026-11-13T06:00:00Z")
    private val adultBirthYear = 1996

    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private fun evaluate(
        birthYear: Int = adultBirthYear,
        weightOk: Boolean = true,
        gender: Gender = Gender.MALE,
        lastDonationAt: Instant? = null,
    ) = calc.evaluate(birthYear, weightOk, gender, lastDonationAt, now)

    @Test
    fun `never donated means eligible now, stored as 1970 not null`() {
        val e = evaluate(lastDonationAt = null)
        assertTrue(e.eligibleNow)
        assertEquals(Instant.EPOCH, e.eligibleFrom)
        assertEquals(EligibilityCalculator.ELIGIBLE_NOW, calc.eligibleFrom(null, Gender.FEMALE))
    }

    @Test
    fun `day 119 is still resting and day 120 is eligible`() {
        val day119 = evaluate(lastDonationAt = daysAgo(119))
        assertFalse(day119.eligibleNow)
        assertEquals(setOf(IneligibleReason.REST_PERIOD), day119.reasons)
        assertEquals(1, EligibilityCalculator.daysUntil(day119.eligibleFrom, now))

        val day120 = evaluate(lastDonationAt = daysAgo(120))
        assertTrue(day120.eligibleNow)
        assertEquals(now, day120.eligibleFrom)
        assertEquals(0, EligibilityCalculator.daysUntil(day120.eligibleFrom, now))
    }

    @Test
    fun `eligibleFrom is last donation plus 120 days`() {
        val donated = Instant.parse("2026-01-01T10:00:00Z")
        assertEquals(Instant.parse("2026-05-01T10:00:00Z"), calc.eligibleFrom(donated, Gender.MALE))
    }

    @Test
    fun `age 17 and 61 are out, 18 and 60 are in`() {
        val year = 2026
        assertEquals(setOf(IneligibleReason.TOO_YOUNG), evaluate(birthYear = year - 17).reasons)
        assertTrue(evaluate(birthYear = year - 18).eligibleNow)
        assertTrue(evaluate(birthYear = year - 60).eligibleNow)
        assertEquals(setOf(IneligibleReason.TOO_OLD), evaluate(birthYear = year - 61).reasons)
    }

    @Test
    fun `weight under 45 kg blocks donation`() {
        assertEquals(setOf(IneligibleReason.LOW_WEIGHT), evaluate(weightOk = false).reasons)
    }

    @Test
    fun `every reason is reported together`() {
        val e = evaluate(birthYear = 2026 - 17, weightOk = false, lastDonationAt = daysAgo(10))
        assertEquals(
            setOf(IneligibleReason.TOO_YOUNG, IneligibleReason.LOW_WEIGHT, IneligibleReason.REST_PERIOD),
            e.reasons,
        )
    }

    @Test
    fun `a longer interval for women comes from the rules`() {
        val rules = EligibilityRules(femaleWholeBloodIntervalDays = 180)
        val c = EligibilityCalculator(rules)
        val donated = daysAgo(150)
        assertFalse(c.evaluate(adultBirthYear, true, Gender.FEMALE, donated, now).eligibleNow)
        assertTrue(c.evaluate(adultBirthYear, true, Gender.MALE, donated, now).eligibleNow)
        assertTrue(c.evaluate(adultBirthYear, true, Gender.OTHER, donated, now).eligibleNow)
        assertEquals(30, EligibilityCalculator.daysUntil(c.eligibleFrom(donated, Gender.FEMALE), now))
    }

    @Test
    fun `an interval below the rules floor of 120 days is raised to 120`() {
        val rules = EligibilityRules(wholeBloodIntervalDays = 90, femaleWholeBloodIntervalDays = 60)
        assertEquals(120, rules.intervalDays(Gender.MALE))
        assertEquals(120, rules.intervalDays(Gender.FEMALE))
        assertFalse(EligibilityCalculator(rules).evaluate(adultBirthYear, true, Gender.MALE, daysAgo(100), now).eligibleNow)
    }

    @Test
    fun `days until rounds partial days up`() {
        assertEquals(1, EligibilityCalculator.daysUntil(now.plusSeconds(1), now))
        assertEquals(1, EligibilityCalculator.daysUntil(now.plus(Duration.ofDays(1)), now))
        assertEquals(2, EligibilityCalculator.daysUntil(now.plus(Duration.ofHours(25)), now))
        assertEquals(0, EligibilityCalculator.daysUntil(now.minusSeconds(1), now))
    }

    @Test
    fun `age uses the UTC year like the rules`() {
        // 31 Dec 20:00 UTC is already 1 Jan in Dhaka, but the rules use request.time.year() in UTC.
        val newYearsEve = Instant.parse("2026-12-31T20:00:00Z")
        assertEquals(18, EligibilityCalculator.ageIn(2008, newYearsEve))
    }


    @Test
    fun `listing needs weight yes and age 18 to 60, counted as the rules do (D23)`() {
        val calc = EligibilityCalculator()
        val now = Instant.parse("2026-11-19T06:00:00Z")
        assertEquals(null, calc.listingBlock(1966, true, now)) // 60
        assertEquals(IneligibleReason.TOO_OLD, calc.listingBlock(1965, true, now)) // 61
        assertEquals(IneligibleReason.LOW_WEIGHT, calc.listingBlock(1990, false, now))
        assertEquals(IneligibleReason.LOW_WEIGHT, calc.listingBlock(1965, false, now)) // the weight first: it can change
        assertEquals(IneligibleReason.TOO_YOUNG, calc.listingBlock(2009, true, now)) // 17
        // 61 from 1 January, in UTC, as the rules and B's daily job count it.
        assertEquals(null, calc.listingBlock(1966, true, Instant.parse("2026-12-31T23:59:59Z")))
        assertEquals(IneligibleReason.TOO_OLD, calc.listingBlock(1966, true, Instant.parse("2027-01-01T00:00:00Z")))
        // A Remote Config value can't raise it past the rules' 60.
        val looser = EligibilityCalculator(EligibilityRules(maxAge = 65))
        assertEquals(IneligibleReason.TOO_OLD, looser.listingBlock(1965, true, now))
        assertTrue(IneligibleReason.TOO_OLD in looser.evaluate(1965, true, Gender.MALE, null, now).reasons)
    }
}
