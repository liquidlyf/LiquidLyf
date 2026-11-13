package io.github.liquidlyf.navigation

import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The auth gate for each of the session states (doc 06 §4.1, Phase 3.14). */
class AuthGateTest {

    private val signedOut = SessionState.SignedOut
    private val unverified = SessionState.Unverified(TestData.user(verified = false))
    private val needsProfile = SessionState.NeedsProfile(TestData.user())
    private val offline = SessionState.ProfileUnavailable(TestData.user(), AppError.Network)
    private val ready = SessionState.Ready(TestData.user(), TestData.profile())

    @Test
    fun `each state starts in its own zone`() {
        assertEquals(GateZone.ONBOARDING, startZone(signedOut, onboardingSeen = false))
        assertEquals(GateZone.SIGNED_OUT, startZone(signedOut, onboardingSeen = true))
        assertEquals(GateZone.VERIFY_EMAIL, startZone(unverified, onboardingSeen = false))
        assertEquals(GateZone.PROFILE_SETUP, startZone(needsProfile, onboardingSeen = true))
        assertEquals(GateZone.SESSION_PROBLEM, startZone(offline, onboardingSeen = true))
        assertEquals(GateZone.MAIN, startZone(ready, onboardingSeen = true))
    }

    @Test
    fun `a screen that fits the state stays put`() {
        assertNull(gateTarget(signedOut, GateZone.ONBOARDING, false))
        assertNull(gateTarget(signedOut, GateZone.SIGNED_OUT, false))
        assertNull(gateTarget(unverified, GateZone.VERIFY_EMAIL, false))
        assertNull(gateTarget(needsProfile, GateZone.PROFILE_SETUP, false))
        assertNull(gateTarget(offline, GateZone.SESSION_PROBLEM, false))
        assertNull(gateTarget(ready, GateZone.MAIN, false))
        assertNull(gateTarget(ready, GateZone.NOTIFICATION_PERMISSION, true))
        GateZone.entries.forEach { assertNull(gateTarget(SessionState.Loading, it, true)) }
    }

    @Test
    fun `sign-in moves the user forward`() {
        assertEquals(GateZone.VERIFY_EMAIL, gateTarget(unverified, GateZone.SIGNED_OUT, false))
        assertEquals(GateZone.PROFILE_SETUP, gateTarget(needsProfile, GateZone.VERIFY_EMAIL, false))
        assertEquals(GateZone.MAIN, gateTarget(ready, GateZone.SIGNED_OUT, true))
    }

    @Test
    fun `finishing profile setup asks for notifications only when needed`() {
        assertEquals(GateZone.NOTIFICATION_PERMISSION, gateTarget(ready, GateZone.PROFILE_SETUP, askForNotifications = true))
        assertEquals(GateZone.MAIN, gateTarget(ready, GateZone.PROFILE_SETUP, askForNotifications = false))
    }

    @Test
    fun `logout from anywhere goes to sign-in (BUG-003)`() {
        GateZone.entries.filter { it != GateZone.ONBOARDING && it != GateZone.SIGNED_OUT }
            .forEach { assertEquals(it.name, GateZone.SIGNED_OUT, gateTarget(signedOut, it, false)) }
    }

    @Test
    fun `an offline profile shows the problem screen, and retry recovers`() {
        assertEquals(GateZone.SESSION_PROBLEM, gateTarget(offline, GateZone.MAIN, false))
        assertEquals(GateZone.MAIN, gateTarget(ready, GateZone.SESSION_PROBLEM, false))
    }

    @Test
    fun `zones open their routes`() {
        assertEquals(AuthGraph, GateZone.SIGNED_OUT.route())
        assertEquals(MainGraph, GateZone.MAIN.route())
        assertEquals(VerifyEmail, GateZone.VERIFY_EMAIL.route())
        assertEquals(NotificationPermission, GateZone.NOTIFICATION_PERMISSION.route())
    }
}
