package io.github.liquidlyf.feature.safety

import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.testing.FakeDonorRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.donor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedPeopleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profiles = FakeProfileRepository()
    private val donors = FakeDonorRepository()
    private val me = TestData.profile(uid = "me").copy(blockedUids = listOf("listed", "unlisted"))

    /** Like the real session: refresh() reads the profile again after a write. */
    private val session = object : SessionManager {
        override val state = MutableStateFlow<SessionState>(SessionState.Ready(TestData.user(uid = "me"), me))
        override fun refresh() {
            state.value = SessionState.Ready(TestData.user(uid = "me"), profiles.profiles.getValue("me"))
        }
        override suspend fun signOut() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        profiles.profiles["me"] = me
        donors.cards += TestData.donor("listed")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `blocked people are listed newest first, named when they have a donor card, and can be unblocked`() = runTest(dispatcher) {
        val vm = BlockedPeopleViewModel(session, profiles, donors)
        val events = mutableListOf<Int>().also { list ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { list += it } }
        }
        advanceUntilIdle()
        assertEquals(listOf(BlockedPerson("unlisted", null), BlockedPerson("listed", "Donor listed")), vm.uiState.value.people)

        vm.unblock("listed")
        advanceUntilIdle()
        assertEquals(listOf("unlisted"), profiles.profiles.getValue("me").blockedUids)
        assertEquals(listOf(BlockedPerson("unlisted", null)), vm.uiState.value.people)
        assertEquals(R.string.blocked_unblocked, events.last())
    }
}
