package io.github.liquidlyf.feature.account

import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.auth.GoogleSignInResult
import io.github.liquidlyf.testing.FakeAuthRepository
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

/** P3: a Google-only account adds a password after Google confirms who it is. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddPasswordViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun signedInWithGoogle(): AddPasswordViewModel {
        auth.signInWithGoogle("tok")
        assertFalse(auth.authState.value!!.hasPassword)
        return AddPasswordViewModel(auth)
    }

    @Test
    fun `both fields are checked before Google is asked`() = runTest(dispatcher) {
        val vm = signedInWithGoogle()
        assertEquals("google-tok@example.test", vm.uiState.value.email)
        vm.onPassword("short")
        assertFalse(vm.readyToConfirm())
        assertEquals(R.string.error_password_short, vm.uiState.value.passwordError)

        vm.onPassword("long enough pw")
        vm.onConfirm("long enough pv")
        assertFalse(vm.readyToConfirm())
        assertEquals(R.string.error_passwords_differ, vm.uiState.value.confirmError)

        vm.onConfirm("long enough pw")
        assertTrue(vm.readyToConfirm())
        assertTrue(vm.uiState.value.working)
        assertFalse("no second sheet while one is open", vm.readyToConfirm())
    }

    @Test
    fun `closing the Google sheet just stops, and no Google account says so`() = runTest(dispatcher) {
        val vm = signedInWithGoogle()
        vm.onPassword("long enough pw")
        vm.onConfirm("long enough pw")
        assertTrue(vm.readyToConfirm())
        vm.onGoogleResult(GoogleSignInResult.Cancelled)
        assertFalse(vm.uiState.value.working)
        assertNull(vm.uiState.value.formError)

        assertTrue(vm.readyToConfirm())
        vm.onGoogleResult(GoogleSignInResult.NoAccount)
        assertEquals(R.string.error_google_no_account, vm.uiState.value.formError)
        assertFalse(auth.authState.value!!.hasPassword)
    }

    @Test
    fun `the same Google account adds the password, and Change password appears`() = runTest(dispatcher) {
        val vm = signedInWithGoogle()
        vm.onPassword("long enough pw")
        vm.onConfirm("long enough pw")
        assertTrue(vm.readyToConfirm())
        vm.onGoogleResult(GoogleSignInResult.Token("tok"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        assertFalse(vm.uiState.value.working)
        assertTrue(auth.authState.value!!.hasPassword)
        assertEquals("long enough pw", auth.accounts.getValue("google-tok@example.test").password)
    }

    @Test
    fun `a different Google account is refused`() = runTest(dispatcher) {
        val vm = signedInWithGoogle()
        vm.onPassword("long enough pw")
        vm.onConfirm("long enough pw")
        assertTrue(vm.readyToConfirm())
        vm.onGoogleResult(GoogleSignInResult.Token("someone-else"))
        advanceUntilIdle()
        assertEquals(R.string.add_password_wrong_account, vm.uiState.value.formError)
        assertFalse(vm.uiState.value.done)
        assertFalse(auth.authState.value!!.hasPassword)
    }
}
