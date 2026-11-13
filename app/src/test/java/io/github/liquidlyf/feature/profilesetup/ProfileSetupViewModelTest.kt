package io.github.liquidlyf.feature.profilesetup

import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Clock
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
import java.time.LocalDate

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

    private fun vm() = ProfileSetupViewModel(session, profiles, geoRepo, store, Clock { TestData.NOW })

    private fun ProfileSetupViewModel.fillAll() {
        edit { copy(bloodGroup = BloodGroup.O_NEG.code) }
        next(AppLocale.BN)
        edit { copy(fullName = "Nasrin Akter", gender = Gender.FEMALE.code, birthYear = "1998", weightOk = true) }
        next(AppLocale.BN)
        onDivision("BD30")
        onDistrict("BD3026")
        edit { copy(upazila = "BD30262000") }
        next(AppLocale.BN)
        edit { copy(phone = "01812345678", neverDonated = false, lastDonationEpochDay = LocalDate.parse("2026-06-01").toEpochDay(), listed = true) }
    }

    @Test
    fun `starts at step 1 with the name from the account`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loaded)
        assertEquals(0, vm.uiState.value.step)
        assertEquals("Rahim Uddin", vm.uiState.value.draft.fullName)
    }

    @Test
    fun `each step blocks Next until it is answered`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.BLOOD_GROUP), vm.uiState.value.errors)

        vm.edit { copy(bloodGroup = "B_POS") }
        vm.next(AppLocale.EN)
        vm.edit { copy(fullName = "R", birthYear = "2015") }
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.NAME, SetupField.GENDER, SetupField.BIRTH_YEAR, SetupField.WEIGHT), vm.uiState.value.errors)

        vm.edit { copy(fullName = "Rahim Uddin", gender = "MALE", birthYear = "1995", weightOk = false) }
        vm.next(AppLocale.EN)
        assertEquals(2, vm.uiState.value.step)
        vm.onDivision("BD20") // Chattogram division
        vm.onDistrict("BD3026") // a district from another division
        vm.edit { copy(upazila = "BD30262000") }
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.LOCATION), vm.uiState.value.errors)

        vm.onDivision("BD30")
        vm.onDistrict("BD3026")
        vm.edit { copy(upazila = "BD30262000") }
        vm.next(AppLocale.EN)
        vm.edit { copy(phone = "012345") }
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.PHONE, SetupField.LAST_DONATION, SetupField.CONSENT), vm.uiState.value.errors)
        assertTrue(profiles.created.isEmpty())
    }

    @Test
    fun `changing the division or district clears what's below it`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.onDivision("BD30")
        vm.onDistrict("BD3026")
        vm.edit { copy(upazila = "BD30262000") }
        vm.onDistrict("BD3033")
        assertNull(vm.uiState.value.draft.upazila)
        vm.onDivision("BD20")
        assertNull(vm.uiState.value.draft.district)
    }

    @Test
    fun `every step is saved, so a killed app resumes where it was`() = runTest(dispatcher) {
        val first = vm()
        advanceUntilIdle()
        first.edit { copy(bloodGroup = "A_POS") }
        first.next(AppLocale.EN)
        advanceUntilIdle()
        assertEquals(1, store.saved?.step)

        val relaunched = vm()
        advanceUntilIdle()
        assertEquals(1, relaunched.uiState.value.step)
        assertEquals("A_POS", relaunched.uiState.value.draft.bloodGroup)
        assertTrue(relaunched.back())
        assertFalse(relaunched.back())
    }

    @Test
    fun `an answer is saved at once, so a kill mid-step keeps it`() = runTest(dispatcher) {
        val first = vm()
        advanceUntilIdle()
        first.edit { copy(bloodGroup = "O_POS") }
        advanceUntilIdle()

        val relaunched = vm()
        advanceUntilIdle()
        assertEquals(0, relaunched.uiState.value.step)
        assertEquals("O_POS", relaunched.uiState.value.draft.bloodGroup)
    }

    @Test
    fun `nothing is saved before the old draft is loaded`() = runTest(dispatcher) {
        store.saved = SetupDraft(uid = "u1", step = 2, bloodGroup = "B_NEG", fullName = "Rahim Uddin")
        val vm = vm()
        vm.edit { copy(phone = "0") } // before init has loaded the draft
        advanceUntilIdle()
        assertEquals(2, store.saved?.step)
        assertEquals(2, vm.uiState.value.step)
    }

    @Test
    fun `finish writes the profile once, then clears the draft and refreshes the session`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.fillAll()
        vm.next(AppLocale.BN)
        assertTrue(vm.uiState.value.saving)
        advanceUntilIdle()

        val (uid, draft) = profiles.created.single()
        assertEquals("u1", uid)
        assertEquals("+8801812345678", draft.phone)
        assertEquals(BloodGroup.O_NEG, draft.bloodGroup)
        assertEquals(TestData.DHAKA_SOUTH, draft.area)
        assertEquals(AppLocale.BN, draft.locale)
        assertEquals(Links.TERMS_VERSION, draft.termsVersion)
        assertTrue(draft.showInDonorSearch)
        assertEquals(LocalDate.parse("2026-06-01"), draft.lastDonationAt?.atOffset(java.time.ZoneOffset.UTC)?.toLocalDate())
        assertTrue(store.cleared)
        assertNull(store.saved)
        assertEquals(1, session.refreshes)
    }

    @Test
    fun `I don't know my blood group skips step 1 and never lists (P1)`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.skipBloodGroup(AppLocale.EN)
        assertEquals(1, vm.uiState.value.step)
        vm.edit { copy(fullName = "Nasrin Akter", gender = Gender.FEMALE.code, birthYear = "1998", weightOk = true) }
        vm.next(AppLocale.EN)
        vm.onDivision("BD30")
        vm.onDistrict("BD3026")
        vm.edit { copy(upazila = "BD30262000") }
        vm.next(AppLocale.EN)
        // No listing question without a group: phone and last donation are enough.
        vm.edit { copy(phone = "01812345678", neverDonated = true) }
        vm.next(AppLocale.EN)
        advanceUntilIdle()
        val draft = profiles.created.single().second
        assertNull(draft.bloodGroup)
        assertFalse(draft.showInDonorSearch)
        assertFalse(draft.available)
    }

    @Test
    fun `picking a group after skipping clears the skip`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.skipBloodGroup(AppLocale.EN)
        assertTrue(vm.back())
        vm.edit { copy(bloodGroup = "A_NEG", bloodGroupUnknown = false) }
        assertFalse(vm.uiState.value.draft.bloodGroupUnknown)
        assertEquals("A_NEG", vm.uiState.value.draft.bloodGroup)
    }

    @Test
    fun `a Google account must accept the Terms in the last step, an email account already did`() = runTest(dispatcher) {
        val google = FakeSessionManager(SessionState.NeedsProfile(user.copy(providers = setOf("google.com"))))
        val vm = ProfileSetupViewModel(google, profiles, geoRepo, store, Clock { TestData.NOW })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsTerms)
        vm.fillAll()
        vm.next(AppLocale.EN)
        assertEquals(setOf(SetupField.TERMS), vm.uiState.value.errors)
        vm.edit { copy(termsAccepted = true) }
        vm.next(AppLocale.EN)
        advanceUntilIdle()
        assertEquals(1, profiles.created.size)

        assertFalse(vm().uiState.value.needsTerms)
    }

    @Test
    fun `not listed means not available, and a failed save can be retried`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        vm.fillAll()
        vm.edit { copy(listed = false, neverDonated = true) }
        profiles.failNext = AppError.Network
        vm.next(AppLocale.EN)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.saving)
        assertEquals(AppError.Network.messageRes, vm.uiState.value.formError)

        vm.next(AppLocale.EN)
        advanceUntilIdle()
        val draft = profiles.created.single().second
        assertFalse(draft.showInDonorSearch)
        assertFalse(draft.available)
        assertNull(draft.lastDonationAt)
    }
}
