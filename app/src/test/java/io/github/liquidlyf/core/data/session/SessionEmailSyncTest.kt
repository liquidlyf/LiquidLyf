package io.github.liquidlyf.core.data.session

import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.push.SubscribedTopicsStore
import io.github.liquidlyf.core.data.push.SystemNotifications
import io.github.liquidlyf.core.data.push.TopicMessaging
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D30: the profile's email copy was set once at setup, so after a change of email it kept the old
 * address. The session moves it to the signed-in email, once per account and address.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionEmailSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()
    private val messaging = object : TopicMessaging {
        override suspend fun subscribe(topic: String) = Unit
        override suspend fun unsubscribe(topic: String) = Unit
    }
    private val store = object : SubscribedTopicsStore {
        var topics: Set<String> = emptySet()
        override suspend fun get() = topics
        override suspend fun set(topics: Set<String>) {
            this.topics = topics
        }
    }
    private val notifications = object : SystemNotifications {
        override fun cancelAll() = Unit
    }

    private suspend fun TestScope.start(me: UserProfile, profiles: FakeProfileRepository = FakeProfileRepository()): AppSessionManager {
        auth.accounts["new@example.com"] = FakeAuthRepository.Account("me", "secret-pw", "Me", verified = true)
        profiles.profiles["me"] = me
        val clock = Clock { TestData.NOW }
        val push = PushTopicManager(messaging, store, EligibilityCalculator(), clock, FakeAlertPrefsStore(), auth)
        val session = AppSessionManager(auth, profiles, push, FakeAccountDeleter(), FakeAlertStore(), notifications, FakeReminders(), EligibilityCalculator(), clock, backgroundScope)
        auth.signInEmail("new@example.com", "secret-pw")
        runCurrent()
        return session
    }

    @Test
    fun `after a change of email the profile's copy follows the signed-in email`() = runTest(dispatcher) {
        val profiles = FakeProfileRepository()
        val session = start(TestData.profile(uid = "me").copy(email = "old@example.com"), profiles)
        runCurrent()
        assertEquals(listOf("new@example.com"), profiles.emailSyncs)
        val ready = session.state.value as SessionState.Ready
        assertEquals("new@example.com", ready.profile.email)
    }

    @Test
    fun `nothing is written when they already match`() = runTest(dispatcher) {
        val profiles = FakeProfileRepository()
        start(TestData.profile(uid = "me").copy(email = "new@example.com"), profiles)
        runCurrent()
        assertTrue(profiles.emailSyncs.isEmpty())
    }

    @Test
    fun `a refused write isn't retried in a loop`() = runTest(dispatcher) {
        val profiles = FakeProfileRepository()
        profiles.syncFailure = AppError.PermissionDenied
        val session = start(TestData.profile(uid = "me").copy(email = "old@example.com"), profiles)
        runCurrent()
        session.refresh()
        runCurrent()
        assertEquals(1, profiles.emailSyncAttempts)
        assertTrue(session.state.value is SessionState.Ready)
    }
}
