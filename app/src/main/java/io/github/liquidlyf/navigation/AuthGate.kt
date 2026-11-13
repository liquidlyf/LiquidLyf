package io.github.liquidlyf.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import io.github.liquidlyf.core.data.session.SessionState

/**
 * The auth gate (doc 06 §4.1): the session decides which part of the app the user may be in.
 *
 *   signed out      → onboarding (first run) or sign-in
 *   not verified    → verify email
 *   no profile yet  → profile setup → (Android 13+) the notification rationale
 *   profile offline → a "can't load your profile" screen with Retry
 *   ready           → the app
 *   deleting        → the account deletion, resumed until it finishes
 *
 * When the session changes (sign-in, "I've verified", logout, a disabled account), the user is
 * moved and the back stack is cleared, so Back never returns to a screen they no longer belong on
 * (BUG-003).
 */
enum class GateZone { ONBOARDING, SIGNED_OUT, VERIFY_EMAIL, PROFILE_SETUP, NOTIFICATION_PERMISSION, SESSION_PROBLEM, MAIN, DELETING_ACCOUNT }

/** Where the app starts, decided once per launch while the splash screen is up. */
fun startZone(session: SessionState, onboardingSeen: Boolean): GateZone = when (session) {
    SessionState.Loading, SessionState.SignedOut -> if (onboardingSeen) GateZone.SIGNED_OUT else GateZone.ONBOARDING
    is SessionState.Unverified -> GateZone.VERIFY_EMAIL
    is SessionState.NeedsProfile -> GateZone.PROFILE_SETUP
    is SessionState.ProfileUnavailable -> GateZone.SESSION_PROBLEM
    is SessionState.Ready -> GateZone.MAIN
    is SessionState.DeletingAccount -> GateZone.DELETING_ACCOUNT
}

/**
 * Where the user must go now, or null when [current] is fine for [session]. A new user who just
 * finished profile setup sees the notification rationale first if [askForNotifications].
 */
fun gateTarget(session: SessionState, current: GateZone, askForNotifications: Boolean): GateZone? {
    val (allowed, target) = when (session) {
        SessionState.Loading -> return null
        SessionState.SignedOut -> setOf(GateZone.ONBOARDING, GateZone.SIGNED_OUT) to GateZone.SIGNED_OUT
        is SessionState.Unverified -> setOf(GateZone.VERIFY_EMAIL) to GateZone.VERIFY_EMAIL
        is SessionState.NeedsProfile -> setOf(GateZone.PROFILE_SETUP) to GateZone.PROFILE_SETUP
        is SessionState.ProfileUnavailable -> setOf(GateZone.SESSION_PROBLEM) to GateZone.SESSION_PROBLEM
        is SessionState.Ready -> setOf(GateZone.MAIN, GateZone.NOTIFICATION_PERMISSION) to
            if (current == GateZone.PROFILE_SETUP && askForNotifications) GateZone.NOTIFICATION_PERMISSION else GateZone.MAIN
        is SessionState.DeletingAccount -> setOf(GateZone.DELETING_ACCOUNT) to GateZone.DELETING_ACCOUNT
    }
    return if (current in allowed) null else target
}

/** The route that opens a zone. */
fun GateZone.route(): Any = when (this) {
    GateZone.ONBOARDING -> Onboarding
    GateZone.SIGNED_OUT -> AuthGraph
    GateZone.VERIFY_EMAIL -> VerifyEmail
    GateZone.PROFILE_SETUP -> ProfileSetup
    GateZone.NOTIFICATION_PERMISSION -> NotificationPermission
    GateZone.SESSION_PROBLEM -> SessionProblem
    GateZone.MAIN -> MainGraph
    GateZone.DELETING_ACCOUNT -> DeletingAccount
}

/** The zone a destination belongs to. */
fun NavDestination.zone(): GateZone = when {
    hasRoute(Onboarding::class) -> GateZone.ONBOARDING
    hasRoute(VerifyEmail::class) -> GateZone.VERIFY_EMAIL
    hasRoute(ProfileSetup::class) -> GateZone.PROFILE_SETUP
    hasRoute(NotificationPermission::class) -> GateZone.NOTIFICATION_PERMISSION
    hasRoute(SessionProblem::class) -> GateZone.SESSION_PROBLEM
    hasRoute(DeletingAccount::class) -> GateZone.DELETING_ACCOUNT
    hierarchy.any { it.hasRoute(AuthGraph::class) } -> GateZone.SIGNED_OUT
    else -> GateZone.MAIN
}

/** Moves to [zone] with a cleared back stack: Back then leaves the app instead of returning. */
fun NavController.enterZone(zone: GateZone) {
    navigate(zone.route()) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}
