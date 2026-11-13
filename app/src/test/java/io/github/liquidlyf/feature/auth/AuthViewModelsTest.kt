package io.github.liquidlyf.feature.auth

import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.auth.GoogleSignInResult
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val auth = FakeAuthRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    // ---- sign in ----

    @Test
    fun `sign in checks the fields before calling Firebase`() = test {
        val vm = SignInViewModel(auth)
        vm.signIn()
        assertEquals(R.string.error_email_required, vm.uiState.value.emailError)
        assertEquals(R.string.error_password_required, vm.uiState.value.passwordError)

        vm.onEmailChange("not-an-email")
        vm.onPasswordChange("x")
        vm.signIn()
        assertEquals(R.string.error_invalid_email, vm.uiState.value.emailError)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `a wrong password shows under the password field and loading ends`() = test {
        auth.accounts["rahim@example.test"] = FakeAuthRepository.Account("u1", "right-pass", "Rahim", verified = true)
        val vm = SignInViewModel(auth)
        vm.onEmailChange("rahim@example.test")
        vm.onPasswordChange("wrong-pass")
        vm.signIn()
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()
        assertEquals(R.string.error_invalid_credentials, vm.uiState.value.passwordError)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `success stays loading (no double submit) while the gate moves on`() = test {
        auth.accounts["rahim@example.test"] = FakeAuthRepository.Account("u1", "right-pass", "Rahim", verified = true)
        val vm = SignInViewModel(auth)
        vm.onEmailChange(" rahim@example.test ")
        vm.onPasswordChange("right-pass")
        vm.signIn()
        vm.signIn() // a second tap is ignored
        advanceUntilIdle()
        assertTrue(vm.uiState.value.loading)
        assertEquals("u1", auth.authState.value?.uid)
    }

    @Test
    fun `Google sign-in - a token signs in, cancel says nothing, problems are explained`() = test {
        val vm = SignInViewModel(auth)
        assertTrue(vm.onGoogleStarted())
        assertFalse(vm.onGoogleStarted()) // busy: a second tap is ignored
        vm.onGoogleResult(GoogleSignInResult.Cancelled)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.formError)

        vm.onGoogleStarted()
        vm.onGoogleResult(GoogleSignInResult.NoAccount)
        advanceUntilIdle()
        assertEquals(R.string.error_google_no_account, vm.uiState.value.formError)
        vm.onGoogleStarted()
        vm.onGoogleResult(GoogleSignInResult.Failed)
        advanceUntilIdle()
        assertEquals(R.string.error_google_failed, vm.uiState.value.formError)

        vm.onGoogleStarted()
        vm.onGoogleResult(GoogleSignInResult.Token("abc"))
        advanceUntilIdle()
        assertEquals("g-abc", auth.authState.value?.uid)
        assertEquals(false, auth.authState.value?.hasPassword)
        assertTrue(vm.uiState.value.loading)
    }

    @Test
    fun `a network error is shown for the whole form, typing clears it`() = test {
        auth.failNext = AppError.Network
        val vm = SignInViewModel(auth)
        vm.onEmailChange("rahim@example.test")
        vm.onPasswordChange("pass")
        vm.signIn()
        advanceUntilIdle()
        assertEquals(R.string.error_network, vm.uiState.value.formError)
        vm.onPasswordChange("pass2")
        assertNull(vm.uiState.value.formError)
    }

    // ---- sign up ----

    @Test
    fun `sign up needs a name, a valid email, 8 characters and the unticked consent`() = test {
        val vm = SignUpViewModel(auth)
        vm.onNameChange("A")
        vm.onEmailChange("rahim@")
        vm.onPasswordChange("short")
        vm.signUp()
        val s = vm.uiState.value
        assertEquals(R.string.error_name_invalid, s.nameError)
        assertEquals(R.string.error_invalid_email, s.emailError)
        assertEquals(R.string.error_password_short, s.passwordError)
        assertEquals(R.string.error_consent_required, s.consentError)
        assertFalse(s.consent)
        assertTrue(auth.accounts.isEmpty())
    }

    @Test
    fun `sign up creates the account and sends the verification email`() = test {
        val vm = SignUpViewModel(auth)
        vm.onNameChange("  Rahim   Uddin ")
        vm.onEmailChange("rahim@example.test")
        vm.onPasswordChange("long-enough")
        vm.onConsentChange(true)
        vm.signUp()
        advanceUntilIdle()
        assertEquals("Rahim Uddin", auth.accounts.getValue("rahim@example.test").name)
        assertEquals(listOf("rahim@example.test"), auth.verificationEmails)
        assertEquals(false, auth.authState.value?.emailVerified)
    }

    @Test
    fun `Continue with Google on Create account signs in a new Google account (P2)`() = test {
        val vm = SignUpViewModel(auth)
        assertTrue(vm.onGoogleStarted())
        vm.onGoogleResult(GoogleSignInResult.Failed)
        advanceUntilIdle()
        assertEquals(R.string.error_google_failed, vm.uiState.value.formError)
        assertFalse(vm.uiState.value.loading)
        vm.onGoogleStarted()
        vm.onGoogleResult(GoogleSignInResult.Token("new"))
        advanceUntilIdle()
        assertEquals("g-new", auth.authState.value?.uid)
        assertEquals(false, auth.authState.value?.hasPassword) // so setup asks for the Terms
    }

    @Test
    fun `an email that's taken shows under the email field`() = test {
        auth.accounts["rahim@example.test"] = FakeAuthRepository.Account("u1", "x", "R", verified = true)
        val vm = SignUpViewModel(auth)
        vm.onNameChange("Rahim")
        vm.onEmailChange("rahim@example.test")
        vm.onPasswordChange("long-enough")
        vm.onConsentChange(true)
        vm.signUp()
        advanceUntilIdle()
        assertEquals(R.string.error_email_in_use, vm.uiState.value.emailError)
        assertFalse(vm.uiState.value.loading)
    }

    // ---- verify email ----

    @Test
    fun `resend waits 60 seconds, counting down (BUG-026)`() = test {
        val session = FakeSessionManager(SessionState.Unverified(TestData.user(verified = false)))
        val vm = VerifyEmailViewModel(auth, session)
        runCurrent()
        assertEquals(60, vm.uiState.value.resendIn)
        vm.resend()
        assertTrue(auth.verificationEmails.isEmpty())
        advanceTimeBy(30_001)
        assertEquals(30, vm.uiState.value.resendIn)
        advanceTimeBy(30_000)
        assertEquals(0, vm.uiState.value.resendIn)
        vm.resend()
        assertEquals(60, vm.uiState.value.resendIn)
        runCurrent()
        assertEquals(R.string.verify_email_sent, vm.uiState.value.message)
    }

    @Test
    fun `I've verified moves on only when the email really is verified`() = test {
        auth.signUpEmail("Rahim", "rahim@example.test", "long-enough")
        val session = FakeSessionManager(SessionState.Unverified(auth.authState.value!!))
        val vm = VerifyEmailViewModel(auth, session)
        assertEquals("rahim@example.test", vm.uiState.value.email)

        vm.checkVerified()
        runCurrent()
        assertEquals(R.string.verify_email_not_yet, vm.uiState.value.message)
        assertFalse(vm.uiState.value.checking)
        assertEquals(0, session.refreshes)

        auth.verify("rahim@example.test")
        vm.checkVerified()
        runCurrent()
        assertEquals(1, session.refreshes)
        assertTrue(vm.uiState.value.checking)
    }

    @Test
    fun `use a different email signs out`() = test {
        val session = FakeSessionManager(SessionState.Unverified(TestData.user(verified = false)))
        VerifyEmailViewModel(auth, session).useDifferentEmail()
        runCurrent()
        assertEquals(1, session.signOuts)
    }

    // ---- forgot password ----

    @Test
    fun `reset says sent for any well-formed email`() = test {
        val vm = ForgotPasswordViewModel(auth)
        vm.onEmailChange("nobody")
        vm.send()
        assertEquals(R.string.error_invalid_email, vm.uiState.value.emailError)
        vm.onEmailChange(" nobody@example.test ")
        vm.send()
        advanceUntilIdle()
        assertEquals("nobody@example.test", vm.uiState.value.sentTo)
        assertEquals(listOf("nobody@example.test"), auth.resetEmails)
    }
}
