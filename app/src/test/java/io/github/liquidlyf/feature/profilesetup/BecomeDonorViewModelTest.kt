package io.github.liquidlyf.feature.profilesetup

import androidx.lifecycle.SavedStateHandle
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** "Become a donor" (D1): the donor questions, saved as one profile edit. */
@OptIn(ExperimentalCoroutinesApi::class)
class BecomeDonorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    /** A requester from the short setup: no group, no donor details, not listed. */
    private val requester = TestData.profile(uid = "me").copy(
        bloodGroup = null, gender = null, birthYear = null, weightOk = null,
        available = false, showInDonorSearch = false, consentAt = null, lastDonationAt = null,
    )
    private val session = FakeSessionManager(SessionState.Ready(TestData.user(uid = "me"), requester))
    private val profiles = FakeProfileRepository().apply { profiles["me"] = requester }
    private val saved = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = BecomeDonorViewModel(saved, session, profiles, Clock { TestData.NOW })

    private fun TestScope.events(vm: BecomeDonorViewModel): List<BecomeDonorEvent> =
        mutableListOf<BecomeDonorEvent>().also { list -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.eventFlow.collect { list += it } } }

    @Test
    fun `each step blocks Next until it is answered, and Back steps back`() = runTest(dispatcher) {
        val vm = vm()
        vm.next()
        assertEquals(setOf(SetupField.BLOOD_GROUP), vm.uiState.value.errors)
        assertEquals(1, vm.uiState.value.errorAttempt)
        vm.edit { copy(bloodGroup = BloodGroup.O_NEG.code) }
        vm.next()
        assertEquals(1, vm.uiState.value.step)

        vm.next()
        assertEquals(setOf(SetupField.GENDER, SetupField.BIRTH_YEAR, SetupField.WEIGHT), vm.uiState.value.errors)
        vm.edit { copy(gender = Gender.FEMALE.code, birthYear = "2015", weightOk = true) }
        vm.next()
        assertEquals(setOf(SetupField.BIRTH_YEAR), vm.uiState.value.errors) // under 18
        vm.edit { copy(birthYear = "1998") }
        vm.next()
        assertEquals(2, vm.uiState.value.step)

        vm.next()
        assertEquals(setOf(SetupField.LAST_DONATION, SetupField.CONSENT), vm.uiState.value.errors)
        assertTrue(vm.back())
        assertEquals(1, vm.uiState.value.step)
        assertTrue(vm.back())
        assertFalse(vm.back()) // the first step: the screen closes
    }

    @Test
    fun `finishing adds every detail in one edit, lists with consent, and goes back`() = runTest(dispatcher) {
        val vm = vm()
        val events = events(vm)
        vm.edit { copy(bloodGroup = BloodGroup.O_NEG.code) }
        vm.next()
        vm.edit { copy(gender = Gender.FEMALE.code, birthYear = "1998", weightOk = true) }
        vm.next()
        vm.edit { copy(neverDonated = false, lastDonationEpochDay = LocalDate.parse("2026-06-01").toEpochDay(), listed = true) }
        vm.next()
        advanceUntilIdle()

        val p = profiles.profiles.getValue("me")
        assertTrue(p.hasDonorDetails)
        assertEquals(BloodGroup.O_NEG, p.bloodGroup)
        assertEquals(Gender.FEMALE, p.gender)
        assertEquals(1998, p.birthYear)
        assertTrue(p.showInDonorSearch)
        assertTrue(p.available)
        assertNotNull(p.consentAt) // listing records the opt-in (PDPA)
        assertNotNull(p.lastDonationAt)
        assertEquals("Rahim Uddin", p.fullName) // the rest of the profile stays
        assertEquals(1, session.refreshes)
        assertEquals(listOf<BecomeDonorEvent>(BecomeDonorEvent.Done), events)
    }

    @Test
    fun `without a known group the details are saved but nobody is listed (P1)`() = runTest(dispatcher) {
        val vm = vm()
        vm.skipBloodGroup()
        assertEquals(1, vm.uiState.value.step)
        vm.edit { copy(gender = Gender.MALE.code, birthYear = "1990", weightOk = true) }
        vm.next()
        vm.edit { copy(neverDonated = true) }
        vm.next() // no listing question without a group
        advanceUntilIdle()
        val p = profiles.profiles.getValue("me")
        assertTrue(p.hasDonorDetails)
        assertNull(p.bloodGroup)
        assertFalse(p.showInDonorSearch)
        assertFalse(p.available)
    }

    @Test
    fun `a failed save says why, and the answers survive process death`() = runTest(dispatcher) {
        profiles.failNext = AppError.Network
        val vm = vm()
        vm.edit { copy(bloodGroup = BloodGroup.A_POS.code) }
        vm.next()
        val again = vm() // the same SavedStateHandle, like after process death
        assertEquals(1, again.uiState.value.step)
        assertEquals(BloodGroup.A_POS.code, again.uiState.value.draft.bloodGroup)
        again.edit { copy(gender = Gender.MALE.code, birthYear = "1990", weightOk = false) }
        again.next()
        again.edit { copy(neverDonated = true, listed = false) }
        again.next()
        advanceUntilIdle()
        assertEquals(AppError.Network.messageRes, again.uiState.value.formError)
        assertFalse(again.uiState.value.saving)
    }
}
