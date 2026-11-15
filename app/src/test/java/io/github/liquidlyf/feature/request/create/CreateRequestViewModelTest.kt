package io.github.liquidlyf.feature.request.create

import androidx.lifecycle.SavedStateHandle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.hospital.Hospital
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.PlaceName
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.model.UserStats
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAlertStore
import io.github.liquidlyf.testing.FakeHospitalRepository
import io.github.liquidlyf.testing.FakeNetworkMonitor
import io.github.liquidlyf.testing.FakeReminders
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeWorkerClient
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.io.File
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class CreateRequestViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val profile = TestData.profile(uid = "me").copy(createdAt = TestData.NOW.minus(Duration.ofDays(30)))
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), profile))
    private val profiles = FakeProfileRepository().apply { profiles["me"] = profile }
    private val requests = FakeRequestRepository()
    private val worker = FakeWorkerClient()
    private val alerts = FakeAlertStore()
    private val reminders = FakeReminders()
    private val dmch = Hospital("h1", PlaceName("Dhaka Medical College Hospital", null), "Bakshibazar", "BD3026", "BD30262000", null)
    private val hospitals = FakeHospitalRepository(listOf(dmch))
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }
    private var saved = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(appScope: CoroutineScope = backgroundScope) =
        CreateRequestViewModel(saved, session, profiles, requests, hospitals, geoRepo, worker, Clock { TestData.NOW }, appScope, FakeNetworkMonitor(), alerts, reminders)

    private fun TestScope.events(vm: CreateRequestViewModel): List<CreateRequestEvent> =
        mutableListOf<CreateRequestEvent>().also { list -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { list += it } } }

    private fun CreateRequestViewModel.fillAll() {
        edit { copy(bloodGroup = BloodGroup.O_NEG.code, units = 2) }
        next()
        onHospitalTyped("Dhaka Medical College Hospital")
        edit { copy(urgency = Urgency.CRITICAL.code) }
        next()
        edit { copy(genuine = true, notes = "  Ward 5  ") }
    }

    @Test
    fun `starts on step 1 with my area and contact filled in`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        val f = vm.uiState.value.form
        assertTrue(vm.uiState.value.loaded)
        assertNull(vm.uiState.value.limit)
        assertEquals(0, f.step)
        assertEquals(TestData.DHAKA_SOUTH.district, f.district)
        assertEquals("Rahim Uddin", f.contactName)
        assertEquals("01712345678", f.contactPhone)
    }

    @Test
    fun `each step blocks Next until it is answered`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.next()
        assertEquals(setOf(RequestField.BLOOD_GROUP), vm.uiState.value.errors)
        vm.edit { copy(bloodGroup = "A_POS") }
        vm.next()
        assertEquals(1, vm.uiState.value.step)

        vm.next()
        assertEquals(setOf(RequestField.HOSPITAL, RequestField.URGENCY), vm.uiState.value.errors)
        vm.onHospitalTyped("Square Hospital")
        vm.edit { copy(urgency = Urgency.PLANNED.code, plannedAtMillis = TestData.NOW.plusSeconds(60).toEpochMilli()) }
        vm.next()
        assertEquals(setOf(RequestField.PLANNED_AT), vm.uiState.value.errors) // under 15 minutes ahead
        vm.edit { copy(plannedAtMillis = TestData.NOW.plus(Duration.ofDays(2)).toEpochMilli()) }
        vm.next()
        assertEquals(2, vm.uiState.value.step)

        vm.edit { copy(contactPhone = "123") }
        vm.next()
        assertEquals(setOf(RequestField.CONTACT_PHONE, RequestField.GENUINE), vm.uiState.value.errors)
        assertTrue(requests.created.isEmpty())
    }

    @Test
    fun `the form survives rotation and process death`() = runTest(dispatcher) {
        val first = vm()
        advanceUntilIdle()
        first.edit { copy(bloodGroup = "AB_NEG", units = 3, component = BloodComponent.PLATELETS.code) }
        first.next()

        val again = vm()
        advanceUntilIdle()
        val f = again.uiState.value.form
        assertEquals(1, f.step)
        assertEquals("AB_NEG", f.bloodGroup)
        assertEquals(3, f.units)
        assertEquals(BloodComponent.PLATELETS.code, f.component)
    }

    @Test
    fun `at the open-request limit the form explains instead (3, or 1 in the first week)`() = runTest(dispatcher) {
        profiles.stats["me"] = UserStats(openRequests = 3, totalRequests = 9, lastRequestId = null)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(OpenRequestLimit(max = 3, newAccount = false), vm.uiState.value.limit)

        val newcomer = profile.copy(createdAt = TestData.NOW.minus(Duration.ofDays(2)))
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), newcomer)
        profiles.stats["me"] = UserStats(openRequests = 1, totalRequests = 1, lastRequestId = null)
        val vm2 = vm()
        advanceUntilIdle()
        assertEquals(OpenRequestLimit(max = 1, newAccount = true), vm2.uiState.value.limit)
    }

    @Test
    fun `a note that mentions money asks first, and is posted only on Post anyway`() = runTest(dispatcher) {
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.fillAll()
        vm.edit { copy(notes = "Pay via bKash after") }
        vm.next()
        advanceUntilIdle()
        assertTrue("7.8: the warning shows", vm.uiState.value.moneyWarning)
        assertTrue("nothing posted yet", requests.created.isEmpty())

        vm.changeNote()
        assertFalse(vm.uiState.value.moneyWarning)
        vm.next()
        assertTrue("still asks while the note mentions money", vm.uiState.value.moneyWarning)

        vm.postAnyway()
        advanceUntilIdle()
        assertEquals("Pay via bKash after", requests.created.single().second.notes)
        assertEquals(1, events.size)
    }

    @Test
    fun `posting writes one request, dispatches it and opens it`() = runTest(dispatcher) {
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.fillAll()
        vm.next()
        assertTrue(vm.uiState.value.posting)
        advanceUntilIdle()

        val (id, draft) = requests.created.single()
        assertEquals(BloodGroup.O_NEG, draft.bloodGroup)
        assertEquals(2, draft.unitsNeeded)
        assertEquals("+8801712345678", draft.contactPhone)
        assertEquals("Ward 5", draft.notes)
        assertEquals(TestData.NOW.plus(Duration.ofHours(6)), draft.neededBy)
        assertEquals(listOf(id), worker.dispatched)
        assertEquals(listOf(CreateRequestEvent.Posted(id, null)), events)
        assertNull(saved.get<String>(CreateRequestViewModel.FORM_KEY))
        // Its alert also reaches my phone (I may be a matching donor): it's remembered as mine.
        assertTrue(alerts.isMyRequest(id))
        // "Ends in 2 hours" is scheduled against its needed-by time.
        assertEquals(draft.neededBy, reminders.ending[id])
    }

    @Test
    fun `over the daily alert cap it is posted, with a notice`() = runTest(dispatcher) {
        worker.dispatchResult = AppResult.Failure(AppError.DailyAlertLimit)
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.fillAll()
        vm.next()
        advanceUntilIdle()
        val id = requests.created.single().first
        assertEquals(listOf(CreateRequestEvent.Posted(id, PostNotice.DAILY_ALERT_LIMIT)), events)
    }

    @Test
    fun `offline, the request opens as waiting and goes out when the network is back`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        requests.createGate = gate
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.fillAll()
        vm.next()
        advanceTimeBy(CreateRequestViewModel.POST_WAIT_MS + 1)
        val id = (events.single() as CreateRequestEvent.Posted).requestId
        assertEquals(PostNotice.WAITING_FOR_NETWORK, (events.single() as CreateRequestEvent.Posted).notice)
        assertTrue(worker.dispatched.isEmpty())
        gate.complete(Unit) // back online: the write lands and the Worker is called
        runCurrent() // runs the app-scope (background) work; advanceUntilIdle() stops before it
        assertEquals(id, requests.created.single().first)
        assertEquals(listOf(id), worker.dispatched)
    }

    @Test
    fun `a refused post shows the error and can be retried`() = runTest(dispatcher) {
        requests.failNext = AppError.PermissionDenied
        val vm = vm()
        val events = events(vm)
        advanceUntilIdle()
        vm.fillAll()
        vm.next()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.posting)
        assertEquals(R.string.error_permission_denied, vm.uiState.value.formError)
        assertTrue(events.isEmpty())
        assertTrue(worker.dispatched.isEmpty())

        vm.next()
        advanceUntilIdle()
        assertEquals(1, requests.created.size)
    }

    @Test
    fun `hospital search waits for typing to stop, and a pick ends it`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onHospitalTyped("D")
        vm.onHospitalTyped("Dh")
        vm.onHospitalTyped("Dhaka")
        advanceUntilIdle()
        assertEquals(listOf("Dhaka"), hospitals.queries)
        assertEquals(listOf(dmch), vm.uiState.value.hospitals)

        vm.onHospitalPicked(dmch, "Dhaka Medical College Hospital")
        advanceUntilIdle()
        assertEquals("h1", vm.uiState.value.form.hospitalId)
        assertTrue(vm.uiState.value.hospitals.isEmpty())

        vm.onHospitalTyped("Dhaka Medical College Hospital 2") // typing again makes it "Other"
        assertNull(vm.uiState.value.form.hospitalId)
    }
}
