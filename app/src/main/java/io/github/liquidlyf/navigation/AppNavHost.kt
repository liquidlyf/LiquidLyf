package io.github.liquidlyf.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import androidx.navigation.toRoute
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.ExpandedLayoutMinWidth
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.windowWidth
import io.github.liquidlyf.feature.gallery.ComponentGalleryScreen
import io.github.liquidlyf.feature.placeholder.PlaceholderLink
import io.github.liquidlyf.feature.placeholder.PlaceholderScreen
import io.github.liquidlyf.feature.settings.SettingsScreen

/** Used by the Phase 1 placeholders until real requests exist (Phase 4). */
private const val SAMPLE_REQUEST_ID = "sample-request"

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
    startWithOnboarding: Boolean,
    initialTab: TopLevelDestination?,
    onOnboardingFinished: () -> Unit,
    onTabSelected: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
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
                    startDestination = if (startWithOnboarding) Onboarding else AuthGraph,
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
                    mainGraph(navController)
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

/** Leaves sign-in for the app; Back then closes the app instead of returning to sign-in. */
private fun NavController.navigateToMain() {
    navigate(MainGraph) {
        popUpTo(graph.id) { inclusive = true }
    }
}

private fun NavGraphBuilder.onboarding(navController: NavController, onFinished: () -> Unit) {
    composable<Onboarding> {
        PlaceholderScreen(
            title = stringResource(R.string.title_onboarding),
            phase = 3,
            links = listOf(
                PlaceholderLink(stringResource(R.string.action_get_started)) {
                    onFinished()
                    navController.navigate(SignIn) { popUpTo<Onboarding> { inclusive = true } }
                },
            ),
        )
    }
}

private fun NavGraphBuilder.authGraph(navController: NavController) {
    navigation<AuthGraph>(startDestination = SignIn) {
        composable<SignIn> {
            PlaceholderScreen(
                title = stringResource(R.string.title_sign_in),
                phase = 3,
                links = listOf(
                    PlaceholderLink(stringResource(R.string.placeholder_continue_to_app)) { navController.navigateToMain() },
                    PlaceholderLink(stringResource(R.string.placeholder_go_to_sign_up)) {
                        navController.navigate(SignUp) { launchSingleTop = true }
                    },
                    PlaceholderLink(stringResource(R.string.placeholder_go_to_forgot_password)) {
                        navController.navigate(ForgotPassword) { launchSingleTop = true }
                    },
                ),
            )
        }
        composable<SignUp> {
            PlaceholderScreen(
                title = stringResource(R.string.title_sign_up),
                phase = 3,
                onBack = { navController.popBackStack() },
                links = listOf(
                    PlaceholderLink(stringResource(R.string.placeholder_go_to_verify_email)) {
                        navController.navigate(VerifyEmail) { launchSingleTop = true }
                    },
                    // Back to the existing sign-in screen, never a second copy on top (BUG-031).
                    PlaceholderLink(stringResource(R.string.placeholder_go_to_sign_in)) {
                        navController.popBackStack<SignIn>(inclusive = false)
                    },
                ),
            )
        }
        composable<VerifyEmail> {
            PlaceholderScreen(
                title = stringResource(R.string.title_verify_email),
                phase = 3,
                onBack = { navController.popBackStack() },
                links = listOf(
                    PlaceholderLink(stringResource(R.string.placeholder_go_to_profile_setup)) {
                        navController.navigate(ProfileSetup) { launchSingleTop = true }
                    },
                ),
            )
        }
        composable<ProfileSetup> {
            PlaceholderScreen(
                title = stringResource(R.string.title_profile_setup),
                phase = 3,
                onBack = { navController.popBackStack() },
                links = listOf(
                    PlaceholderLink(stringResource(R.string.placeholder_continue_to_app)) { navController.navigateToMain() },
                ),
            )
        }
        composable<ForgotPassword> {
            PlaceholderScreen(
                title = stringResource(R.string.title_forgot_password),
                phase = 3,
                onBack = { navController.popBackStack() },
                links = listOf(
                    PlaceholderLink(stringResource(R.string.placeholder_go_to_sign_in)) {
                        navController.popBackStack<SignIn>(inclusive = false)
                    },
                ),
            )
        }
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

private fun NavGraphBuilder.mainGraph(navController: NavController) {
    navigation<MainGraph>(startDestination = HomeGraph) {
        navigation<HomeGraph>(startDestination = Home) {
            composable<Home> {
                PlaceholderScreen(
                    title = stringResource(R.string.nav_home),
                    phase = 4,
                    links = listOf(
                        PlaceholderLink(stringResource(R.string.placeholder_open_sample_request)) {
                            navController.navigate(RequestDetail(id = SAMPLE_REQUEST_ID)) { launchSingleTop = true }
                        },
                    ),
                    topBarActions = {
                        IconButton(onClick = dropUnlessResumed { navController.navigate(Notifications) { launchSingleTop = true } }) {
                            Icon(painterResource(LLIcons.Notifications), contentDescription = stringResource(R.string.action_open_notifications))
                        }
                    },
                    floatingActionButton = {
                        ExtendedFloatingActionButton(
                            onClick = dropUnlessResumed { navController.navigate(CreateRequest) { launchSingleTop = true } },
                            icon = { Icon(painterResource(LLIcons.Add), contentDescription = null) },
                            text = { Text(stringResource(R.string.action_request_blood)) },
                        )
                    },
                )
            }
        }
        navigation<RequestsGraph>(startDestination = Requests) {
            composable<Requests> {
                PlaceholderScreen(
                    title = stringResource(R.string.nav_requests),
                    phase = 4,
                    links = listOf(
                        PlaceholderLink(stringResource(R.string.placeholder_open_sample_request)) {
                            navController.navigate(RequestDetail(id = SAMPLE_REQUEST_ID)) { launchSingleTop = true }
                        },
                    ),
                )
            }
        }
        navigation<DonorsGraph>(startDestination = Donors) {
            composable<Donors> {
                PlaceholderScreen(title = stringResource(R.string.nav_donors), phase = 6)
            }
        }
        navigation<ProfileGraph>(startDestination = Profile) {
            composable<Profile> {
                PlaceholderScreen(
                    title = stringResource(R.string.nav_profile),
                    phase = 6,
                    topBarActions = {
                        IconButton(onClick = dropUnlessResumed { navController.navigate(Settings) { launchSingleTop = true } }) {
                            Icon(painterResource(LLIcons.Settings), contentDescription = stringResource(R.string.action_open_settings))
                        }
                    },
                )
            }
            composable<Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenComponentGallery = { navController.navigate(ComponentGallery) { launchSingleTop = true } },
                )
            }
            composable<ComponentGallery> {
                ComponentGalleryScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<RequestDetail> { entry ->
            val route = entry.toRoute<RequestDetail>()
            PlaceholderScreen(
                title = stringResource(R.string.title_request_detail),
                phase = 4,
                onBack = { navController.popBackStack() },
                detail = stringResource(R.string.placeholder_request_id, route.id),
            )
        }
        composable<CreateRequest> {
            PlaceholderScreen(
                title = stringResource(R.string.title_create_request),
                phase = 4,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Notifications> {
            PlaceholderScreen(
                title = stringResource(R.string.title_notifications),
                phase = 5,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
