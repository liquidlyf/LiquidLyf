package io.github.liquidlyf.navigation

import kotlinx.serialization.Serializable

/*
 * Type-safe navigation routes. A route's properties are its arguments.
 *
 * Graph layout. The auth gate (AuthGate.kt) moves the user between these as the session changes:
 *   Onboarding                      (first run only)
 *   AuthGraph:  SignIn · SignUp · ForgotPassword · PhoneVerify      (signed out)
 *   VerifyEmail                     (signed in, email not verified)
 *   SetupChoice → ProfileSetup → [DonorSetup] → NotificationPermission (verified, no profile yet; D28)
 *   SessionProblem                  (verified, but the profile can't be read: offline)
 *   DeletingAccount                 (an account deletion is running or must resume)
 *   MainGraph:  HomeGraph(Home) · RequestsGraph(Requests) · DonorsGraph(Donors)
 *               · ProfileGraph(Profile, Settings, ComponentGallery)   ← bottom navigation tabs
 *               · RequestDetail · CreateRequest · Notifications        ← full screen, no tabs
 */

// Graphs
@Serializable data object AuthGraph
@Serializable data object MainGraph
@Serializable data object HomeGraph
@Serializable data object RequestsGraph
@Serializable data object DonorsGraph
@Serializable data object ProfileGraph

// First run and sign-in (Phase 3)
@Serializable data object Onboarding
@Serializable data object SignIn
/** [email]: what was typed on Sign in, so it needn't be typed again (VT-11). */
@Serializable data class SignUp(val email: String = "")
@Serializable data object VerifyEmail
@Serializable data object ForgotPassword
@Serializable data object PhoneVerify
/** D28: the first page of setup, "What would you like to do?" (donate, or only ask for blood). */
@Serializable data object SetupChoice

/** About you. [donor]: the choice page's first option, so About you is step 1 of 4 and the donor steps follow. */
@Serializable data class ProfileSetup(val donor: Boolean = false)

/** D28: Become a donor's steps as steps 2-4 of setup, once About you has saved the account. */
@Serializable data object DonorSetup
@Serializable data object NotificationPermission
@Serializable data object SessionProblem
@Serializable data object DeletingAccount
@Serializable data object Suspended

// Tab roots
@Serializable data object Home
@Serializable data object Requests
@Serializable data object Donors
@Serializable data object Profile

// Inside the Profile tab
@Serializable data object Settings
@Serializable data object EditProfile
@Serializable data object ChangePassword
@Serializable data object AddPassword
@Serializable data object DeleteAccount
@Serializable data object ComponentGallery

// Full-screen destinations above the tabs
/** [notice]: a [io.github.liquidlyf.feature.request.create.PostNotice] name, shown once after posting. */
@Serializable data class RequestDetail(val id: String, val notice: String? = null)
@Serializable data object CreateRequest
/** D1: the donor questions the short setup left out. */
@Serializable data object BecomeDonor
@Serializable data object Notifications
@Serializable data object NotificationSettings
@Serializable data object SafetyTips
@Serializable data object BlockedPeople
@Serializable data object Moderation
@Serializable data object About
@Serializable data object MedicalDisclaimer
@Serializable data object HowMatchingWorks
@Serializable data object Licences
