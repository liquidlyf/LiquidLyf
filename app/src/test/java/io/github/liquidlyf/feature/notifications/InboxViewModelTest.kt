package io.github.liquidlyf.feature.notifications

import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.profile.VerifiedDonationRecorder
import io.github.liquidlyf.core.data.push.LocalAlert
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.InboxItem
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAlertStore
import io.github.liquidlyf.testing.FakeInboxRepository
import io.github.liquidlyf.testing.FakeOfferRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val seen = TestData.NOW.minus(Duration.ofHours(1))
    private val me = TestData.profile(uid = "me").copy(inboxSeenAt = seen)
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), me))
    private val inbox = FakeInboxRepository()
    private val alerts = FakeAlertStore()
    private val profiles = FakeProfileRepository().apply { profiles["me"] = me }
    private val requests = FakeRequestRepository()
    private val offers = FakeOfferRepository(requests)
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm() = InboxViewModel(
        session, inbox, alerts, profiles, VerifiedDonationRecorder(profiles, offers, requests, Clock { TestData.NOW }), geoRepo, Clock { TestData.NOW },
    ).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} } }

    private fun item(id: String, type: InboxType, minutesAgo: Long, requestId: String = "r-$id") =
        InboxItem(id, type, "someone", requestId, TestData.NOW.minus(Duration.ofMinutes(minutesAgo)))

    @Test
    fun `updates and this phone's alerts are merged, newest first, and the visit is marked once`() = runTest(dispatcher) {
        inbox.items += item("offer", InboxType.NEW_OFFER, minutesAgo = 10)
        inbox.items += item("confirmed", InboxType.OFFER_CONFIRMED, minutesAgo = 120)
        alerts.recordShown(LocalAlert("alert-1", stillNeeded = false, bloodGroup = "B_POS", district = "BD3026", urgency = "URGENT", receivedAtMillis = TestData.NOW.minus(Duration.ofMinutes(30)).toEpochMilli()))
        val vm = vm()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("r-offer", "alert-1", "r-confirmed"), state.entries!!.map { it.requestId })
        // New = after my last visit (an hour ago).
        assertEquals(listOf(true, true, false), state.entries!!.map { state.isNew(it) })
        assertTrue(state.endReached)
        assertEquals(1, profiles.inboxSeenMarks)
        assertEquals(TestData.NOW.toEpochMilli(), alerts.visited.value) // A43: by this phone's clock too
        assertEquals(1, session.refreshes)

        vm.loadMore() // nothing more to load, nothing more to mark
        advanceUntilIdle()
        assertEquals(1, profiles.inboxSeenMarks)
    }

    @Test
    fun `older updates load a page at a time`() = runTest(dispatcher) {
        repeat(35) { inbox.items += item("i$it", InboxType.NEW_OFFER, minutesAgo = it.toLong()) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(30, vm.uiState.value.entries!!.size)
        assertFalse(vm.uiState.value.endReached)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(35, vm.uiState.value.entries!!.size)
        assertTrue(vm.uiState.value.endReached)
    }

    @Test
    fun `a donation recorded item fills in a missing verified donation`() = runTest(dispatcher) {
        val donated = TestData.request(id = "r-don").copy(responders = mapOf("me" to Responder("Me", me.bloodGroup!!, ResponseStatus.DONATED)))
        requests.put(donated)
        inbox.items += item("don", InboxType.DONATION_RECORDED, minutesAgo = 5, requestId = "r-don")
        vm()
        advanceUntilIdle()
        assertEquals(listOf("me" to "r-don"), profiles.verifiedRecorded)
        assertEquals(2, session.refreshes) // the visit, then the new donation

        // Next visit: it's already there, nothing is written twice.
        vm()
        advanceUntilIdle()
        assertEquals(1, profiles.verifiedRecorded.size)
    }

    @Test
    fun `empty inbox`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(emptyList<InboxEntry>(), vm.uiState.value.entries)
    }
}
