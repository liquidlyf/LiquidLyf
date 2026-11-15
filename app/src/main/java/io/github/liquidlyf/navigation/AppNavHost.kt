package io.github.liquidlyf.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.ExpandedLayoutMinWidth
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.windowWidth
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.feature.account.ChangePasswordRoute
import io.github.liquidlyf.feature.account.DeleteAccountRoute
import io.github.liquidlyf.feature.account.DeletingAccountRoute
import io.github.liquidlyf.feature.account.EditProfileRoute
import io.github.liquidlyf.feature.auth.ForgotPasswordRoute
import io.github.liquidlyf.feature.auth.SignInRoute
import io.github.liquidlyf.feature.auth.SignUpRoute
import io.github.liquidlyf.feature.auth.VerifyEmailRoute
import io.github.liquidlyf.feature.donors.FindDonorsRoute
import io.github.liquidlyf.feature.gallery.ComponentGalleryScreen
import io.github.liquidlyf.feature.home.HomeRoute
import io.github.liquidlyf.feature.notifications.InboxRoute
import io.github.liquidlyf.feature.onboarding.OnboardingScreen
import io.github.liquidlyf.feature.placeholder.PlaceholderScreen
import io.github.liquidlyf.feature.profile.ProfileRoute
import io.github.liquidlyf.feature.profilesetup.ProfileSetupRoute
import io.github.liquidlyf.feature.request.create.CreateRequestRoute
import io.github.liquidlyf.feature.request.detail.RequestDetailRoute
import io.github.liquidlyf.feature.request.list.RequestsRoute
import io.github.liquidlyf.feature.session.NotificationPermissionScreen
import io.github.liquidlyf.feature.session.SessionProblemScreen
import io.github.liquidlyf.feature.session.needsNotificationPermission
import io.github.liquidlyf.feature.settings.NotificationPrefsRoute
import io.github.liquidlyf.feature.settings.SettingsScreen

/** Used by the Phase 1 placeholders until real requests exist (Phase 4). */
/** liquidlyf://request/{id} (the manifest declares the scheme and host). */
const val REQUEST_DEEP_LINK = "liquidlyf://request"

/**
 * The whole app UI: one NavHost with the auth graph and the main graph. The main graph's
 * four tabs keep their own back stacks (saveState/restoreState), so switching tabs never
 * rebuilds a screen (BUG-032) and never stacks duplicates (BUG-031).
 *
 * Back: from a tab's sub-screen back to the tab root; from another tab's root to Home; from
 * Home the app closes. Re-selecting the current tab returns to its root.
 */
