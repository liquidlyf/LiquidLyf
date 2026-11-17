package io.github.liquidlyf.feature.profilesetup

import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
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

/** The short setup (FLOW-01, D1 = a): name, mobile and area on one screen; no donor questions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val user = TestData.user(uid = "u1")
    private val session = FakeSessionManager(SessionState.NeedsProfile(user))
    private val profiles = FakeProfileRepository()
    private val store = object : SetupDraftStore {
        var saved: SetupDraft? = null
        var cleared = false
        override suspend fun load(uid: String) = saved?.takeIf { it.uid == uid }
        override suspend fun save(draft: SetupDraft) {
            saved = draft
        }
        override suspend fun clear() {
            cleared = true
            saved = null
        }
    }
    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(s: FakeSessionManager = session) = ProfileSetupViewModel(s, profiles, geoRepo, store)

    private fun ProfileSetupViewModel.fillAll() {
        edit { copy(fullName = "Nasrin Akter", phone = "01812345678") }
        onDivision("BD30")
        onDistrict("BD3026")
        edit { copy(upazila = "BD30262000") }
    }

    @Test
    fun `starts with the name from the account`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loaded)
        assertEquals("Rahim Uddin", vm.uiState.value.draft.fullName)
        assertFalse(vm.uiState.value.needsTerms) // an email account accepted them on sign-up
    }

    @Test
    fun `Continue is blocked until the name, number and area are given`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.edit { copy(fullName = "") }
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.NAME, SetupField.PHONE, SetupField.LOCATION), vm.uiState.value.errors)
        // FLOW-04: each failed Continue is counted, so the screen brings the first error into view again.
        assertEquals(1, vm.uiState.value.errorAttempt)
        vm.edit { copy(fullName = "Nasrin Akter", phone = "123") }
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.PHONE, SetupField.LOCATION), vm.uiState.value.errors)
        assertEquals(2, vm.uiState.value.errorAttempt)
        assertTrue(profiles.created.isEmpty())
    }

    @Test
    fun `changing the division or district clears what's below it`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.fillAll()
        vm.onDistrict("BD3033")
        assertNull(vm.uiState.value.draft.upazila)
        vm.onDivision("BD40")
        assertNull(vm.uiState.value.draft.district)
    }

    @Test
    fun `an answer is saved at once, so a killed app comes back with it`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.edit { copy(phone = "01812345678") }
        advanceUntilIdle()
        assertEquals("01812345678", store.saved?.phone)
        val again = vm()
        advanceUntilIdle()
        assertEquals("01812345678", again.uiState.value.draft.phone)
    }

    @Test
    fun `nothing is saved before the old draft is loaded`() = runTest(dispatcher) {
        store.saved = SetupDraft(uid = "u1", phone = "01812345678")
        val vm = vm()
        vm.edit { copy(fullName = "Early") } // before load: not saved over the old draft
        advanceUntilIdle()
        assertEquals("01812345678", vm.uiState.value.draft.phone)
    }

    @Test
    fun `Continue writes a requester's profile once, without donor details, then moves on`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.fillAll()
        vm.next(AppLocale.BN)
        advanceUntilIdle()
        val (uid, draft) = profiles.created.single()
        assertEquals("u1", uid)
        assertEquals("Nasrin Akter", draft.fullName)
        assertEquals("+8801812345678", draft.phone)
        assertEquals("BD30262000", draft.area.upazila)
        // D1: the donor questions come later, only in "Become a donor".
        assertNull(draft.bloodGroup)
        assertNull(draft.gender)
        assertNull(draft.birthYear)
        assertNull(draft.weightOk)
        assertNull(draft.lastDonationAt)
        assertFalse(draft.showInDonorSearch)
        assertFalse(draft.available)
        assertEquals(AppLocale.BN, draft.locale)
        assertEquals(Links.TERMS_VERSION, draft.termsVersion)
        assertTrue(store.cleared)
        assertTrue(vm.uiState.value.saving) // stays saving while the session moves on
        assertEquals(1, session.refreshes)
    }

    @Test
    fun `a Google account must accept the Terms here, an email account already did`() = runTest(dispatcher) {
        val google = FakeSessionManager(SessionState.NeedsProfile(user.copy(providers = setOf("google.com"))))
        val vm = vm(google)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsTerms)
        vm.fillAll()
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.TERMS), vm.uiState.value.errors)
        vm.edit { copy(termsAccepted = true) }
        vm.next(AppLocale.EN)
        advanceUntilIdle()
        assertEquals(1, profiles.created.size)
    }

    @Test
    fun `a failed save says why and can be retried`() = runTest(dispatcher) {
        profiles.failNext = AppError.Network
        val vm = vm()
        advanceUntilIdle()
        vm.fillAll()
        vm.next(AppLocale.EN)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.saving)
        assertEquals(AppError.Network.messageRes, vm.uiState.value.formError)
        vm.next(AppLocale.EN)
        advanceUntilIdle()
        assertEquals(1, profiles.created.size)
    }
}
