package io.github.liquidlyf.core.data.session

import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.push.SubscribedTopicsStore
import io.github.liquidlyf.core.data.push.SystemNotifications
import io.github.liquidlyf.core.data.push.TopicMessaging
import io.github.liquidlyf.core.domain.EligibilityCalculator
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A16 (15 Nov 2026): the app crashed right after signing out. Whatever happens after the sign-out
 * itself, this person's topics, alerts, notifications and reminders must already be gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSignOutTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()
    private val signedIn get() = auth.authState.value != null

    /** What happened, and whether the account was still signed in at that moment. */
    private val steps = mutableListOf<Pair<String, Boolean>>()
    private val subscribed = mutableSetOf<String>()
    private val messaging = object : TopicMessaging {
        override suspend fun subscribe(topic: String) {
            subscribed += topic
        }

        override suspend fun unsubscribe(topic: String) {
            subscribed -= topic
            steps += "unsubscribe ${topic.substringBefore('_')}" to signedIn
        }
    }
    private val store = object : SubscribedTopicsStore {
        var topics: Set<String> = emptySet()
        override suspend fun get() = topics
        override suspend fun set(topics: Set<String>) {
            this.topics = topics
        }
    }
    private val notifications = object : SystemNotifications {
        override fun cancelAll() {
            steps += "notifications" to signedIn
        }
    }
    private val alerts = FakeAlertStore()
    private val reminders = FakeReminders()

    @Test
    fun `sign-out drops the topics and clears alerts, notifications and reminders before the account signs out`() = runTest(dispatcher) {
        val me = TestData.profile(uid = "me") // listed, available, eligible: 4 topics
        auth.accounts["me@example.com"] = FakeAuthRepository.Account("me", "secret-pw", "Me", verified = true)
        val profiles = FakeProfileRepository().apply { profiles["me"] = me }
        val push = PushTopicManager(messaging, store, EligibilityCalculator(), Clock { TestData.NOW }, FakeAlertPrefsStore())
        val session = AppSessionManager(auth, profiles, push, FakeAccountDeleter(), alerts, notifications, reminders, backgroundScope)
        auth.signInEmail("me@example.com", "secret-pw")
        runCurrent() // the session runs in backgroundScope, which advanceUntilIdle() leaves alone
        assertTrue(session.state.value.toString(), session.state.value is SessionState.Ready)
        assertEquals(4, subscribed.size)

        session.signOut()
        runCurrent() // the session runs in backgroundScope, which advanceUntilIdle() leaves alone

        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(emptySet<String>(), subscribed)
        assertEquals(listOf("unsubscribe dv", "unsubscribe di", "unsubscribe up", "unsubscribe usr", "notifications").sorted(), steps.map { it.first }.distinct().sorted())
        // Each step ran while still signed in (the first clear-up, from signOut() itself).
        assertTrue(steps.toString(), steps.filter { it.first.startsWith("unsubscribe") }.all { it.second })
        assertTrue(steps.toString(), steps.first { it.first == "notifications" }.second)
        assertTrue(alerts.cleared >= 1)
        assertTrue(reminders.cancelledAll >= 1)
    }
}
