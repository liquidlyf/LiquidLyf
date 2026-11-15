package io.github.liquidlyf.feature.profile

import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.Badge
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profiles = FakeProfileRepository()
    private val me = TestData.profile(uid = "me")
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }

    /** Like the real session: refresh() reads the profile again after a write. */
    private val session = object : SessionManager {
        override val state = MutableStateFlow<SessionState>(SessionState.Ready(TestData.user(uid = "me"), me))
        override val signingOut = MutableStateFlow(false)
        override fun refresh() {
            state.value = SessionState.Ready(TestData.user(uid = "me"), profiles.profiles.getValue("me"))
        }
        override suspend fun signOut() = Unit
    }

    private fun vm() = ProfileViewModel(session, profiles, geoRepo, EligibilityCalculator(), Clock { TestData.NOW })

    private fun daysAgo(days: Long) = TestData.NOW.minus(Duration.ofDays(days))

    private fun verified(id: String, daysAgo: Long) =
        Donation(id, daysAgo(daysAgo), BloodComponent.WHOLE_BLOOD, "DMCH", requestId = id, verified = true, source = DonationSource.REQUEST, createdAt = daysAgo(daysAgo))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        profiles.profiles["me"] = me
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the profile shows eligibility, impact and badges from the history`() = runTest(dispatcher) {
        profiles.donationList["me"] = mutableListOf(verified("r2", 30), verified("r1", 400))
        val withHistory = me.copy(donationCount = 2, lastDonationAt = daysAgo(30))
        profiles.profiles["me"] = withHistory // the server has the same copy as the session
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), withHistory)
        val vm = vm()
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf("r2", "r1"), s.donations?.map { it.id })
        assertEquals(2, s.impact.donations)
        assertEquals(6, s.impact.livesHelped)
        assertEquals(listOf(Badge.FIRST_DROP), s.badges)
        assertTrue(IneligibleReason.REST_PERIOD in s.eligibility!!.reasons)
        assertEquals(daysAgo(30).plus(Duration.ofDays(120)), s.eligibility!!.eligibleFrom)
        assertNotNull(s.geo)
    }

    @Test
    fun `opening the profile reads it from the server once, so a Worker-set flag shows`() = runTest(dispatcher) {
        // The session's copy is cached; on the server the Worker has since set rapidResponder.
        profiles.profiles["me"] = me.copy(rapidResponder = true)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(Badge.RAPID_RESPONDER), vm.uiState.value.badges)
    }

    @Test
    fun `the Rapid responder badge follows the profile's flag from the Worker`() = runTest(dispatcher) {
        profiles.profiles["me"] = me.copy(rapidResponder = true)
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(rapidResponder = true))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(Badge.RAPID_RESPONDER), vm.uiState.value.badges)
    }

    @Test
    fun `a newer past donation asks first, and updating restarts the rest period`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(emptyList<Donation>(), vm.uiState.value.donations)
        assertTrue(vm.uiState.value.eligibility!!.eligibleNow)

        val draft = DonationDraft(daysAgo(10), BloodComponent.WHOLE_BLOOD, "  Jashore Sadar Hospital ")
        vm.addDonation(draft)
        advanceUntilIdle()
        assertEquals(draft, vm.uiState.value.pendingDonation)
        assertEquals(daysAgo(10).plus(Duration.ofDays(120)), vm.eligibleAfter(draft))
        assertTrue("nothing saved before the answer", profiles.donationList["me"].isNullOrEmpty())

        vm.answerEligibility(update = true)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertNull(s.pendingDonation)
        assertEquals(daysAgo(10), profiles.profiles.getValue("me").lastDonationAt)
        assertEquals("Jashore Sadar Hospital", s.donations!!.single().hospitalName)
        assertFalse(s.donations!!.single().verified)
        assertTrue(IneligibleReason.REST_PERIOD in s.eligibility!!.reasons)
        assertEquals(listOf(Badge.FIRST_DROP), s.badges)
    }

    @Test
    fun `keeping eligibility as is saves the donation without moving the date`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.addDonation(DonationDraft(daysAgo(10), BloodComponent.PLATELETS, null))
        vm.answerEligibility(update = false)
        advanceUntilIdle()
        assertNull(profiles.profiles.getValue("me").lastDonationAt)
        assertEquals(1, vm.uiState.value.donations!!.size)
        assertTrue(vm.uiState.value.eligibility!!.eligibleNow)
    }

    @Test
    fun `an older past donation is saved without asking`() = runTest(dispatcher) {
        session.state.value = SessionState.Ready(TestData.user(uid = "me"), me.copy(lastDonationAt = daysAgo(30)))
        profiles.profiles["me"] = me.copy(lastDonationAt = daysAgo(30))
        val vm = vm()
        advanceUntilIdle()
        vm.addDonation(DonationDraft(daysAgo(300), BloodComponent.WHOLE_BLOOD, null))
        advanceUntilIdle()
        assertNull(vm.uiState.value.pendingDonation)
        assertEquals(daysAgo(30), profiles.profiles.getValue("me").lastDonationAt)
        assertEquals(1, vm.uiState.value.donations!!.size)
    }

    @Test
    fun `a self-reported donation can be removed`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.addDonation(DonationDraft(daysAgo(10), BloodComponent.WHOLE_BLOOD, null))
        vm.answerEligibility(update = false)
        advanceUntilIdle()
        val d = vm.uiState.value.donations!!.single()
        vm.removeDonation(d)
        advanceUntilIdle()
        assertEquals(emptyList<Donation>(), vm.uiState.value.donations)
    }

    @Test
    fun `dates from the sheet never land in the future or before the donor turned 18`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(LocalDate.of(me.birthYear + 18, 1, 1), vm.earliestDonationDate())
        // Today: capped a few minutes before now, so the rules never see a future time.
        val today = TestData.NOW.atOffset(ZoneOffset.UTC).toLocalDate()
        assertTrue(vm.donationInstant(today, ZoneOffset.UTC) < TestData.NOW)
        // A past day: midday that day.
        assertEquals(today.minusDays(3).atTime(12, 0).toInstant(ZoneOffset.UTC), vm.donationInstant(today.minusDays(3), ZoneOffset.UTC))
        assertEquals(today, vm.dateOf(today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
    }
}
