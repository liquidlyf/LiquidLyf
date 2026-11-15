package io.github.liquidlyf.core.data.push

import io.github.liquidlyf.core.data.auth.Claims
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAlertPrefsStore
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTopicManagerTest {

    private val now = Instant.parse("2026-11-13T06:00:00Z")

    private class FakeMessaging : TopicMessaging {
        val subscribed = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        var failOn: String? = null

        override suspend fun subscribe(topic: String) {
            if (topic == failOn) throw IOException("offline")
            calls += "+$topic"
            subscribed += topic
        }

        override suspend fun unsubscribe(topic: String) {
            if (topic == failOn) throw IOException("offline")
            calls += "-$topic"
            subscribed -= topic
        }
    }

    private class MemoryStore : SubscribedTopicsStore {
        var topics: Set<String> = emptySet()
        override suspend fun get() = topics
        override suspend fun set(topics: Set<String>) {
            this.topics = topics
        }
    }

    private val fcm = FakeMessaging()
    private val store = MemoryStore()
    private val prefs = FakeAlertPrefsStore()
    private val auth = io.github.liquidlyf.testing.FakeAuthRepository()
    private val manager = PushTopicManager(fcm, store, EligibilityCalculator(), Clock { now }, prefs, auth)

    private val donor = UserProfile(
        uid = "u1", fullName = "Karim Uddin", email = "k@example.test", phone = "+8801712345678", phoneVerified = false,
        bloodGroup = BloodGroup.B_POS, gender = Gender.MALE, birthYear = 1995, weightOk = true,
        area = Area("BD30", "BD3026", "BD30262000"), available = true, lastDonationAt = null, showInDonorSearch = true,
        consentAt = now, locale = AppLocale.BN, termsVersion = "1", termsAcceptedAt = now, createdAt = now,
        updatedAt = now, role = null, hiddenRequestIds = emptyList(), blockedUids = emptyList(), inboxSeenAt = null,
        lastRequestAt = null, lastActiveAt = now, donationCount = 0, verifiedDonations = 0, lastDonationId = null,
    )
    private val areaTopics = setOf("up_BD30262000_B_POS", "di_BD3026_B_POS", "dv_BD30_B_POS")

    @Test
    fun `a listed, available, eligible donor gets the personal and three area topics`() {
        assertEquals(setOf("usr_u1") + areaTopics, manager.desiredTopics(donor))
    }

    @Test
    fun `unlisted, unavailable, resting or under-age donors get only the personal topic`() {
        listOf(
            donor.copy(showInDonorSearch = false),
            donor.copy(available = false),
            donor.copy(lastDonationAt = now.minus(Duration.ofDays(30))),
            donor.copy(birthYear = 2010),
            donor.copy(weightOk = false),
        ).forEach { assertEquals(it.toString(), setOf("usr_u1"), manager.desiredTopics(it)) }
        assertEquals(emptySet<String>(), manager.desiredTopics(null))
    }

    @Test
    fun `an admin also gets the admins topic, whatever the alert settings say (7_3)`() = runTest {
        assertEquals(setOf("usr_u1", "admins"), manager.desiredTopics(donor.copy(available = false), admin = true))

        auth.claims.value = Claims(uid = "u1", admin = true)
        manager.sync(donor)
        assertTrue("admins" in fcm.subscribed)
        // Just after a launch the claims aren't read yet: the topic stays, no unsubscribe-resubscribe.
        auth.claims.value = Claims()
        fcm.calls.clear()
        manager.sync(donor)
        assertTrue("admins" in fcm.subscribed)
        assertEquals(emptyList<String>(), fcm.calls)
        auth.claims.value = Claims(uid = "u1", admin = true)
        // The claim is taken away: the next sync drops the topic.
        auth.claims.value = Claims(uid = "u1")
        manager.sync(donor)
        assertTrue("admins" !in fcm.subscribed)
        // A claim read for someone else (the person before, on this phone) doesn't count.
        auth.claims.value = Claims(uid = "someone-else", admin = true)
        manager.sync(donor)
        assertTrue("admins" !in fcm.subscribed)
        // Signed out: nothing at all.
        manager.sync(null)
        assertEquals(emptySet<String>(), fcm.subscribed)
    }

    @Test
    fun `without a blood group only the personal topic is wanted (P1)`() {
        assertEquals(setOf("usr_u1"), manager.desiredTopics(donor.copy(bloodGroup = null)))
    }

    @Test
    fun `sync subscribes what's missing and unsubscribes what's no longer wanted`() = runTest {
        assertEquals(AppResult.Success(Unit), manager.sync(donor))
        assertEquals(setOf("usr_u1") + areaTopics, fcm.subscribed)
        assertEquals(fcm.subscribed, store.topics)

        // Toggling unavailable drops only the area topics.
        fcm.calls.clear()
        manager.sync(donor.copy(available = false))
        assertEquals(areaTopics.map { "-$it" }.toSet(), fcm.calls.toSet())
        assertEquals(setOf("usr_u1"), store.topics)
    }

    @Test
    fun `moving to another area swaps the area topics`() = runTest {
        manager.sync(donor)
        fcm.calls.clear()
        manager.sync(donor.copy(area = Area("BD30", "BD3026", "BD30262500")))
        assertEquals(setOf("-up_BD30262000_B_POS", "+up_BD30262500_B_POS"), fcm.calls.toSet())
    }

    @Test
    fun `logout unsubscribes everything`() = runTest {
        manager.sync(donor)
        manager.sync(null)
        assertEquals(emptySet<String>(), fcm.subscribed)
        assertEquals(emptySet<String>(), store.topics)
    }

    @Test
    fun `force re-subscribes every wanted topic, plain sync does nothing new`() = runTest {
        manager.sync(donor)
        fcm.calls.clear()
        manager.sync(donor)
        assertTrue(fcm.calls.isEmpty())
        manager.sync(donor, force = true)
        assertEquals(4, fcm.calls.count { it.startsWith("+") })
    }

    @Test
    fun `a failed call is retried by the next sync`() = runTest {
        fcm.failOn = "dv_BD30_B_POS"
        assertTrue(manager.sync(donor) is AppResult.Failure)
        assertTrue("dv_BD30_B_POS" !in store.topics)
        fcm.failOn = null
        fcm.calls.clear()
        manager.sync(donor)
        assertEquals(listOf("+dv_BD30_B_POS"), fcm.calls)
    }

    @Test
    fun `the next change is the end of the rest period`() {
        val donated = now.minus(Duration.ofDays(100))
        assertEquals(donated.plus(Duration.ofDays(120)), manager.nextChangeAt(donor.copy(lastDonationAt = donated)))
        assertNull(manager.nextChangeAt(donor))
        assertNull(manager.nextChangeAt(donor.copy(lastDonationAt = donated, available = false)))
        assertNull(manager.nextChangeAt(null))
    }

    @Test
    fun `the phone's notification settings take topics away`() = runTest {
        manager.sync(donor)
        assertEquals(4, store.topics.size)

        // Request alerts off: the area topics go, personal updates stay.
        prefs.set(AlertPrefs(requestAlerts = false))
        manager.sync(donor)
        assertEquals(setOf("usr_u1"), store.topics)

        // Updates off too: nothing at all.
        prefs.set(AlertPrefs(requestAlerts = false, updates = false))
        manager.sync(donor)
        assertTrue(store.topics.isEmpty())

        // Both back on.
        prefs.set(AlertPrefs())
        manager.sync(donor)
        assertEquals(4, store.topics.size)
    }
}
