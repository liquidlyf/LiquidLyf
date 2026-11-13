package io.github.liquidlyf.feature.request.detail

import androidx.lifecycle.SavedStateHandle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.request.create.PostNotice
import io.github.liquidlyf.testing.FakeOfferRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSafetyRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeWorkerClient
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class RequestDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val donor = TestData.profile(uid = "donor").copy(fullName = "Nasrin Akter")
    private val requester = TestData.profile(uid = "requester-1").copy(fullName = "Karim Hossain")
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "donor"), donor))
    private val requests = FakeRequestRepository()
    private val offers = FakeOfferRepository(requests)
    private val profiles = FakeProfileRepository().apply { profiles["donor"] = donor; profiles["requester-1"] = requester }
    private val safety = FakeSafetyRepository()
    private val worker = FakeWorkerClient()
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }
    private val contact = RequestContact("requester-1", "Karim Hossain", "+8801812345678", "Abdul", "Ward 5")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        requests.put(TestData.request(id = "r1", group = BloodGroup.B_POS))
        requests.contacts["r1"] = contact
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(id: String = "r1", notice: String? = null) = RequestDetailViewModel(
        SavedStateHandle(mapOf("id" to id, "notice" to notice)),
        session, requests, offers, profiles, safety, worker, geoRepo, EligibilityCalculator(), Clock { TestData.NOW },
    )

    private fun signInAs(p: UserProfile) {
        session.state.value = SessionState.Ready(TestData.user(uid = p.uid), p)
    }

    private fun TestScope.events(vm: RequestDetailViewModel): List<DetailEvent> =
        mutableListOf<DetailEvent>().also { list -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { list += it } } }

    @Test
    fun `a donor sees no contact until offering, then can call`() = runTest(dispatcher) {
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(ViewerRole.DONOR, s.role)
        assertNull(s.offerBlock)
        assertNull(s.myStatus)
        assertNull(s.contact)

        vm.offer("  Coming at 4 pm  ")
        advanceUntilIdle()
        assertEquals(ResponseStatus.OFFERED, vm.uiState.value.myStatus)
        assertEquals("Coming at 4 pm", offers.responses.value["r1" to "donor"]?.message)
        assertEquals(contact, vm.uiState.value.contact)
        assertEquals(listOf("r1" to "donor"), worker.offerNotices)
        assertEquals(listOf<DetailEvent>(DetailEvent.Message(R.string.detail_offer_sent)), events)
    }

    @Test
    fun `offering is blocked with a reason`() = runTest(dispatcher) {
        signInAs(donor.copy(bloodGroup = null))
        val noGroup = vm()
        advanceUntilIdle()
        assertEquals(OfferBlock.NoBloodGroup, noGroup.uiState.value.offerBlock)

        signInAs(donor.copy(bloodGroup = BloodGroup.A_POS))
        val incompatible = vm()
        advanceUntilIdle()
        assertEquals(OfferBlock.Incompatible, incompatible.uiState.value.offerBlock)

        signInAs(donor.copy(lastDonationAt = TestData.NOW.minus(Duration.ofDays(40))))
        val resting = vm()
        advanceUntilIdle()
        val block = resting.uiState.value.offerBlock as OfferBlock.NotEligible
        assertEquals(setOf(IneligibleReason.REST_PERIOD), block.reasons)
        assertEquals(TestData.NOW.plus(Duration.ofDays(80)), block.eligibleFrom)

        signInAs(donor)
        requests.put(TestData.request(id = "r2", status = RequestStatus.FULFILLED))
        val closed = vm("r2")
        advanceUntilIdle()
        assertEquals(OfferBlock.Closed, closed.uiState.value.offerBlock)
    }

    @Test
    fun `the requester sees offers live and confirms, then marks donated`() = runTest(dispatcher) {
        // Nasrin offers first, as a donor.
        offers.offer(requests.requests.value.getValue("r1"), donor, null)
        signInAs(requester)
        val vm = vm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(ViewerRole.REQUESTER, s.role)
        assertEquals(contact, s.contact)
        val offer = s.responses.single()
        assertEquals("donor", offer.donorUid)
        assertEquals("+8801712345678", offer.donorPhone)

        vm.confirm(offer)
        advanceUntilIdle()
        assertEquals(ResponseStatus.CONFIRMED, vm.uiState.value.responses.single().status)
        vm.markDonated(vm.uiState.value.responses.single())
        advanceUntilIdle()
        val r = vm.uiState.value.request!!
        assertEquals(1, r.unitsFulfilled)
        assertEquals(RequestStatus.FULFILLED, r.status)
        assertEquals(listOf(ResponseStatus.CONFIRMED, ResponseStatus.DONATED), offers.decisions.map { it.third })
        assertEquals(listOf("r1" to "donor", "r1" to "donor"), worker.offerNotices)
    }

    @Test
    fun `closing tells the waiting donors`() = runTest(dispatcher) {
        signInAs(requester)
        val vm = vm()
        advanceUntilIdle()
        vm.close(RequestStatus.CANCELLED, "  ")
        advanceUntilIdle()
        assertEquals(Triple("r1", RequestStatus.CANCELLED, null), requests.closed.single())
        assertEquals(listOf("r1"), worker.responderNotices)
    }

    @Test
    fun `the donor's app records a verified donation once`() = runTest(dispatcher) {
        offers.offer(requests.requests.value.getValue("r1"), donor, null)
        val vm = vm()
        advanceUntilIdle()
        // The requester marks it donated; the donor's screen sees the change live.
        offers.decide(requests.requests.value.getValue("r1"), offers.responses.value.getValue("r1" to "donor"), ResponseStatus.DONATED)
        advanceUntilIdle()
        assertEquals(ResponseStatus.DONATED, vm.uiState.value.myStatus)
        assertEquals(listOf("donor" to "r1"), profiles.verifiedRecorded)
        assertEquals(1, session.refreshes)

        val again = vm() // reopening the request doesn't count it twice
        advanceUntilIdle()
        assertEquals(1, profiles.verifiedRecorded.size)
        assertFalse(again.uiState.value.loading)
    }

    @Test
    fun `not for me hides it everywhere and leaves`() = runTest(dispatcher) {
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.hide()
        advanceUntilIdle()
        assertEquals(listOf("r1"), profiles.profiles.getValue("donor").hiddenRequestIds)
        assertEquals(1, session.refreshes)
        assertEquals(listOf<DetailEvent>(DetailEvent.Left), events)
    }

    @Test
    fun `report, a missing request and the post notice`() = runTest(dispatcher) {
        val vm = vm(notice = PostNotice.DAILY_ALERT_LIMIT.name)
        advanceUntilIdle()
        assertEquals(PostNotice.DAILY_ALERT_LIMIT, vm.uiState.value.notice)
        vm.report(ReportReason.ASKING_MONEY, "")
        advanceUntilIdle()
        assertEquals(listOf("r1" to ReportReason.ASKING_MONEY), safety.reports)

        val missing = vm("nope")
        advanceUntilIdle()
        assertFalse(missing.uiState.value.loading)
        assertNull(missing.uiState.value.request)
        assertTrue(missing.uiState.value.responses.isEmpty())
    }
}
