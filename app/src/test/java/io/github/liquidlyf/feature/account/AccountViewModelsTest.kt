package io.github.liquidlyf.feature.account

import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAccountDeleter
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.FieldCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()
    private val deleter = FakeAccountDeleter()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun signedIn(): String {
        auth.accounts["rahim@example.test"] = FakeAuthRepository.Account("u1", "old-password", "Rahim", verified = true)
        auth.signInEmail("rahim@example.test", "old-password")
        return "u1"
    }

    // ---- change password ----

    @Test
    fun `change password checks the current one first`() = test {
        signedIn()
        val vm = ChangePasswordViewModel(auth)
        vm.onCurrentChange("wrong")
        vm.onNewChange("short")
        vm.save()
        assertEquals(FieldCheck.ERROR, vm.uiState.value.newPassword.valueCheck) // "Use at least 8 characters."

        // D24: typed once isn't enough; the second box turns red.
        vm.onNewChange("a-new-password")
        vm.save()
        assertEquals(FieldCheck.ERROR, vm.uiState.value.newPassword.againCheck)
        assertEquals(2, vm.uiState.value.errorAttempt)
        vm.onNewAgainChange("a-new-password")
        assertEquals(FieldCheck.OK, vm.uiState.value.newPassword.againCheck)
        vm.save()
        advanceUntilIdle()
        assertEquals(R.string.error_invalid_credentials, vm.uiState.value.currentError)
        assertFalse(vm.uiState.value.done)

        vm.onCurrentChange("old-password")
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertEquals("a-new-password", auth.accounts.getValue("rahim@example.test").password)
    }

    // ---- delete account ----

    @Test
    fun `the Bangla word also confirms the deletion, in any app language (A23)`() = test {
        val uid = signedIn()
        val session = FakeSessionManager(SessionState.Ready(TestData.user(uid), TestData.profile(uid)))
        val vm = DeleteAccountViewModel(auth, deleter, session)
        vm.onWordChange(" মুছুন ")
        vm.onPasswordChange("old-password")
        vm.confirm()
        advanceUntilIdle()
        assertEquals(uid, deleter.pendingUid)
    }

    @Test
    fun `delete needs the exact word and the password, then starts and hands over to the session`() = test {
        val uid = signedIn()
        val session = FakeSessionManager(SessionState.Ready(TestData.user(uid), TestData.profile(uid)))
        val vm = DeleteAccountViewModel(auth, deleter, session)
        assertTrue(vm.uiState.value.needsPassword)

        vm.onWordChange("delet")
        vm.confirm()
        assertTrue(vm.uiState.value.wordError)
        assertEquals(R.string.error_password_required, vm.uiState.value.passwordError)

        vm.onWordChange(" delete ")
        vm.onPasswordChange("wrong")
        vm.confirm()
        advanceUntilIdle()
        assertEquals(R.string.error_invalid_credentials, vm.uiState.value.passwordError)
        assertNull(deleter.pendingUid)

        vm.onPasswordChange("old-password")
        vm.confirm()
        advanceUntilIdle()
        assertEquals(uid, deleter.pendingUid)
        assertEquals(1, session.refreshes)
    }

    @Test
    fun `the deleting screen runs on its own, and retries after a failure`() = test {
        val uid = signedIn()
        deleter.pendingUid = uid
        deleter.failWith = AppError.Network
        val session = FakeSessionManager(SessionState.DeletingAccount(TestData.user(uid)))
        val vm = DeletingAccountViewModel(auth, deleter, session)
        advanceUntilIdle()
        assertEquals(1, deleter.runs)
        assertFalse(vm.uiState.value.running)
        assertEquals(R.string.error_network, vm.uiState.value.error)
        assertFalse(vm.uiState.value.needsPassword)

        deleter.failWith = null
        vm.run()
        advanceUntilIdle()
        assertEquals(2, deleter.runs)
        assertNull(deleter.pendingUid)
    }

    @Test
    fun `a stale sign-in asks for the password, then continues`() = test {
        val uid = signedIn()
        deleter.failWith = AppError.RecentLoginRequired
        val vm = DeletingAccountViewModel(auth, deleter, FakeSessionManager(SessionState.DeletingAccount(TestData.user(uid))))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsPassword)

        deleter.failWith = null
        vm.onPasswordChange("old-password")
        vm.run()
        advanceUntilIdle()
        assertEquals(2, deleter.runs)
    }

    // ---- edit profile ----

    private val geo = BdGeoParser.parse(File("src/main/assets/bd-geo.json").readText())
    private val geoRepo = object : GeoRepository {
        override suspend fun geo(): BdGeo = geo
    }

    private fun editVm(profiles: FakeProfileRepository, session: FakeSessionManager) =
        EditProfileViewModel(session, profiles, auth, geoRepo, Clock { TestData.NOW }, EligibilityCalculator())

    @Test
    fun `edit profile starts from the profile and shows the phone the local way`() = test {
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        advanceUntilIdle()
        assertEquals("01712345678", vm.uiState.value.phone)
        assertEquals("BD30262000", vm.uiState.value.upazila)
    }

    @Test
    fun `a Google-only account has no Change email, one with a password keeps it (D30)`() = test {
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val session = FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile()))
        fun signedIn(providers: Set<String>) = FakeAuthRepository().apply {
            accounts["rahim@example.test"] = FakeAuthRepository.Account("uid-1", "pw-123456", "Rahim", verified = true, providers = providers)
            runBlocking { signInEmail("rahim@example.test", "pw-123456") }
        }
        fun vm(providers: Set<String>) = EditProfileViewModel(session, profiles, signedIn(providers), geoRepo, Clock { TestData.NOW }, EligibilityCalculator())
        assertTrue(vm(setOf("google.com")).uiState.value.googleOnly)
        assertFalse(vm(setOf("google.com", "password")).uiState.value.googleOnly)
        assertFalse(vm(setOf("password")).uiState.value.googleOnly)
    }

    @Test
    fun `a new email waits for its link, with Resend after 60 seconds (D34)`() = test {
        signedIn()
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        advanceUntilIdle()
        assertNull(vm.uiState.value.pendingEmail)

        vm.changeEmail("rahim.new@example.test", "old-password")
        runCurrent()
        assertEquals("rahim.new@example.test", vm.uiState.value.pendingEmail)
        assertEquals(60, vm.uiState.value.resendIn) // a link was just sent
        vm.resendEmailChange()
        runCurrent()
        assertEquals(1, auth.emailChanges.size) // not yet
        advanceTimeBy(60_001)
        vm.resendEmailChange()
        runCurrent()
        assertEquals(listOf("rahim.new@example.test", "rahim.new@example.test"), auth.emailChanges)
        assertEquals(R.string.verify_email_sent, vm.uiState.value.message)

        // The link opened: the line goes.
        auth.requestedEmail.value = null
        runCurrent()
        assertNull(vm.uiState.value.pendingEmail)
        advanceUntilIdle()
    }

    @Test
    fun `the 60-second wait counts from the last link, also on a new visit to the page`() = test {
        signedIn()
        // A link went out 20 seconds before the page opens.
        auth.requestedEmail.value = io.github.liquidlyf.core.data.auth.RequestedEmail("rahim.new@example.test", TestData.NOW.minusSeconds(20))
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        runCurrent()
        assertEquals(40, vm.uiState.value.resendIn)
        advanceUntilIdle()
    }

    @Test
    fun `an old request isn't shown after 3 days (D34)`() = test {
        signedIn()
        auth.requestedEmail.value = io.github.liquidlyf.core.data.auth.RequestedEmail("rahim.new@example.test", TestData.NOW.minus(java.time.Duration.ofDays(3)))
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        advanceUntilIdle()
        assertNull(vm.uiState.value.pendingEmail)
    }

    @Test
    fun `a new blood group asks for confirmation before saving`() = test {
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val session = FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile()))
        val vm = editVm(profiles, session)
        advanceUntilIdle()
        vm.edit { copy(bloodGroup = BloodGroup.O_POS) }
        vm.save()
        assertTrue(vm.uiState.value.confirmBloodGroup)
        assertEquals(BloodGroup.B_POS, profiles.profiles.getValue("uid-1").bloodGroup)

        vm.onBloodGroupConfirmed(false)
        assertEquals(BloodGroup.B_POS, vm.uiState.value.bloodGroup)

        vm.edit { copy(bloodGroup = BloodGroup.O_POS) }
        vm.save()
        vm.onBloodGroupConfirmed(true)
        advanceUntilIdle()
        assertEquals(BloodGroup.O_POS, profiles.profiles.getValue("uid-1").bloodGroup)
        assertTrue(vm.uiState.value.saved)
        assertEquals(1, session.refreshes)
    }

    @Test
    fun `adding a first blood group needs no confirmation (P1)`() = test {
        val noGroup = TestData.profile().copy(bloodGroup = null, showInDonorSearch = false)
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = noGroup }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), noGroup)))
        advanceUntilIdle()
        assertNull(vm.uiState.value.bloodGroup)
        vm.edit { copy(bloodGroup = BloodGroup.AB_NEG) }
        vm.save()
        assertFalse(vm.uiState.value.confirmBloodGroup)
        advanceUntilIdle()
        assertEquals(BloodGroup.AB_NEG, profiles.profiles.getValue("uid-1").bloodGroup)
    }

    @Test
    fun `edit profile validates and a district change clears the area`() = test {
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        advanceUntilIdle()
        vm.edit { copy(fullName = "R", phone = "123", birthYear = "2015") }
        vm.onDistrict("BD3033")
        vm.save()
        val s = vm.uiState.value
        assertEquals(R.string.error_name_invalid, s.nameError)
        assertEquals(R.string.error_phone_invalid, s.phoneError)
        assertEquals(R.string.error_birth_year, s.birthYearError)
        assertTrue(s.locationError)
        assertFalse(s.saved)
    }

    @Test
    fun `change email asks for the password, checks it, then sends the link (D35)`() = test {
        signedIn()
        val vm = editVm(FakeProfileRepository(), FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        // Both fields are checked first, under each field.
        vm.changeEmail("nope", "")
        assertEquals(R.string.error_invalid_email, vm.uiState.value.newEmailError)
        assertEquals(R.string.error_password_required, vm.uiState.value.passwordError)
        // A wrong password: the box stays, nothing is sent.
        vm.changeEmail(" new@example.test ", "not-it")
        advanceUntilIdle()
        assertEquals(AppError.InvalidCredentials.messageRes, vm.uiState.value.passwordError)
        assertFalse(vm.uiState.value.changeEmailSent)
        assertTrue(auth.emailChanges.isEmpty())
        // The right one: sent, and the box closes.
        vm.changeEmail(" new@example.test ", "old-password")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(R.string.change_email_sent, vm.uiState.value.message)
        assertTrue(vm.uiState.value.changeEmailSent)
        assertEquals(listOf("new@example.test"), auth.emailChanges)
        vm.onChangeEmailClosed()
        assertFalse(vm.uiState.value.changeEmailSent)
        advanceUntilIdle()
    }

    @Test
    fun `no network shows inside the box, which stays open (D's §31)`() = test {
        signedIn()
        val vm = editVm(FakeProfileRepository(), FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        auth.failNext = AppError.Network
        vm.changeEmail("new@example.test", "old-password")
        advanceUntilIdle()
        assertEquals(AppError.Network.messageRes, vm.uiState.value.changeEmailError)
        assertFalse(vm.uiState.value.changeEmailSent)
        assertEquals(null, vm.uiState.value.message)
        vm.onChangeEmailClosed()
        assertEquals(null, vm.uiState.value.changeEmailError)
    }

    @Test
    fun `a Resend that needs a fresh sign-in opens the box again with the address (D35)`() = test {
        signedIn()
        auth.requestedEmail.value = io.github.liquidlyf.core.data.auth.RequestedEmail("rahim.new@example.test", TestData.NOW.minusSeconds(120))
        val vm = editVm(FakeProfileRepository(), FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        runCurrent()
        auth.failNext = AppError.RecentLoginRequired
        vm.resendEmailChange()
        runCurrent()
        assertEquals("rahim.new@example.test", vm.uiState.value.askPasswordFor)
        vm.onPasswordAsked()
        assertEquals(null, vm.uiState.value.askPasswordFor)
        advanceUntilIdle()
    }


    // ---- D23 a: the weight answer in Edit profile ----

    @Test
    fun `weight no in Edit profile takes a listed donor off the list in the same save (D23)`() = test {
        val me = TestData.profile()
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = me }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), me)))
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.weightOk)
        vm.edit { copy(weightOk = false) }
        vm.save()
        advanceUntilIdle()
        val p = profiles.profiles.getValue("uid-1")
        assertEquals(false, p.weightOk)
        assertFalse(p.showInDonorSearch)
        assertTrue(vm.uiState.value.saved)
        assertFalse(vm.uiState.value.askListMe)
    }

    @Test
    fun `a birth year over 60 in Edit profile unlists too (D23)`() = test {
        val me = TestData.profile()
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = me }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), me)))
        advanceUntilIdle()
        vm.edit { copy(birthYear = "1964") }
        vm.save()
        advanceUntilIdle()
        assertFalse(profiles.profiles.getValue("uid-1").showInDonorSearch)
        assertFalse(vm.uiState.value.askListMe)
    }

    @Test
    fun `weight no then yes asks list me again after Save, and yes lists them (D23)`() = test {
        val me = TestData.profile().copy(weightOk = false, showInDonorSearch = false, consentAt = null)
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = me }
        val session = FakeSessionManager(SessionState.Ready(TestData.user(), me))
        val vm = editVm(profiles, session)
        advanceUntilIdle()
        vm.edit { copy(weightOk = true) }
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.askListMe)
        assertFalse(vm.uiState.value.saved) // the question first, then Edit profile closes
        assertEquals(true, profiles.profiles.getValue("uid-1").weightOk)
        assertFalse(profiles.profiles.getValue("uid-1").showInDonorSearch) // nothing is chosen for them

        vm.onListMeAnswered(true)
        advanceUntilIdle()
        assertTrue(profiles.profiles.getValue("uid-1").showInDonorSearch)
        assertEquals(listOf(true), profiles.listingWrites)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `weight no then yes, then Not now, leaves them unlisted (D23)`() = test {
        val me = TestData.profile().copy(weightOk = false, showInDonorSearch = false)
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = me }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), me)))
        advanceUntilIdle()
        vm.edit { copy(weightOk = true) }
        vm.save()
        advanceUntilIdle()
        vm.onListMeAnswered(false)
        advanceUntilIdle()
        assertFalse(profiles.profiles.getValue("uid-1").showInDonorSearch)
        assertTrue(profiles.listingWrites.isEmpty())
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `weight no then yes at 61 doesn't ask - age still keeps them off the list (D23)`() = test {
        val me = TestData.profile().copy(weightOk = false, showInDonorSearch = false, birthYear = 1965)
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = me }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), me)))
        advanceUntilIdle()
        vm.edit { copy(weightOk = true) }
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.askListMe)
        assertTrue(vm.uiState.value.saved)
    }
}
