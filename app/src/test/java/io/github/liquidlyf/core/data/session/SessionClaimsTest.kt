package io.github.liquidlyf.core.data.session

import io.github.liquidlyf.core.data.auth.Claims
import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.push.SubscribedTopicsStore
import io.github.liquidlyf.core.data.push.SystemNotifications
import io.github.liquidlyf.core.data.push.TopicMessaging
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.navigation.GateZone
import io.github.liquidlyf.navigation.gateTarget
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

/** Phase 7: the `admin` and `banned` custom claims, read from the ID token, shape the session. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionClaimsTest {
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

    private suspend fun TestScope.signedInSession(): AppSessionManager {
        auth.accounts["me@example.com"] = FakeAuthRepository.Account("me", "secret-pw", "Me", verified = true)
        val profiles = FakeProfileRepository().apply { profiles["me"] = TestData.profile(uid = "me") }
        val push = PushTopicManager(messaging, store, EligibilityCalculator(), Clock { TestData.NOW }, FakeAlertPrefsStore())
        val session = AppSessionManager(auth, profiles, push, FakeAccountDeleter(), FakeAlertStore(), notifications, FakeReminders(), backgroundScope)
        auth.signInEmail("me@example.com", "secret-pw")
        runCurrent() // the session runs in backgroundScope
        return session
    }

    @Test
    fun `the admin claim marks the session, and a ban suspends it until lifted`() = runTest(dispatcher) {
        val session = signedInSession()
        assertFalse((session.state.value as SessionState.Ready).admin)

        auth.claims.value = Claims(uid = "me", admin = true)
        runCurrent()
        assertTrue((session.state.value as SessionState.Ready).admin)

        auth.claims.value = Claims(uid = "me", banned = true)
        runCurrent()
        val suspended = session.state.value
        assertTrue(suspended is SessionState.Suspended)
        assertEquals("the gate moves a suspended user out of the app", GateZone.SUSPENDED, gateTarget(suspended, GateZone.MAIN, false))

        auth.claims.value = Claims(uid = "me")
        runCurrent()
        assertTrue(session.state.value is SessionState.Ready)
    }

    @Test
    fun `claims for someone else never apply`() = runTest(dispatcher) {
        val session = signedInSession()
        auth.claims.value = Claims(uid = "previous-user", admin = true, banned = true)
        runCurrent()
        val ready = session.state.value as SessionState.Ready
        assertFalse(ready.admin)
    }
}
