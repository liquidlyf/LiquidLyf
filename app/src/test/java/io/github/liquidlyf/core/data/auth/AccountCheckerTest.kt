package io.github.liquidlyf.core.data.auth

import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

/** D32: the account is read again at start and on return, and Sign in says why it's back. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountCheckerTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = TestData.NOW
    private val auth = FakeAuthRepository().apply {
        accounts["me@example.test"] = FakeAuthRepository.Account("me", "secret-pw", "Me", verified = true)
    }

    private suspend fun TestScope.signedIn(): AccountChecker {
        auth.signInEmail("me@example.test", "secret-pw")
        val checker = AccountChecker(auth, Clock { now }, backgroundScope)
        runCurrent()
        return checker
    }

    @Test
    fun `at most every 10 minutes`() = runTest(dispatcher) {
        val checker = signedIn()
        checker.onAppVisible()
        runCurrent()
        now = now.plus(Duration.ofMinutes(9))
        checker.onAppVisible()
        runCurrent()
        assertEquals(1, auth.checks)
        now = now.plus(Duration.ofMinutes(2))
        checker.onAppVisible()
        runCurrent()
        assertEquals(2, auth.checks)
    }

    @Test
    fun `after Change email here, at every return until it shows`() = runTest(dispatcher) {
        val checker = signedIn()
        checker.onAppVisible()
        auth.changeEmail("new@example.test")
        repeat(3) {
            now = now.plusSeconds(30)
            checker.onAppVisible()
            runCurrent()
        }
        assertEquals(4, auth.checks)
    }

    @Test
    fun `signed out, nothing is checked`() = runTest(dispatcher) {
        val checker = AccountChecker(auth, Clock { now }, backgroundScope)
        checker.onAppVisible()
        runCurrent()
        assertEquals(0, auth.checks)
    }

    @Test
    fun `a session ended after an email change says so, with the new address`() = runTest(dispatcher) {
        val checker = signedIn()
        auth.nextCheck = AccountCheck.SessionEnded("new@example.test")
        checker.onAppVisible()
        runCurrent()
        assertEquals(SignInNotice.EmailChanged("new@example.test"), checker.signInNotice.value)
        assertNull(auth.authState.value)
        // Signed in again: the line goes.
        auth.accounts["new@example.test"] = FakeAuthRepository.Account("me", "secret-pw", "Me", verified = true)
        auth.signInEmail("new@example.test", "secret-pw")
        runCurrent()
        assertNull(checker.signInNotice.value)
    }

    @Test
    fun `ended for another reason, it claims no change`() = runTest(dispatcher) {
        val checker = signedIn()
        auth.nextCheck = AccountCheck.SessionEnded(null)
        checker.onAppVisible()
        runCurrent()
        assertEquals(SignInNotice.SignedOut, checker.signInNotice.value)
    }

    @Test
    fun `no answer or no change, nothing happens`() = runTest(dispatcher) {
        val checker = signedIn()
        auth.nextCheck = AccountCheck.Same // as offline
        checker.onAppVisible()
        runCurrent()
        assertNull(checker.signInNotice.value)
        assertEquals("me@example.test", auth.authState.value?.email)
    }
}
