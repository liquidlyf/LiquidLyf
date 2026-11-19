package io.github.liquidlyf.core.data.session

import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.push.SubscribedTopicsStore
import io.github.liquidlyf.core.data.push.SystemNotifications
import io.github.liquidlyf.core.data.push.TopicMessaging
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.Tiers
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAccountDeleter
import io.github.liquidlyf.testing.FakeAlertPrefsStore
import io.github.liquidlyf.testing.FakeAlertStore
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeReminders
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D23 a: a listing made before (an over-60 donor, or weight না from before D23) ends when the app
 * starts, and the alert topics go with it. B's daily job does the same on the server for phones
 * that aren't opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListingTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()
    private val subscribed = mutableSetOf<String>()
    private val messaging = object : TopicMessaging {
        override suspend fun subscribe(topic: String) {
            subscribed += topic
        }

        override suspend fun unsubscribe(topic: String) {
            subscribed -= topic
        }
    }
    private val store = object : SubscribedTopicsStore {
        var topics: Set<String> = emptySet()
        override suspend fun get() = topics
        override suspend fun set(topics: Set<String>) {
            this.topics = topics
        }
    }
    private val donorTopics = TestData.profile().let { p -> Tiers.donorTopics(p.bloodGroup!!, p.area.division, p.area.district, p.area.upazila) }
    private val notifications = object : SystemNotifications {
        override fun cancelAll() = Unit
    }

    private suspend fun TestScope.start(me: UserProfile): FakeProfileRepository {
        auth.accounts["me@example.com"] = FakeAuthRepository.Account("me", "secret-pw", "Me", verified = true)
        val profiles = FakeProfileRepository().apply { profiles["me"] = me }
        val clock = Clock { TestData.NOW }
        val push = PushTopicManager(messaging, store, EligibilityCalculator(), clock, FakeAlertPrefsStore(), auth)
        val session = AppSessionManager(auth, profiles, push, FakeAccountDeleter(), FakeAlertStore(), notifications, FakeReminders(), EligibilityCalculator(), clock, backgroundScope)
        auth.signInEmail("me@example.com", "secret-pw")
        runCurrent() // the session runs in backgroundScope, which advanceUntilIdle() leaves alone
        assertTrue(session.state.value.toString(), session.state.value is SessionState.Ready)
        return profiles
    }

    @Test
    fun `a listed donor past 60 is unlisted on start, and gets no area alerts`() = runTest(dispatcher) {
        val profiles = start(TestData.profile(uid = "me").copy(birthYear = 1965)) // 61 in 2026
        runCurrent()
        assertFalse(profiles.profiles.getValue("me").showInDonorSearch)
        assertEquals(listOf(false), profiles.listingWrites)
        assertTrue("no area topics: $subscribed", subscribed.none { it in donorTopics })
    }

    @Test
    fun `a listed donor with weight না from before is unlisted on start`() = runTest(dispatcher) {
        val profiles = start(TestData.profile(uid = "me").copy(weightOk = false))
        runCurrent()
        assertFalse(profiles.profiles.getValue("me").showInDonorSearch)
    }

    @Test
    fun `a listed donor of 60 stays listed`() = runTest(dispatcher) {
        val profiles = start(TestData.profile(uid = "me").copy(birthYear = 1966))
        runCurrent()
        assertTrue(profiles.profiles.getValue("me").showInDonorSearch)
        assertTrue(profiles.listingWrites.isEmpty())
        assertTrue(subscribed.containsAll(donorTopics))
    }
}
