package io.github.liquidlyf.feature.request.list

import androidx.lifecycle.SavedStateHandle
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeNetworkMonitor
import io.github.liquidlyf.testing.FakeOfferRepository
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class RequestsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val me = TestData.profile(uid = "me").copy(hiddenRequestIds = listOf("hidden"))
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), me))
    private val requests = FakeRequestRepository()
    private val offers = FakeOfferRepository(requests)
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }
    private val saved = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RequestsTabRequest.take() // the object outlives a test
    }

    private fun vm() = RequestsViewModel(saved, session, requests, offers, geoRepo, Clock { TestData.NOW }, FakeNetworkMonitor())

    @Test
    fun `nearby lists every open request in my district, any group`() = runTest(dispatcher) {
        requests.put(
            TestData.request(id = "b-pos"),
            TestData.request(id = "o-neg", group = BloodGroup.O_NEG),
            TestData.request(id = "hidden"),
            TestData.request(id = "far", district = "BD4041"),
            TestData.request(id = "closed", status = RequestStatus.CANCELLED),
        )
        val vm = vm()
        advanceUntilIdle()
        val nearby = vm.uiState.value.nearby
        assertEquals(setOf("b-pos", "o-neg"), nearby.items.map { it.id }.toSet())
        assertTrue(nearby.loaded)
        assertTrue(nearby.endReached)
    }

    @Test
    fun `pages load 20 at a time until the end`() = runTest(dispatcher) {
        repeat(25) { i -> requests.put(TestData.request(id = "r%02d".format(i), neededBy = TestData.NOW.plus(Duration.ofHours(1L + i)))) }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(20, vm.uiState.value.nearby.items.size)
        assertFalse(vm.uiState.value.nearby.endReached)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals((0 until 25).map { "r%02d".format(it) }, vm.uiState.value.nearby.items.map { it.id })
        assertTrue(vm.uiState.value.nearby.endReached)

        vm.refresh()
        advanceUntilIdle()
        assertEquals(20, vm.uiState.value.nearby.items.size)
    }

    @Test
    fun `the limit message's button opens a new Requests screen on My requests, once (D21)`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "mine-open", requesterUid = "me"))
        saved["requests_tab"] = RequestsTab.OFFERS.name // the tab used last
        RequestsTabRequest.open(RequestsTab.MINE)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(RequestsTab.MINE, vm.uiState.value.tab)
        assertEquals(listOf("mine-open"), vm.uiState.value.mine.items.map { it.id })
        assertEquals(RequestsTab.MINE.name, saved.get<String>("requests_tab")) // kept for rotation
        assertNull(RequestsTabRequest.take())
        assertEquals(RequestsTab.NEARBY, RequestsViewModel(SavedStateHandle(), session, requests, offers, geoRepo, Clock { TestData.NOW }, FakeNetworkMonitor()).uiState.value.tab)
    }

    @Test
    fun `a Requests screen kept with its tab switches to My requests when asked (D21)`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(RequestsTab.NEARBY, vm.uiState.value.tab)
        RequestsTabRequest.open(RequestsTab.MINE)
        vm.takeAskedTab() // the screen, shown again, asks its model
        advanceUntilIdle()
        assertEquals(RequestsTab.MINE, vm.uiState.value.tab)
        assertTrue(vm.uiState.value.mine.loaded)
        vm.selectTab(RequestsTab.NEARBY) // the person's own choice afterwards stays
        advanceUntilIdle()
        assertEquals(RequestsTab.NEARBY, vm.uiState.value.tab)
        assertNull(RequestsTabRequest.take())
    }

    @Test
    fun `mine and my offers load when their tab is opened, and the tab is remembered`() = runTest(dispatcher) {
        requests.put(
            TestData.request(id = "mine-open", requesterUid = "me"),
            TestData.request(id = "mine-done", requesterUid = "me", status = RequestStatus.FULFILLED),
            TestData.request(id = "theirs"),
        )
        offers.offer(requests.requests.value.getValue("theirs"), me, null)
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.mine.loaded)

        vm.selectTab(RequestsTab.MINE)
        advanceUntilIdle()
        assertEquals(setOf("mine-open", "mine-done"), vm.uiState.value.mine.items.map { it.id }.toSet())

        vm.selectTab(RequestsTab.OFFERS)
        advanceUntilIdle()
        val offer = vm.uiState.value.offers.items.single()
        assertEquals(ResponseStatus.OFFERED, offer.response.status)
        assertEquals("theirs", offer.request?.id)

        assertEquals(RequestsTab.OFFERS, vm().uiState.value.tab) // e.g. after process death
    }

    @Test
    fun `an offer on a request that's gone still shows`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "gone"))
        offers.offer(requests.requests.value.getValue("gone"), me, null)
        requests.requests.value = emptyMap()
        val vm = vm()
        vm.selectTab(RequestsTab.OFFERS)
        advanceUntilIdle()
        assertNull(vm.uiState.value.offers.items.single().request)
    }
}
