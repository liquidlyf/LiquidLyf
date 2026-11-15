package io.github.liquidlyf.navigation

import kotlinx.serialization.Serializable

/*
 * Type-safe navigation routes. A route's properties are its arguments.
 *
 * Graph layout. The auth gate (AuthGate.kt) moves the user between these as the session changes:
 *   Onboarding                      (first run only)
 *   AuthGraph:  SignIn · SignUp · ForgotPassword · PhoneVerify      (signed out)
 *   VerifyEmail                     (signed in, email not verified)
 *   ProfileSetup → NotificationPermission                         (verified, no profile yet)
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
@Serializable data object SignUp
@Serializable data object VerifyEmail
@Serializable data object ForgotPassword
@Serializable data object PhoneVerify
@Serializable data object ProfileSetup
@Serializable data object NotificationPermission
@Serializable data object SessionProblem
@Serializable data object DeletingAccount

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
@Serializable data object Notifications
@Serializable data object NotificationSettings
@Serializable data object SafetyTips
