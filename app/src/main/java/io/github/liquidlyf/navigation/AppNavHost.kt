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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.liquidlyf.core.designsystem.components.AppSnackbars
import io.github.liquidlyf.core.designsystem.components.CapFontScale
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.MAX_LABEL_SCALE
import io.github.liquidlyf.core.designsystem.windowWidth
import io.github.liquidlyf.core.firebase.CrashReports
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.feature.about.AboutRoute
import io.github.liquidlyf.feature.about.HowMatchingWorksScreen
import io.github.liquidlyf.feature.about.LicencesScreen
import io.github.liquidlyf.feature.about.MedicalDisclaimerScreen
import io.github.liquidlyf.feature.account.AddPasswordRoute
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
import io.github.liquidlyf.feature.moderation.ModerationRoute
import io.github.liquidlyf.feature.notifications.InboxRoute
import io.github.liquidlyf.feature.onboarding.OnboardingScreen
import io.github.liquidlyf.feature.placeholder.PlaceholderScreen
import io.github.liquidlyf.feature.profile.ProfileRoute
import io.github.liquidlyf.feature.profilesetup.BecomeDonorRoute
import io.github.liquidlyf.feature.profilesetup.ProfileSetupRoute
import io.github.liquidlyf.feature.request.create.CreateRequestRoute
import io.github.liquidlyf.feature.request.detail.RequestDetailRoute
import io.github.liquidlyf.feature.request.list.RequestsRoute
import io.github.liquidlyf.feature.safety.BlockedPeopleRoute
import io.github.liquidlyf.feature.safety.SafetyTipsRoute
import io.github.liquidlyf.feature.session.NotificationPermissionScreen
import io.github.liquidlyf.feature.session.SessionProblemScreen
import io.github.liquidlyf.feature.session.SuspendedScreen
import io.github.liquidlyf.feature.session.needsNotificationPermission
import io.github.liquidlyf.feature.settings.LanguageViewModel
import io.github.liquidlyf.feature.settings.NotificationPrefsRoute
import io.github.liquidlyf.feature.settings.SettingsScreen

/** Used by the Phase 1 placeholders until real requests exist (Phase 4). */
/** liquidlyf://request/{id} (the manifest declares the scheme and host). */
const val REQUEST_DEEP_LINK = "liquidlyf://request"

