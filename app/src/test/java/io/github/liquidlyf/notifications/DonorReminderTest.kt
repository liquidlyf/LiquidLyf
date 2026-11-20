package io.github.liquidlyf.notifications

import io.github.liquidlyf.core.data.push.AlertPrefs
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.profilesetup.SetupDraft
import io.github.liquidlyf.testing.FakeAlertPrefsStore
import io.github.liquidlyf.testing.FakeReminders
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeSetupDraftStore
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** D29: when the "Want to donate too?" reminders come, and who gets them. */
class DonorReminderTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")
    private val plan = DonorReminderPlan(EligibilityCalculator())
    private val on = AlertPrefs()

    /** Someone who only asked for blood: no donor answers, not listed. NOW is Friday 13 Nov, 12:00 in Dhaka. */
    private val asker = TestData.profile(uid = "me").copy(
        bloodGroup = null, gender = null, birthYear = null, weightOk = null, available = false, showInDonorSearch = false, consentAt = null,
    )

    private fun dhaka(text: String): Instant = java.time.LocalDateTime.parse(text).atZone(dhaka).toInstant()

    @Test
    fun `7 Fridays at 10, the first 7 or more days after setup, weekly 4 then every 4 weeks`() {
        val slots = plan.slots(TestData.NOW, dhaka)
        assertEquals(
            listOf("2026-11-27T10:00", "2026-12-04T10:00", "2026-12-11T10:00", "2026-12-18T10:00", "2027-01-15T10:00", "2027-02-12T10:00", "2027-03-12T10:00")
                .map(::dhaka),
            slots,
        )
        assertTrue(slots.all { it.atZone(dhaka).dayOfWeek == DayOfWeek.FRIDAY })
    }

    @Test
    fun `set up before 10 on a Friday, the first is that time a week later`() {
        assertEquals(dhaka("2026-11-20T10:00"), plan.slots(dhaka("2026-11-13T09:00"), dhaka).first())
        // A Saturday setup: not the Friday 6 days later, the one after it.
        assertEquals(dhaka("2026-11-27T10:00"), plan.slots(dhaka("2026-11-14T08:00"), dhaka).first())
    }

    @Test
    fun `only someone who isn't a donor and could be one`() {
        assertTrue(plan.wanted(asker, null, on, TestData.NOW))
        // A donor, listed or not ("Not now" at "list me" is a choice too).
        assertFalse(plan.wanted(TestData.profile(uid = "me"), null, on, TestData.NOW))
        assertFalse(plan.wanted(TestData.profile(uid = "me").copy(showInDonorSearch = false, available = false), null, on, TestData.NOW))
        // The switch, or "Don't remind me".
        assertFalse(plan.wanted(asker, null, AlertPrefs(donorReminder = false), TestData.NOW))
        // D28's answers kept on this phone that rule donating out.
        assertFalse(plan.wanted(asker, SetupDraft(uid = "me", weightOk = false), on, TestData.NOW))
        assertFalse(plan.wanted(asker, SetupDraft(uid = "me", birthYear = "1960"), on, TestData.NOW))
        assertFalse(plan.wanted(asker, SetupDraft(uid = "me", birthYear = "2010"), on, TestData.NOW))
        // A year still being typed counts for nothing; a fine one keeps the reminders.
        assertTrue(plan.wanted(asker, SetupDraft(uid = "me", birthYear = "19"), on, TestData.NOW))
        assertTrue(plan.wanted(asker, SetupDraft(uid = "me", birthYear = "1998", weightOk = true, bloodGroup = "B_POS"), on, TestData.NOW))
    }

    @Test
    fun `only the times still to come, and none for an old account or without a setup time`() {
        val later = dhaka("2026-12-05T09:00")
        assertEquals(5, plan.upcoming(asker, null, on, later, dhaka).size)
        assertEquals(dhaka("2026-12-11T10:00"), plan.upcoming(asker, null, on, later, dhaka).first())
        assertTrue(plan.upcoming(asker, null, on, TestData.NOW.plus(Duration.ofDays(130)), dhaka).isEmpty())
        assertTrue(plan.upcoming(asker.copy(createdAt = null), null, on, TestData.NOW, dhaka).isEmpty())
    }

    @Test
    fun `the scheduler sets them while wanted and cancels them all when not`() = runTest {
        val reminders = FakeReminders()
        val prefs = FakeAlertPrefsStore()
        val drafts = FakeSetupDraftStore()
        val scheduler = DonorReminderScheduler(FakeSessionManager(), prefs, drafts, reminders, plan, Clock { TestData.NOW }, backgroundScope)
        val ready = SessionState.Ready(TestData.user(uid = "me"), asker)

        scheduler.update(ready, on, dhaka)
        assertEquals(plan.slots(TestData.NOW, dhaka), reminders.donorSlots)

        // They became a donor.
        scheduler.update(SessionState.Ready(TestData.user(uid = "me"), TestData.profile(uid = "me")), on, dhaka)
        assertTrue(reminders.donorSlots.isEmpty())

        // Back to wanted, then the switch goes off.
        scheduler.update(ready, on, dhaka)
        assertEquals(7, reminders.donorSlots.size)
        scheduler.update(ready, AlertPrefs(donorReminder = false), dhaka)
        assertTrue(reminders.donorSlots.isEmpty())

        // Weight না kept on this phone (D28's Skip).
        drafts.draft = SetupDraft(uid = "me", weightOk = false)
        scheduler.update(ready, on, dhaka)
        assertTrue(reminders.donorSlots.isEmpty())

        // Signed out.
        drafts.draft = null
        scheduler.update(ready, on, dhaka)
        scheduler.update(SessionState.SignedOut, on, dhaka)
        assertTrue(reminders.donorSlots.isEmpty())
    }
}
