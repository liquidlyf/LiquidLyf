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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
        assertEquals(R.string.error_password_short, vm.uiState.value.newError)

        vm.onNewChange("a-new-password")
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
    fun `delete needs the exact word and the password, then starts and hands over to the session`() = test {
        val uid = signedIn()
        val session = FakeSessionManager(SessionState.Ready(TestData.user(uid), TestData.profile(uid)))
        val vm = DeleteAccountViewModel(auth, deleter, session)
        assertTrue(vm.uiState.value.needsPassword)

        vm.onWordChange("delet")
        vm.confirm("DELETE")
        assertTrue(vm.uiState.value.wordError)
        assertEquals(R.string.error_password_required, vm.uiState.value.passwordError)

        vm.onWordChange(" delete ")
        vm.onPasswordChange("wrong")
        vm.confirm("DELETE")
        advanceUntilIdle()
        assertEquals(R.string.error_invalid_credentials, vm.uiState.value.passwordError)
        assertNull(deleter.pendingUid)

        vm.onPasswordChange("old-password")
        vm.confirm("DELETE")
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
        EditProfileViewModel(session, profiles, auth, geoRepo, Clock { TestData.NOW })

    @Test
    fun `edit profile starts from the profile and shows the phone the local way`() = test {
        val profiles = FakeProfileRepository().apply { profiles["uid-1"] = TestData.profile() }
        val vm = editVm(profiles, FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        advanceUntilIdle()
        assertEquals("01712345678", vm.uiState.value.phone)
        assertEquals("BD30262000", vm.uiState.value.upazila)
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
    fun `change email sends a link to the new address`() = test {
        signedIn()
        val vm = editVm(FakeProfileRepository(), FakeSessionManager(SessionState.Ready(TestData.user(), TestData.profile())))
        vm.changeEmail("nope")
        assertEquals(R.string.error_invalid_email, vm.uiState.value.message)
        vm.changeEmail(" new@example.test ")
        advanceUntilIdle()
        assertEquals(R.string.change_email_sent, vm.uiState.value.message)
        assertEquals(listOf("new@example.test"), auth.emailChanges)
    }
}
