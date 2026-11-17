package io.github.liquidlyf.feature.request.detail

import androidx.lifecycle.SavedStateHandle
import com.google.firebase.firestore.FirebaseFirestoreException
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.components.AppSnackbars
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.request.create.PostNotice
import io.github.liquidlyf.testing.FakeNetworkMonitor
import io.github.liquidlyf.testing.FakeOfferRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeReminders
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSafetyRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeWorkerClient
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertSame
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
    private val reminders = FakeReminders()
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }
    private val contact = RequestContact("requester-1", "Karim Hossain", "+8801812345678", "Abdul", "Ward 5")
    private val network = FakeNetworkMonitor()

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
        session, requests, offers, profiles, safety, worker, geoRepo, EligibilityCalculator(), Clock { TestData.NOW }, network, reminders,
        appScope = CoroutineScope(dispatcher),
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
        assertEquals("Coming at 4 pm", vm.uiState.value.myMessage) // "Your offer" shows it (D9)
        assertEquals(donor.phone, vm.uiState.value.myPhone)

        // Opened again later: the message is read back from my offer.
        val later = vm()
        advanceUntilIdle()
        assertEquals("Coming at 4 pm", later.uiState.value.myMessage)
    }

    @Test
    fun `a decline that lands while the contact loads keeps it hidden`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        requests.contactGate = gate
        val vm = vm()
        advanceUntilIdle()
        vm.offer("")
        advanceUntilIdle() // offered: the contact is loading
        offers.decide(requests.requests.value.getValue("r1"), offers.responses.value.getValue("r1" to "donor"), ResponseStatus.DECLINED)
        advanceUntilIdle()
        gate.complete(Unit) // the read comes back after the decline
        advanceUntilIdle()
        assertEquals(ResponseStatus.DECLINED, vm.uiState.value.myStatus)
        assertNull(vm.uiState.value.contact)
    }

    @Test
    fun `a requester without donor details is sent to Become a donor, not refused (D1)`() = runTest(dispatcher) {
        signInAs(donor.copy(gender = null, birthYear = null, weightOk = null, available = false, showInDonorSearch = false))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OfferBlock.NotADonor, vm.uiState.value.offerBlock)
    }

    @Test
    fun `an alert held back by the daily limit is known from the request itself`() {
        val r = TestData.request(id = "r9").copy(fanoutTier = 0, nextTierAt = TestData.NOW)
        assertFalse(r.alertHeldBack) // new: due now, tier 0
        assertTrue(r.copy(nextTierAt = BloodRequest.ALERT_NEVER_DUE).alertHeldBack)
        assertFalse(r.copy(fanoutTier = 3, nextTierAt = BloodRequest.ALERT_NEVER_DUE).alertHeldBack) // sent, then finished
    }

    @Test
    fun `the contact disappears once the offer is withdrawn or declined`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.offer("")
        advanceUntilIdle()
        assertEquals(contact, vm.uiState.value.contact)
        vm.withdraw()
        advanceUntilIdle()
        assertEquals(ResponseStatus.WITHDRAWN, vm.uiState.value.myStatus)
        assertNull(vm.uiState.value.contact)

        // A declined donor: the requester said no, so the number goes too.
        requests.put(TestData.request(id = "r2", group = BloodGroup.B_POS))
        requests.contacts["r2"] = contact
        val r2 = vm("r2")
        advanceUntilIdle()
        r2.offer("")
        advanceUntilIdle()
        assertEquals(contact, r2.uiState.value.contact)
        val offer = offers.responses.value.getValue("r2" to "donor")
        offers.decide(requests.requests.value.getValue("r2"), offer, ResponseStatus.DECLINED)
        advanceUntilIdle()
        assertEquals(ResponseStatus.DECLINED, r2.uiState.value.myStatus)
        assertNull(r2.uiState.value.contact)
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
        // The last unit closed the request: donors still waiting are told, as on "Found blood".
        assertEquals(listOf("r1"), worker.responderNotices)
    }

    @Test
    fun `blocking a donor declines their open offer and hides them, and a person can be reported`() = runTest(dispatcher) {
        offers.offer(requests.requests.value.getValue("r1"), donor, null)
        signInAs(requester)
        profiles.profiles["requester-1"] = requester
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        val offer = vm.uiState.value.responses.single()

        vm.reportPerson("donor", ReportReason.ABUSIVE, "  rude  ")
        advanceUntilIdle()
        assertEquals(listOf("donor" to ReportReason.ABUSIVE), safety.userReports)
        assertEquals(DetailEvent.Message(R.string.reported_thanks), events.last())

        vm.blockDonor(offer)
        advanceUntilIdle()
        assertEquals("their open offer is declined, so they lose my number", ResponseStatus.DECLINED, offers.decisions.single().third)
        assertEquals(listOf("r1" to "donor"), worker.offerNotices)
        assertEquals(listOf("donor"), profiles.profiles.getValue("requester-1").blockedUids)
        assertEquals(emptyList<String>(), vm.uiState.value.responses.map { it.donorUid })
        assertEquals(DetailEvent.Message(R.string.blocked_done), events.last())
    }

    @Test
    fun `a donor blocks the requester and leaves the request`() = runTest(dispatcher) {
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.blockRequester()
        advanceUntilIdle()
        assertEquals(listOf("requester-1"), profiles.profiles.getValue("donor").blockedUids)
        assertEquals(listOf(DetailEvent.Message(R.string.blocked_done), DetailEvent.Left), events.takeLast(2))
    }

    @Test
    fun `offers from people I blocked stay hidden`() = runTest(dispatcher) {
        val pest = TestData.profile(uid = "pest").copy(fullName = "Pest Person")
        profiles.profiles["pest"] = pest
        offers.offer(requests.requests.value.getValue("r1"), donor, null)
        offers.offer(requests.requests.value.getValue("r1"), pest, null)
        signInAs(requester.copy(blockedUids = listOf("pest")))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("donor"), vm.uiState.value.responses.map { it.donorUid })
    }

    @Test
    fun `only the donation that fills the request tells the other waiting donors`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "r2", group = BloodGroup.B_POS).copy(unitsNeeded = 2))
        val donor2 = TestData.profile(uid = "donor2").copy(fullName = "Rina Begum")
        profiles.profiles["donor2"] = donor2
        offers.offer(requests.requests.value.getValue("r2"), donor, null)
        offers.offer(requests.requests.value.getValue("r2"), donor2, null)
        signInAs(requester)
        val vm = vm("r2")
        advanceUntilIdle()

        vm.confirm(vm.uiState.value.responses.first { it.donorUid == "donor" })
        advanceUntilIdle()
        vm.markDonated(vm.uiState.value.responses.first { it.donorUid == "donor" })
        advanceUntilIdle()
        assertEquals(RequestStatus.OPEN, vm.uiState.value.request!!.status)
        assertEquals("1 of 2 units: still open, nobody else told", emptyList<String>(), worker.responderNotices)

        vm.confirm(vm.uiState.value.responses.first { it.donorUid == "donor2" })
        advanceUntilIdle()
        vm.markDonated(vm.uiState.value.responses.first { it.donorUid == "donor2" })
        advanceUntilIdle()
        assertEquals(RequestStatus.FULFILLED, vm.uiState.value.request!!.status)
        assertEquals(listOf("r2"), worker.responderNotices)
    }

    @Test
    fun `closing tells the waiting donors`() = runTest(dispatcher) {
        signInAs(requester)
        val vm = vm()
        advanceUntilIdle()
        // My open request reminds me 2 h before it's needed.
        assertEquals(requests.requests.value.getValue("r1").neededBy, reminders.ending["r1"])
        vm.close(RequestStatus.CANCELLED, "  ")
        advanceUntilIdle()
        assertEquals(Triple("r1", RequestStatus.CANCELLED, null), requests.closed.single())
        assertEquals(listOf("r1"), worker.responderNotices)
        // Closed: no reminder.
        assertEquals(null, reminders.ending["r1"])
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
    fun `not for me offers Undo on the screen it goes back to, and Undo brings it back`() = runTest(dispatcher) {
        // FLOW-08: it used to leave with no message and no undo.
        AppSnackbars.clear()
        val vm = vm()
        advanceUntilIdle()
        vm.hide()
        advanceUntilIdle()
        val entry = AppSnackbars.current.first()!!
        val snackbar = entry.snackbar
        assertEquals(R.string.request_hidden, snackbar.message)
        assertEquals(R.string.action_undo, snackbar.actionLabel)
        // A rotation mid-show recreates the screen: the message is still there to show again.
        assertSame(entry, AppSnackbars.current.first())
        AppSnackbars.done(entry)
        assertNull(AppSnackbars.current.first())

        // Undo before the session has re-read the profile (its copy doesn't list r1 as hidden yet):
        // Undo reads the profile itself, so it still works.
        snackbar.onAction!!.invoke()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), profiles.profiles.getValue("donor").hiddenRequestIds)
        assertEquals(2, session.refreshes)
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

    @Test
    fun `a listener closed by Firestore shows the request as not available instead of crashing`() = runTest(dispatcher) {
        // A16: signed out while the requester's view still listened to the request and its offers.
        signInAs(requester)
        requests.put(TestData.request(id = "r1", group = BloodGroup.B_POS))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(ViewerRole.REQUESTER, vm.uiState.value.role)

        requests.listenFailure.value = FirebaseFirestoreException("Missing or insufficient permissions.", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        advanceUntilIdle() // uncaught, this failed the test (and crashed the app)
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.request)
    }

    @Test
    fun `a failed load is an error with Retry, not a removed request`() = runTest(dispatcher) {
        // STATE-05: before, any listener error read "This request isn't available. It may have been removed".
        requests.listenFailure.value = FirebaseFirestoreException("Unavailable", FirebaseFirestoreException.Code.UNAVAILABLE)
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.request)
        assertEquals(AppError.Network, vm.uiState.value.loadError)

        requests.listenFailure.value = null
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.uiState.value.loadError)
        assertEquals("r1", vm.uiState.value.request?.id)
    }

    @Test
    fun `offline, a request that isn't on the phone says no internet, then shows up when back online`() = runTest(dispatcher) {
        requests.notOnPhone.value = setOf("r9")
        network.isOnline.value = false
        val vm = vm("r9")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertEquals(AppError.Network, vm.uiState.value.loadError)

        network.isOnline.value = true
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loading) // waiting for the server's answer
        assertNull(vm.uiState.value.loadError)

        requests.put(TestData.request(id = "r9", group = BloodGroup.B_POS))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertEquals("r9", vm.uiState.value.request?.id)
    }

    @Test
    fun `just posted offline, its page shows loading while the copy on the phone is written, not an error`() = runTest(dispatcher) {
        requests.notOnPhone.value = setOf("r9")
        network.isOnline.value = false
        val vm = vm("r9", notice = PostNotice.WAITING_FOR_NETWORK.name)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loading)
        assertNull(vm.uiState.value.loadError)
    }

    @Test
    fun `a request the server doesn't have is not available, with no error`() = runTest(dispatcher) {
        val vm = vm("gone")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.request)
        assertNull(vm.uiState.value.loadError)
    }
}