/** The admins' new-report notification opens Moderation (phase-07 7.3). */
const val MODERATION_DEEP_LINK = "liquidlyf://moderation"

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
    // Crashlytics' "screen" key (A32): the route's name, never its arguments.
    val route = currentEntry?.destination?.route
    LaunchedEffect(route) { route?.let { CrashReports.screen(CrashReports.screenName(it)) } }

    // Reopen the tab used last time, once per launch (not after rotation), but only when the
    // app opens on a tab's root. A notification's deep link (Moderation, a request) opens its
    // own screen, and that screen stays.
    var lastTabRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentEntry?.destination) {
        val destination = currentEntry?.destination ?: return@LaunchedEffect
        if (lastTabRestored || destination.zone() != GateZone.MAIN) return@LaunchedEffect
        lastTabRestored = true
        val tab = TopLevelDestination.entries.firstOrNull { destination.isInTab(it) } ?: return@LaunchedEffect
        val atTabRoot = destination.hasRoute(tab.root::class)
        if (atTabRoot && initialTab != null && initialTab != tab) navController.navigateToTab(initialTab)
    }

    val selectTab: (TopLevelDestination) -> Unit = { tab ->
        if (tab == currentTab) {
            navController.popBackStack(tab.root, inclusive = false)
        } else {
            navController.navigateToTab(tab)
            onTabSelected(tab)
        }
    }

    // App-wide messages that outlive the screen that sent them (FLOW-08: "Request hidden · Undo").
    val resources = LocalResources.current
    LaunchedEffect(snackbarHostState) {
        AppSnackbars.current.collect { entry ->
            val s = entry?.snackbar ?: return@collect
            // Cancelled by a rotation mid-show: not done, so the new screen shows it again.
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(s.message),
                actionLabel = s.actionLabel?.let(resources::getString),
                duration = SnackbarDuration.Long,
            )
            AppSnackbars.done(entry)
            if (result == SnackbarResult.ActionPerformed) s.onAction?.invoke()
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

/** Internal for the README and website screenshots (StoreScreenshotsTest), which draw it around a tab. */
@Composable
internal fun AppNavigationBar(currentTab: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { tab ->
            val selected = tab == currentTab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                label = { TabLabel(stringResource(tab.labelRes)) },
                // The one place the pink stays (D4 a): secondaryContainer is a neutral grey now.
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/**
 * A tab's label on one line, never split mid-word (A11Y-01: at 200% text "Reque / sts" pushed the
 * icons out of line). Its text grows with the phone's font size up to 1.5x, then shrinks to fit the
 * tab; the icon says it too, and TalkBack reads the full label.
 */
@Composable
private fun TabLabel(text: String) {
    CapFontScale(MAX_LABEL_SCALE) {
        val style = LocalTextStyle.current
        BasicText(
            text,
            style = style.copy(color = LocalContentColor.current),
            maxLines = 1,
            softWrap = false,
            // Never below 11 sp (D's re-audit: 9 sp on a narrow phone); a longer word ends in "…".
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            overflow = TextOverflow.Ellipsis,
        )
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
                label = { TabLabel(stringResource(tab.labelRes)) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
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
        val language: LanguageViewModel = hiltViewModel()
        OnboardingScreen(
            onFinished = dropUnlessResumed {
                onFinished()
                navController.navigate(AuthGraph) { popUpTo<Onboarding> { inclusive = true } }
            },
            onLanguageChange = language::set,
        )
    }
}

/** Signed out. Success needs no navigation here: the session changes and the gate moves on. */
private fun NavGraphBuilder.authGraph(navController: NavController) {
    navigation<AuthGraph>(startDestination = SignIn) {
        composable<SignIn> { entry ->
            val prefill by entry.savedStateHandle.getStateFlow<String?>(PREFILL_EMAIL, null).collectAsStateWithLifecycle()
            SignInRoute(
                onCreateAccount = { navController.navigate(SignUp) { launchSingleTop = true } },
                onForgotPassword = { navController.navigate(ForgotPassword) { launchSingleTop = true } },
                prefillEmail = prefill,
                onPrefillUsed = { entry.savedStateHandle[PREFILL_EMAIL] = null },
            )
        }
        // Back to the existing sign-in screen, never a second copy on top (BUG-031).
        composable<SignUp> {
            SignUpRoute(
                onBack = { navController.popBackStack<SignIn>(inclusive = false) },
                // FORM-04: "already has an account": Sign in, with this email filled in.
                onSignInWithEmail = { email ->
                    runCatching { navController.getBackStackEntry<SignIn>().savedStateHandle[PREFILL_EMAIL] = email }
                    navController.popBackStack<SignIn>(inclusive = false)
                },
            )
        }
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
    composable<Suspended> { SuspendedScreen(onSignOut = onSignOut) }
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
                    onBecomeDonor = dropUnlessResumed { navController.navigate(BecomeDonor) { launchSingleTop = true } },
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
                    onBecomeDonor = dropUnlessResumed { navController.navigate(BecomeDonor) { launchSingleTop = true } },
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
                    onAddPassword = { navController.navigate(AddPassword) { launchSingleTop = true } },
                    onOpenSafetyTips = { navController.navigate(SafetyTips) { launchSingleTop = true } },
                    onOpenBlockedPeople = { navController.navigate(BlockedPeople) { launchSingleTop = true } },
                    onOpenModeration = { navController.navigate(Moderation) { launchSingleTop = true } },
                    onOpenAbout = { navController.navigate(About) { launchSingleTop = true } },
                    onOpenHowMatchingWorks = { navController.navigate(HowMatchingWorks) { launchSingleTop = true } },
                    onBecomeDonor = { navController.navigate(BecomeDonor) { launchSingleTop = true } },
                )
            }
            composable<About> {
                AboutRoute(
                    onBack = { navController.popBackStack() },
                    onOpenMedicalDisclaimer = dropUnlessResumed { navController.navigate(MedicalDisclaimer) { launchSingleTop = true } },
                    onOpenHowMatchingWorks = dropUnlessResumed { navController.navigate(HowMatchingWorks) { launchSingleTop = true } },
                    onOpenLicences = dropUnlessResumed { navController.navigate(Licences) { launchSingleTop = true } },
                )
            }
            composable<MedicalDisclaimer> { MedicalDisclaimerScreen(onBack = { navController.popBackStack() }) }
            composable<HowMatchingWorks> { HowMatchingWorksScreen(onBack = { navController.popBackStack() }) }
            composable<Licences> { LicencesScreen(onBack = { navController.popBackStack() }) }
            composable<EditProfile> { EditProfileRoute(onDone = { navController.popBackStack() }) }
            composable<ChangePassword> { ChangePasswordRoute(onDone = { navController.popBackStack() }) }
            composable<AddPassword> { AddPasswordRoute(onDone = { navController.popBackStack() }) }
            composable<Moderation>(deepLinks = listOf(navDeepLink<Moderation>(basePath = MODERATION_DEEP_LINK))) {
                ModerationRoute(
                    onBack = { navController.popBackStack() },
                    onOpenRequest = { id -> navController.navigate(RequestDetail(id = id)) { launchSingleTop = true } },
                )
            }
            composable<DeleteAccount> { DeleteAccountRoute(onBack = { navController.popBackStack() }) }
            composable<ComponentGallery> {
                ComponentGalleryScreen(onBack = { navController.popBackStack() })
            }
        }
        composable<RequestDetail>(deepLinks = listOf(navDeepLink<RequestDetail>(basePath = REQUEST_DEEP_LINK))) {
            RequestDetailRoute(
                onBack = { navController.popBackStack() },
                onOpenSafetyTips = dropUnlessResumed { navController.navigate(SafetyTips) { launchSingleTop = true } },
                onBecomeDonor = dropUnlessResumed { navController.navigate(BecomeDonor) { launchSingleTop = true } },
            )
        }
        composable<BecomeDonor> { BecomeDonorRoute(onDone = { navController.popBackStack() }) }
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
        composable<SafetyTips> { SafetyTipsRoute(onBack = { navController.popBackStack() }) }
        composable<BlockedPeople> { BlockedPeopleRoute(onBack = { navController.popBackStack() }) }
    }
}

/** Sign up hands an email to Sign in through this saved-state key (FORM-04). */
private const val PREFILL_EMAIL = "prefillEmail"
