package io.github.liquidlyf.feature.donors

import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.UserStats
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeDonorRepository
import io.github.liquidlyf.testing.FakeFeatureFlags
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSafetyRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeWorkerClient
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.donor
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class FindDonorsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val me = TestData.profile(uid = "me").copy(blockedUids = listOf("troll")) // B+, Dhaka South, listed
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), me))
    private val donors = FakeDonorRepository()
    private val requests = FakeRequestRepository()
    private val safety = FakeSafetyRepository()
    private val worker = FakeWorkerClient()
    private val flags = FakeFeatureFlags()
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }
    private val jashore = Area("BD40", "BD4041", "BD40410023")

    private val profiles = io.github.liquidlyf.testing.FakeProfileRepository().apply { profiles["me"] = me }

    private fun vm() = FindDonorsViewModel(session, donors, requests, safety, profiles, worker, flags, geoRepo, EligibilityCalculator(), Clock { TestData.NOW })

    private fun TestScope.events(vm: FindDonorsViewModel): List<FindDonorsEvent> =
        mutableListOf<FindDonorsEvent>().also { list -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { list += it } } }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        donors.cards += listOf(
            TestData.donor("me"),
            TestData.donor("troll"),
            TestData.donor("d1"),
            TestData.donor("d2", group = BloodGroup.O_POS),
            TestData.donor("d3", area = jashore),
            TestData.donor("d4", eligibleFrom = TestData.NOW.plus(Duration.ofDays(30))),
            TestData.donor("d5", available = false),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `nothing is read until Search, and results leave out me and the donors I blocked`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.enabled)
        assertEquals(me.area.district, vm.uiState.value.filters.district)
        assertFalse("no group yet", vm.uiState.value.filters.ready)

        vm.toggleGroup(BloodGroup.B_POS)
        vm.setEligibleNowOnly(true)
        advanceUntilIdle()
        assertTrue("chip taps don't search", donors.searches.isEmpty())
        assertEquals(0, donors.counts)

        vm.search()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf("d1"), s.results?.map { it.uid })
        assertEquals("me, troll and d1 match; me is left out of the count", 2L, s.total)
        assertTrue(s.endReached)

        vm.setEligibleNowOnly(false)
        vm.search()
        advanceUntilIdle()
        assertEquals(listOf("d1", "d4"), vm.uiState.value.results?.map { it.uid })
    }

    @Test
    fun `an upazila narrows the district, and groups are combined`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.toggleGroup(BloodGroup.B_POS)
        vm.toggleGroup(BloodGroup.O_POS)
        vm.search()
        advanceUntilIdle()
        assertEquals(listOf("d1", "d2"), vm.uiState.value.results?.map { it.uid })

        vm.setDivision(jashore.division)
        vm.setDistrict(jashore.district)
        vm.setUpazila(jashore.upazila)
        vm.search()
        advanceUntilIdle()
        assertEquals(listOf("d3"), vm.uiState.value.results?.map { it.uid })
        assertEquals(jashore.upazila, donors.searches.last().upazila)
    }

    @Test
    fun `results come in pages of 20`() = runTest(dispatcher) {
        donors.cards.clear()
        donors.cards += TestData.donor("me") // my own card sorts first and is hidden
        donors.cards += (1..25).map { TestData.donor("p%02d".format(it)) }
        val vm = vm()
        advanceUntilIdle()
        vm.toggleGroup(BloodGroup.B_POS)
        vm.search()
        advanceUntilIdle()
        assertEquals(19, vm.uiState.value.results?.size)
        assertFalse(vm.uiState.value.endReached)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(25, vm.uiState.value.results?.size)
        assertTrue(vm.uiState.value.endReached)
        assertEquals(25L, vm.uiState.value.total)
    }

    @Test
    fun `a donor can be reported or blocked from their sheet, and a blocked one leaves the results`() = runTest(dispatcher) {
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.toggleGroup(BloodGroup.B_POS)
        vm.search()
        advanceUntilIdle()
        assertEquals(listOf("d1"), vm.uiState.value.results?.map { it.uid })

        vm.openAsk(TestData.donor("d1"))
        advanceUntilIdle()
        vm.reportDonor(io.github.liquidlyf.core.model.ReportReason.SPAM, null)
        advanceUntilIdle()
        assertEquals(listOf("d1" to io.github.liquidlyf.core.model.ReportReason.SPAM), safety.userReports)
        assertEquals(FindDonorsEvent.Message(R.string.reported_thanks), events.last())

        vm.blockDonor()
        advanceUntilIdle()
        assertEquals(listOf("troll", "d1"), profiles.profiles.getValue("me").blockedUids)
        assertEquals(emptyList<String>(), vm.uiState.value.results?.map { it.uid })
        assertNull(vm.uiState.value.ask)
        assertEquals(FindDonorsEvent.Message(R.string.blocked_done), events.last())
    }

    @Test
    fun `saver mode hides the search`() = runTest(dispatcher) {
        flags.donorSearch = false
        val vm = vm()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.enabled)
    }

    @Test
    fun `asking a donor for one of my open requests sends the ask and the push`() = runTest(dispatcher) {
        requests.put(
            TestData.request(id = "r1", requesterUid = "me", group = BloodGroup.B_POS),
            TestData.request(id = "r2", requesterUid = "me", group = BloodGroup.O_POS),
            TestData.request(id = "r3", requesterUid = "me", group = BloodGroup.B_POS, status = RequestStatus.FULFILLED),
            TestData.request(id = "other", requesterUid = "someone", group = BloodGroup.B_POS),
        )
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        val d1 = TestData.donor("d1")
        vm.openAsk(d1)
        advanceUntilIdle()
        val sheet = vm.uiState.value.ask!!
        assertEquals("only my open requests a B+ donor can give to", listOf("r1"), sheet.requests?.map { it.id })
        assertEquals("r1", sheet.selected)

        vm.ask()
        advanceUntilIdle()
        assertEquals(listOf("r1" to "d1"), safety.asks)
        assertEquals(listOf("r1" to "d1"), worker.askNotices)
        assertNull(vm.uiState.value.ask)
        assertEquals(FindDonorsEvent.Message(R.string.donors_asked, "Donor d1"), events.last())
        assertTrue(vm.isAsked(sheet.requests!!.single(), d1))
    }

    @Test
    fun `posting or closing a request elsewhere updates 'post a request first' when Find donors is back`() = runTest(dispatcher) {
        profiles.profiles["me"] = profiles.profiles["me"] ?: TestData.profile(uid = "me")
        val vm = vm()
        vm.onResume() // the first time: init has just read it
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.hasOpenRequest)

        profiles.stats["me"] = UserStats(openRequests = 1, totalRequests = 1, lastRequestId = "r1") // posted from Request blood
        vm.onResume()
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.hasOpenRequest)

        profiles.stats["me"] = UserStats(openRequests = 0, totalRequests = 1, lastRequestId = "r1") // closed
        vm.onResume()
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.hasOpenRequest)
    }

    @Test
    fun `without a fitting open request the sheet says why`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.openAsk(TestData.donor("d1"))
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), vm.uiState.value.ask?.requests)
        assertFalse(vm.uiState.value.ask!!.noneCompatible)

        requests.put(TestData.request(id = "r2", requesterUid = "me", group = BloodGroup.O_POS))
        vm.openAsk(TestData.donor("d1"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.ask!!.noneCompatible)
    }

    @Test
    fun `asking twice, a full request and the daily limit each get their own message`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "r1", requesterUid = "me", group = BloodGroup.B_POS))
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()

        // Already asked: the fixed ask id makes the batch fail; no push.
        safety.askResult = AppResult.Failure(AppError.PermissionDenied)
        vm.openAsk(TestData.donor("d1"))
        advanceUntilIdle()
        vm.ask()
        advanceUntilIdle()
        assertEquals(FindDonorsEvent.Message(R.string.donors_ask_already), events.last())
        assertTrue(worker.askNotices.isEmpty())

        // The Worker's daily cap: saved, but not pushed.
        safety.askResult = AppResult.Success(Unit)
        worker.askResult = AppResult.Failure(AppError.DailyAskLimit)
        vm.ask()
        advanceUntilIdle()
        assertEquals(FindDonorsEvent.Message(R.string.error_daily_ask_limit), events.last())
        assertEquals(listOf("r1" to "d1"), safety.asks)

        // A request that already had its 10 asks isn't tried.
        requests.put(TestData.request(id = "r1", requesterUid = "me", group = BloodGroup.B_POS).copy(askCount = 10))
        vm.openAsk(TestData.donor("d2b", group = BloodGroup.B_POS))
        advanceUntilIdle()
        vm.ask()
        advanceUntilIdle()
        assertEquals(FindDonorsEvent.Message(R.string.donors_ask_request_full), events.last())
        assertEquals(1, safety.asks.size)
    }
}
