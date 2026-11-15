package io.github.liquidlyf.feature.moderation

import io.github.liquidlyf.core.data.moderation.RequestAction
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.Report
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.ReportStatus
import io.github.liquidlyf.core.model.ReportTarget
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.testing.FakeModerationRepository
import io.github.liquidlyf.testing.FakeProfileRepository
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
import org.junit.Before
import org.junit.Test
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ModerationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val moderation = FakeModerationRepository()
    private val requests = FakeRequestRepository()
    private val profiles = FakeProfileRepository()
    private val admin = SessionState.Ready(TestData.user(uid = "admin"), TestData.profile(uid = "admin"), admin = true)

    private fun report(id: String, target: String, reason: ReportReason = ReportReason.FAKE_REQUEST, reporter: String = "rep-$id", minutesAgo: Long = 10, type: ReportTarget = ReportTarget.REQUEST) =
        Report(id, reporter, type, target, reason, null, TestData.NOW.minus(Duration.ofMinutes(minutesAgo)), ReportStatus.OPEN)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `money asked of a donor who offered comes first, then requests that mention money, then the newest`() = runTest(dispatcher) {
        val offeredOn = TestData.request(id = "r-money").copy(
            responders = mapOf("donor-1" to Responder("Donor", io.github.liquidlyf.core.model.BloodGroup.B_POS, ResponseStatus.OFFERED)),
        )
        requests.put(offeredOn, TestData.request(id = "r-flagged").copy(flagged = true), TestData.request(id = "r-plain"))
        profiles.profiles["pest"] = TestData.profile(uid = "pest").copy(fullName = "Pest Person")
        moderation.reports += listOf(
            report("1", "r-plain", minutesAgo = 1),
            report("2", "r-flagged", minutesAgo = 50),
            report("3", "r-money", ReportReason.ASKING_MONEY, reporter = "donor-1", minutesAgo = 90),
            report("4", "pest", ReportReason.ABUSIVE, minutesAgo = 5, type = ReportTarget.USER),
        )
        val vm = ModerationViewModel(FakeSessionManager(admin), moderation, requests, profiles)
        advanceUntilIdle()
        val items = vm.uiState.value.items!!
        assertEquals(listOf("3", "2", "1", "4"), items.map { it.report.id })
        assertEquals("Pest Person", items.last().personName)
        assertEquals(true, items.first().reporterOffered)
    }

    @Test
    fun `without the admin claim nothing is read`() = runTest(dispatcher) {
        moderation.reports += report("1", "r1")
        val vm = ModerationViewModel(FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), TestData.profile(uid = "me"))), moderation, requests, profiles)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.admin)
        assertNull(vm.uiState.value.items)
    }

    @Test
    fun `each action goes to the repository as the admin, and the list reloads`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "r1"), TestData.request(id = "r2", status = RequestStatus.REMOVED))
        moderation.reports += listOf(report("1", "r1"), report("2", "r2"), report("3", "pest", type = ReportTarget.USER))
        val vm = ModerationViewModel(FakeSessionManager(admin), moderation, requests, profiles)
        advanceUntilIdle()
        val byId = vm.uiState.value.items!!.associateBy { it.report.id }

        vm.act(byId.getValue("1"), RequestAction.HIDE, "fake")
        advanceUntilIdle()
        vm.act(byId.getValue("2"), RequestAction.CANCEL, null)
        advanceUntilIdle()
        vm.markHandled(byId.getValue("3"), "banned with the script")
        advanceUntilIdle()
        assertEquals(listOf("HIDE r1 by admin", "CANCEL r2 by admin", "HANDLED 3 by admin"), moderation.actions)
        // Hidden is not decided: its report stays in the list for Restore or Cancel.
        assertEquals(listOf("1"), vm.uiState.value.items!!.map { it.report.id })
    }

    @Test
    fun `all of a request's open reports are decided together`() = runTest(dispatcher) {
        requests.put(TestData.request(id = "r1", status = RequestStatus.REMOVED), TestData.request(id = "r2"))
        moderation.reports += listOf(report("1", "r1"), report("2", "r1"), report("3", "r1"), report("4", "r2"))
        val vm = ModerationViewModel(FakeSessionManager(admin), moderation, requests, profiles)
        advanceUntilIdle()

        vm.act(vm.uiState.value.items!!.first { it.report.id == "2" }, RequestAction.RESTORE, "fine")
        advanceUntilIdle()
        assertEquals(listOf("1", "2", "3"), moderation.decided.getValue("r1").sorted())
        assertEquals(listOf("4"), vm.uiState.value.items!!.map { it.report.id })
    }
}