@Composable
fun AppNavHost(
    startZone: GateZone,
    session: SessionState,
    initialTab: TopLevelDestination?,
    onOnboardingFinished: () -> Unit,
    onTabSelected: (TopLevelDestination) -> Unit,
    onRetrySession: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The auth gate: when the session changes, move the user to where they now belong.
    LaunchedEffect(session) {
        val current = navController.currentDestination ?: return@LaunchedEffect
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        gateTarget(session, current.zone(), needsNotificationPermission(granted))?.let(navController::enterZone)
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentTab = currentEntry?.destination?.let { destination ->
        TopLevelDestination.entries.firstOrNull { destination.isInTab(it) }
    }
    val useRail = windowWidth() >= ExpandedLayoutMinWidth

    // Reopen the tab used last time, once per launch (not after rotation).
    var lastTabRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentTab) {
        if (currentTab != null && !lastTabRestored) {
            lastTabRestored = true
            if (initialTab != null && initialTab != currentTab) navController.navigateToTab(initialTab)
        }
    }

    val selectTab: (TopLevelDestination) -> Unit = { tab ->
        if (tab == currentTab) {
            navController.popBackStack(tab.root, inclusive = false)
        } else {
            navController.navigateToTab(tab)
            onTabSelected(tab)
        }
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                if (currentTab != null && !useRail) AppNavigationBar(currentTab, onSelect = selectTab)
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            // Each screen handles its own insets; this Scaffold only reserves the bottom bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                val railTab = currentTab?.takeIf { useRail }
                val showRail = railTab != null
                if (railTab != null) AppNavigationRail(railTab, onSelect = selectTab)
                NavHost(
                    navController = navController,
                    startDestination = startZone.route(),
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (showRail) {
                                Modifier.consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    onboarding(navController, onOnboardingFinished)
                    authGraph(navController)
                    sessionScreens(navController, session, onRetrySession, onSignOut)
                    mainGraph(navController, session, onSignOut)
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(currentTab: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { tab ->
            val selected = tab == currentTab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(currentTab: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    NavigationRail {
        TopLevelDestination.entries.forEach { tab ->
            val selected = tab == currentTab
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

private fun NavDestination.isInTab(tab: TopLevelDestination): Boolean =
    hierarchy.any { it.hasRoute(tab.graph::class) }

/** Switches tab, saving the old tab's back stack and restoring the new one's. */
internal fun NavController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.graph) {
        popUpTo<Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavGraphBuilder.onboarding(navController: NavController, onFinished: () -> Unit) {
    composable<Onboarding> {
        OnboardingScreen(
            onFinished = dropUnlessResumed {
                onFinished()
                navController.navigate(AuthGraph) { popUpTo<Onboarding> { inclusive = true } }
            },
        )
    }
}

/** Signed out. Success needs no navigation here: the session changes and the gate moves on. */
private fun NavGraphBuilder.authGraph(navController: NavController) {
    navigation<AuthGraph>(startDestination = SignIn) {
        composable<SignIn> {
            SignInRoute(
                onCreateAccount = { navController.navigate(SignUp) { launchSingleTop = true } },
                onForgotPassword = { navController.navigate(ForgotPassword) { launchSingleTop = true } },
            )
        }
        // Back to the existing sign-in screen, never a second copy on top (BUG-031).
        composable<SignUp> { SignUpRoute(onBack = { navController.popBackStack<SignIn>(inclusive = false) }) }
        composable<ForgotPassword> { ForgotPasswordRoute(onBack = { navController.popBackStack<SignIn>(inclusive = false) }) }
        // Telegram phone verification arrives in v1.1 (D3); no screen links here yet.
        composable<PhoneVerify> {
            PlaceholderScreen(
                title = stringResource(R.string.title_phone_verify),
                phase = 3,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** Signed in, but not in the app yet: verify, set up, allow alerts, or retry an offline profile. */
private fun NavGraphBuilder.sessionScreens(
    navController: NavController,
    session: SessionState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    composable<VerifyEmail> { VerifyEmailRoute() }
    composable<ProfileSetup> { ProfileSetupRoute() }
    composable<NotificationPermission> {
        NotificationPermissionScreen(onDone = dropUnlessResumed { navController.enterZone(GateZone.MAIN) })
    }
    composable<DeletingAccount> { DeletingAccountRoute() }
    composable<SessionProblem> {
        SessionProblemScreen(
            error = (session as? SessionState.ProfileUnavailable)?.error ?: AppError.Unknown,
            onRetry = onRetry,
            onSignOut = onSignOut,
        )
    }
}

private fun NavGraphBuilder.mainGraph(navController: NavController, session: SessionState, onSignOut: () -> Unit) {
    navigation<MainGraph>(startDestination = HomeGraph) {
        navigation<HomeGraph>(startDestination = Home) {
            composable<Home> {
                HomeRoute(
                    onOpenRequest = { id -> navController.navigate(RequestDetail(id = id)) { launchSingleTop = true } },
                    onCreateRequest = dropUnlessResumed { navController.navigate(CreateRequest) { launchSingleTop = true } },
                    onOpenNotifications = dropUnlessResumed { navController.navigate(Notifications) { launchSingleTop = true } },
                    onEditProfile = dropUnlessResumed { navController.navigate(EditProfile) { launchSingleTop = true } },
                )
            }
        }
        navigation<RequestsGraph>(startDestination = Requests) {
            composable<Requests> {
                RequestsRoute(
                    onOpenRequest = { id -> navController.navigate(RequestDetail(id = id)) { launchSingleTop = true } },
                    onCreateRequest = dropUnlessResumed { navController.navigate(CreateRequest) { launchSingleTop = true } },
                )
            }
        }
        navigation<DonorsGraph>(startDestination = Donors) {
            composable<Donors> {
                FindDonorsRoute(onCreateRequest = dropUnlessResumed { navController.navigate(CreateRequest) { launchSingleTop = true } })
            }
        }
        navigation<ProfileGraph>(startDestination = Profile) {
            composable<Profile> {
                ProfileRoute(
                    onEditProfile = dropUnlessResumed { navController.navigate(EditProfile) { launchSingleTop = true } },
                    onOpenSettings = dropUnlessResumed { navController.navigate(Settings) { launchSingleTop = true } },
                )
            }
            composable<Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenComponentGallery = { navController.navigate(ComponentGallery) { launchSingleTop = true } },
                    onEditProfile = { navController.navigate(EditProfile) { launchSingleTop = true } },
                    onChangePassword = { navController.navigate(ChangePassword) { launchSingleTop = true } },
                    onDeleteAccount = { navController.navigate(DeleteAccount) { launchSingleTop = true } },
                    onSignOut = onSignOut,
                    onOpenNotificationSettings = { navController.navigate(NotificationSettings) { launchSingleTop = true } },
                )
            }
            composable<EditProfile> { EditProfileRoute(onDone = { navController.popBackStack() }) }
            composable<ChangePassword> { ChangePasswordRoute(onDone = { navController.popBackStack() }) }
            composable<DeleteAccount> { DeleteAccountRoute(onBack = { navController.popBackStack() }) }
            composable<ComponentGallery> {
                ComponentGalleryScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<RequestDetail>(deepLinks = listOf(navDeepLink<RequestDetail>(basePath = REQUEST_DEEP_LINK))) {
            RequestDetailRoute(onBack = { navController.popBackStack() })
        }
        composable<CreateRequest> {
            CreateRequestRoute(
                onClose = { navController.popBackStack() },
                onPosted = { id, notice ->
                    navController.navigate(RequestDetail(id = id, notice = notice?.name)) {
                        popUpTo<CreateRequest> { inclusive = true }
                    }
                },
                onOpenMyRequests = {
                    navController.navigate(RequestsGraph) {
                        popUpTo<CreateRequest> { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Notifications> {
            InboxRoute(
                onBack = { navController.popBackStack() },
                onOpenRequest = { id -> navController.navigate(RequestDetail(id = id)) { launchSingleTop = true } },
                onOpenSettings = { navController.navigate(NotificationSettings) { launchSingleTop = true } },
            )
        }
        composable<NotificationSettings> { NotificationPrefsRoute(onBack = { navController.popBackStack() }) }
    }
}
