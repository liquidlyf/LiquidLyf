package io.github.liquidlyf.feature.settings

import io.github.liquidlyf.core.data.push.AlertPrefs
import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.push.SubscribedTopicsStore
import io.github.liquidlyf.core.data.push.TopicMessaging
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAlertPrefsStore
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationPrefsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val me = TestData.profile(uid = "me") // a listed, available, eligible donor
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), me))
    private val prefs = FakeAlertPrefsStore()
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
    private val push = PushTopicManager(messaging, store, EligibilityCalculator(), Clock { TestData.NOW }, prefs, io.github.liquidlyf.testing.FakeAuthRepository())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a switch is saved and the topics follow at once`() = runTest(dispatcher) {
        push.sync(me)
        assertEquals(4, subscribed.size)
        val vm = NotificationPrefsViewModel(prefs, push, session, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loaded)

        vm.set(AlertPrefs(requestAlerts = false))
        runCurrent() // the save runs in the app's scope (backgroundScope here)
        advanceUntilIdle()
        assertFalse(prefs.prefs.first().requestAlerts)
        assertFalse(vm.uiState.value.prefs.requestAlerts)
        assertEquals(setOf("usr_me"), subscribed)
    }
}
