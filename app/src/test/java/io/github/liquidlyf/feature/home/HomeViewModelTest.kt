package io.github.liquidlyf.feature.home

import com.google.firebase.firestore.FirebaseFirestoreException
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.push.LocalAlert
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.InboxItem
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAlertStore
import io.github.liquidlyf.testing.FakeInboxRepository
import io.github.liquidlyf.testing.FakeNetworkMonitor
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.request
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val me = TestData.profile(uid = "me") // B+, Dhaka South, listed, available
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), me))
    private val requests = FakeRequestRepository()
    private val profiles = FakeProfileRepository().apply { profiles["me"] = me }
    private val inbox = FakeInboxRepository()
    private val alerts = FakeAlertStore()
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val network = FakeNetworkMonitor()

    private fun TestScope.vm() = HomeViewModel(
        session, requests, profiles, geoRepo, EligibilityCalculator(), Clock { TestData.NOW }, network, inbox, alerts,
    ).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} } }

    @Test
    fun `the feed shows what I can answer, most urgent first`() = runTest(dispatcher) {
        val soon = TestData.NOW.plus(Duration.ofHours(2))
        requests.put(
            TestData.request(id = "planned", neededBy = soon).copy(urgency = Urgency.PLANNED),
            TestData.request(id = "urgent-late", neededBy = soon.plusSeconds(3600)).copy(urgency = Urgency.URGENT),
            TestData.request(id = "urgent-early", neededBy = soon).copy(urgency = Urgency.URGENT),
            TestData.request(id = "critical", neededBy = soon.plusSeconds(7200)),
            TestData.request(id = "hidden"),
            TestData.request(id = "blocked", requesterUid = "troll"),
            TestData.request(id = "mine", requesterUid = "me"),
            TestData.request(id = "other-group", group = BloodGroup.O_NEG),
            TestData.request(id = "other-district", district = "BD4041"),
            TestData.request(id = "closed", status = RequestStatus.FULFILLED),
            TestData.request(id = "expired").copy(expiresAt = TestData.NOW.minusSeconds(1)),
        )
        session.state.value = SessionState.Ready(
            TestData.user(uid = "me"),
            me.copy(hiddenRequestIds = listOf("hidden"), blockedUids = listOf("troll")),
        )
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("critical", "urgent-early", "urgent-late", "planned"), vm.uiState.value.requests?.map { it.id })
    }

    @Test
    fun `my open request shows as Your request, live, and goes when it's closed`() = runTest(dispatcher) {
        val mine = TestData.request(id = "mine", requesterUid = "me").copy(unitsNeeded = 2)
        requests.put(mine, TestData.request(id = "old", requesterUid = "me", status = RequestStatus.FULFILLED), TestData.request(id = "theirs"))
        val vm = vm()
        vm.onResume() // the first time: the flow has just looked
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ownLoaded) // the screen held the list until now, so nothing jumps
        assertEquals(listOf("mine"), vm.uiState.value.ownRequests.map { it.id })

        requests.put(mine.copy(unitsFulfilled = 1)) // a donor gave: the card follows
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.ownRequests.single().unitsFulfilled)

        requests.put(mine.copy(status = RequestStatus.FULFILLED))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ownRequests.isEmpty())

        // Posted elsewhere, then back on Home: it shows.
        requests.put(TestData.request(id = "new", requesterUid = "me"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ownRequests.isEmpty())
        vm.onResume()
        advanceUntilIdle()
        assertEquals(listOf("new"), vm.uiState.value.ownRequests.map { it.id })
    }

    @Test
    fun `offline with nothing on the phone yet, the feed says it couldn't load, not that no one needs blood`() = runTest(dispatcher) {
        requests.feedUnknown.value = true
        network.isOnline.value = false
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.requests)
        assertEquals(AppError.Network, vm.uiState.value.feedError)

        network.isOnline.value = true // online: still loading until the server answers
        advanceUntilIdle()
        assertNull(vm.uiState.value.feedError)
        requests.feedUnknown.value = false
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), vm.uiState.value.requests)
    }

    @Test
    fun `without a blood group there's no feed to show`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "r1"))
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(bloodGroup = null))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), vm.uiState.value.requests)
    }

    @Test
    fun `the availability switch moves at once and stays when saved`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.available)

        vm.onAvailableToggled(false)
        runCurrent()
        assertFalse(vm.uiState.value.available) // optimistic
        assertTrue(vm.uiState.value.savingAvailability)
        vm.onAvailableToggled(true) // ignored while saving
        runCurrent()
        assertEquals(listOf(false), profiles.availabilityWrites)
        assertEquals(1, session.refreshes)
        assertFalse(vm.uiState.value.available) // still shown while the profile refreshes

        // The refreshed profile arrives: the switch keeps its place and is free again.
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(available = false))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.available)
        assertFalse(vm.uiState.value.savingAvailability)
    }

    @Test
    fun `a failed availability write moves the switch back and says why`() = runTest(dispatcher) {
        val vm = vm()
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()
        profiles.failNext = AppError.Network
        vm.onAvailableToggled(false)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.available)
        assertFalse(vm.uiState.value.savingAvailability)
        assertEquals(listOf<HomeEvent>(HomeEvent.Message(R.string.error_network)), events)
    }

    @Test
    fun `List me lists an unlisted donor and the session reads it again`() = runTest(dispatcher) {
        val unlisted = me.copy(showInDonorSearch = false)
        profiles.profiles["me"] = unlisted
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), unlisted)
        val vm = vm()
        advanceUntilIdle()
        vm.listMe()
        advanceUntilIdle()
        assertEquals(listOf(true), profiles.listingWrites)
        assertEquals(1, session.refreshes)
    }

    @Test
    fun `eligibility counts down the rest period`() = runTest(dispatcher) {
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(lastDonationAt = TestData.NOW.minus(Duration.ofDays(100))))
        val vm = vm()
        advanceUntilIdle()
        val e = vm.uiState.value.eligibility!!
        assertEquals(setOf(IneligibleReason.REST_PERIOD), e.reasons)
        assertEquals(TestData.NOW.plus(Duration.ofDays(20)), e.eligibleFrom)
    }

    @Test
    fun `the bell shows a badge for anything newer than my last visit`() = runTest(dispatcher) {
        val seen = TestData.NOW.minus(Duration.ofHours(1))
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(inboxSeenAt = seen))
        inbox.items += InboxItem("old", InboxType.NEW_OFFER, "donor", "r1", seen.minusSeconds(60))
        val vm = vm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.unreadInbox.collect {} }
        advanceUntilIdle()
        assertFalse(vm.unreadInbox.value)

        // A request alert on this phone after my visit.
        alerts.recordShown(LocalAlert("r2", stillNeeded = false, receivedAtMillis = TestData.NOW.toEpochMilli()))
        advanceUntilIdle()
        assertTrue(vm.unreadInbox.value)

        // Opening the inbox moves inboxSeenAt: the badge clears. A newer Firestore item brings it back.
        alerts.clear()
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(inboxSeenAt = TestData.NOW))
        advanceUntilIdle()
        assertFalse(vm.unreadInbox.value)
        inbox.items += InboxItem("new", InboxType.OFFER_CONFIRMED, "requester", "r3", TestData.NOW.plusSeconds(60))
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(inboxSeenAt = TestData.NOW.minusSeconds(1)))
        advanceUntilIdle()
        assertTrue(vm.unreadInbox.value)
    }

    @Test
    fun `a feed listener closed by Firestore shows an empty list instead of crashing`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "r1"))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("r1"), vm.uiState.value.requests?.map { it.id })

        requests.listenFailure.value = FirebaseFirestoreException("Missing or insufficient permissions.", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        advanceUntilIdle() // uncaught, this failed the test (and crashed the app in A16)
        assertEquals(emptyList<String>(), vm.uiState.value.requests?.map { it.id })
    }

    @Test
    fun `a feed that fails shows an error with Retry, not no requests`() = runTest(dispatcher) {
        // STATE-01: before, any listener error emptied the list: "No one near you needs B+ right now".
        requests.put(TestData.request(id = "r1"))
        requests.listenFailure.value = FirebaseFirestoreException("Unavailable", FirebaseFirestoreException.Code.UNAVAILABLE)
        val vm = vm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.requests)
        assertEquals(AppError.Network, vm.uiState.value.feedError)

        requests.listenFailure.value = null
        vm.retryFeed()
        advanceUntilIdle()
        assertNull(vm.uiState.value.feedError)
        assertEquals(listOf("r1"), vm.uiState.value.requests?.map { it.id })
    }
}
