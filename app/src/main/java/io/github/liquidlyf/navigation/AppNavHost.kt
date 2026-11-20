package io.github.liquidlyf.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
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
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.link.PendingRequestStore
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.ExpandedLayoutMinWidth
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.AppSnackbarHost
import io.github.liquidlyf.core.designsystem.components.AppSnackbars
import io.github.liquidlyf.core.designsystem.components.CapFontScale
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarClearance
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.MAX_LABEL_SCALE
import io.github.liquidlyf.core.designsystem.components.SnackbarClearance
import io.github.liquidlyf.core.designsystem.components.SuccessMessages
import io.github.liquidlyf.core.designsystem.components.SuccessVisuals
import io.github.liquidlyf.core.designsystem.components.keepsSnackbarsAbove
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
import io.github.liquidlyf.feature.profilesetup.SetupChoiceRoute
import io.github.liquidlyf.feature.request.create.CreateRequestRoute
import io.github.liquidlyf.feature.request.detail.RequestDetailRoute
import io.github.liquidlyf.feature.request.list.RequestsRoute
import io.github.liquidlyf.feature.request.list.RequestsTab
import io.github.liquidlyf.feature.request.list.RequestsTabRequest
import io.github.liquidlyf.feature.safety.BlockedPeopleRoute
import io.github.liquidlyf.feature.safety.SafetyTipsRoute
import io.github.liquidlyf.feature.session.NotificationPermissionScreen
import io.github.liquidlyf.feature.session.SessionProblemScreen
import io.github.liquidlyf.feature.session.SuspendedScreen
import io.github.liquidlyf.feature.session.needsNotificationPermission
import io.github.liquidlyf.feature.settings.LanguageViewModel
import io.github.liquidlyf.feature.settings.NotificationPrefsRoute
import io.github.liquidlyf.feature.settings.SettingsScreen
import io.github.liquidlyf.start.StartScreen
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Used by the Phase 1 placeholders until real requests exist (Phase 4). */
/** liquidlyf://request/{id} (the manifest declares the scheme and host). */
const val REQUEST_DEEP_LINK = "liquidlyf://request"

/** The admins' new-report notification opens Moderation (phase-07 7.3). */
const val MODERATION_DEEP_LINK = "liquidlyf://moderation"

/** liquidlyf://become-donor: D29's reminder opens Become a donor. */
const val BECOME_DONOR_DEEP_LINK = "liquidlyf://become-donor"

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
    /** D25: the first screen a fresh start lands on, once it's settled (after the hop to the last tab). */
    onStartScreen: (StartScreen) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The auth gate: when the session changes, move the user to where they now belong. D31: also when
    // the screen does, since a link can land on a screen the person may not see yet (a request's
    // page while signed out, from the web page's button): they go to sign in, and the request,
    // kept on the phone, opens after it.
    val gateDestination = navController.currentBackStackEntryAsState().value?.destination
    LaunchedEffect(session, gateDestination) {
        val entry = navController.currentBackStackEntry ?: return@LaunchedEffect
        val current = entry.destination
        // D28: About you on the donor path goes on to the donor steps once it has saved the account.
        val donorNext = current.hasRoute(ProfileSetup::class) && entry.toRoute<ProfileSetup>().donor
        gateTarget(session, current.zone(), notificationsNext(context), donorNext)?.let(navController::enterZone)
    }
    val currentEntry by navController.currentBackStackEntryAsState()

    // D31: a request link kept on this phone opens once the person is in the app (signed in, set
    // up, past the alerts question), over Home, so Back goes Home. Taken once. An alert's tap may
    // have opened it already; then it's only forgotten.
    val pendingRequests: PendingRequestViewModel = hiltViewModel()
    // A State, not its value, for the graph: the screens read it, so the graph isn't rebuilt.
    val pendingState = pendingRequests.pending.collectAsStateWithLifecycle()
    val pendingRequest = pendingState.value
    // Signed in too: a link can land on a request's page before the gate has moved a signed-out person on.
    val inApp = session is SessionState.Ready && currentEntry?.destination?.zone() == GateZone.MAIN
    LaunchedEffect(inApp, pendingRequest) {
        if (!inApp || pendingRequest == null) return@LaunchedEffect
        val id = pendingRequests.take() ?: return@LaunchedEffect
        // The store answers from its own thread: navigate on the main one.
        withContext(Dispatchers.Main.immediate) {
            val entry = navController.currentBackStackEntry
            if (entry?.destination?.hasRoute(RequestDetail::class) == true && entry.toRoute<RequestDetail>().id == id) return@withContext
            navController.navigateToTab(TopLevelDestination.HOME)
            navController.navigate(RequestDetail(id = id)) { launchSingleTop = true }
        }
    }

    val currentTab = currentEntry?.destination?.let { destination ->
        TopLevelDestination.entries.firstOrNull { destination.isInTab(it) }
    }
    val useRail = windowWidth() >= ExpandedLayoutMinWidth
    // Crashlytics' "screen" key (A32): the route's name, never its arguments.
    val route = currentEntry?.destination?.route
    LaunchedEffect(route) { route?.let { CrashReports.screen(CrashReports.screenName(it)) } }

    // Reopen the tab used last time, once per launch (not after rotation), but only when the
    // app opens on a tab's root. A notification's deep link (Moderation, a request) opens its
    // own screen, and that screen stays. D25: the hop is instant, behind the start logo, so the
    // logo lands on that tab and Home never flashes first.
    var lastTabRestored by rememberSaveable { mutableStateOf(false) }
    var instantHop by remember { mutableStateOf(false) }
    var startReported by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentEntry?.destination) {
        val destination = currentEntry?.destination ?: return@LaunchedEffect
        instantHop = false
        if (!lastTabRestored && destination.zone() == GateZone.MAIN) {
            lastTabRestored = true
            val tab = TopLevelDestination.entries.firstOrNull { destination.isInTab(it) }
            if (tab != null && destination.hasRoute(tab.root::class) && initialTab != null && initialTab != tab) {
                instantHop = true
                navController.navigateToTab(initialTab)
                return@LaunchedEffect // the start lands on that tab, reported when it's current
            }
        }
        if (!startReported) {
            startReported = true
            onStartScreen(destination.startScreen())
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

    // App-wide messages that outlive the screen that sent them (FLOW-08: "Request hidden · Undo").
    val resources = LocalResources.current
    LaunchedEffect(snackbarHostState) {
        AppSnackbars.current.collect { entry ->
            val s = entry?.snackbar ?: return@collect
            // Cancelled by a rotation mid-show: not done, so the new screen shows it again.
            val result = if (s.actionLabel == null && s.message in SuccessMessages.ids) {
                snackbarHostState.showSnackbar(SuccessVisuals(resources.getString(s.message)))
            } else {
                snackbarHostState.showSnackbar(
                    message = resources.getString(s.message),
                    actionLabel = s.actionLabel?.let(resources::getString),
                    duration = SnackbarDuration.Long,
                )
            }
            AppSnackbars.done(entry)
            if (result == SnackbarResult.ActionPerformed) s.onAction?.invoke()
        }
    }
    // VT-16: the host sits outside the Scaffold, above whichever bar is highest (SnackbarClearance).
    val snackbarClearance = remember { SnackbarClearance() }
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState, LocalSnackbarClearance provides snackbarClearance) {
        Box(modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    if (currentTab != null && !useRail) AppNavigationBar(currentTab, onSelect = selectTab)
                },
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
                        enterTransition = { if (instantHop) EnterTransition.None else if (isTabSwitch()) fadeThroughIn() else sharedAxisIn(forward = true) },
                        exitTransition = { if (instantHop) ExitTransition.None else if (isTabSwitch()) fadeThroughOut() else sharedAxisOut(forward = true) },
                        popEnterTransition = { if (isTabSwitch()) fadeThroughIn() else sharedAxisIn(forward = false) },
                        popExitTransition = { if (isTabSwitch()) fadeThroughOut() else sharedAxisOut(forward = false) },
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
                        authGraph(navController, pendingState)
                        sessionScreens(navController, session, pendingState, onRetrySession, onSignOut)
                        mainGraph(navController, session, onSignOut)
                    }
                }
            }
            AppSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

// ---- MOTION-01: M3 transitions. With "Remove animations" on, Compose runs them at no length. ----

private val TAB_GRAPHS = listOf(HomeGraph::class, RequestsGraph::class, DonorsGraph::class, ProfileGraph::class)

/** What the start logo moves into (D25): the welcome page and Sign in have the stacked logo, Home the top-bar one. */
private fun NavDestination.startScreen(): StartScreen = when {
    hasRoute(Onboarding::class) -> StartScreen.WELCOME
    hasRoute(SignIn::class) -> StartScreen.SIGN_IN
    hasRoute(Home::class) -> StartScreen.HOME
    else -> StartScreen.OTHER
}

/** The tab graph a destination sits in, or null (sign-in, a request on top of the tabs…). */
private fun NavBackStackEntry.tabGraph(): Int? =
    destination.hierarchy.firstNotNullOfOrNull { d -> TAB_GRAPHS.indexOfFirst { d.hasRoute(it) }.takeIf { it >= 0 } }

/** Between two tabs' own screens: a fade-through, not a slide (tabs aren't "forward"). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean {
    val from = initialState.tabGraph()
    val to = targetState.tabGraph()
    return from != null && to != null && from != to
}

private const val FADE_THROUGH_OUT_MS = 90
private const val FADE_THROUGH_IN_MS = 210
private const val SHARED_AXIS_MS = 250

private fun fadeThroughIn(): EnterTransition =
    fadeIn(tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS)) + scaleIn(tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS), initialScale = 0.92f)

private fun fadeThroughOut(): ExitTransition = fadeOut(tween(FADE_THROUGH_OUT_MS))

/** M3 shared axis X: a short slide (30 dp's worth) with a cross-fade, forward or back. */
private fun sharedAxisIn(forward: Boolean): EnterTransition =
    slideInHorizontally(tween(SHARED_AXIS_MS)) { w -> (if (forward) 1 else -1) * w / 10 } + fadeIn(tween(SHARED_AXIS_MS))

private fun sharedAxisOut(forward: Boolean): ExitTransition =
    slideOutHorizontally(tween(SHARED_AXIS_MS)) { w -> (if (forward) -1 else 1) * w / 10 } + fadeOut(tween(SHARED_AXIS_MS / 2))

/** Internal for the README and website screenshots (StoreScreenshotsTest), which draw it around a tab. */
@Composable
internal fun AppNavigationBar(currentTab: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    val tabs = TopLevelDestination.entries
    NavigationBar(Modifier.keepsSnackbarsAbove().semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size) }) {
        tabs.forEachIndexed { index, tab ->
            val selected = tab == currentTab
            NavigationBarItem(
                modifier = Modifier.tabSemantics(stringResource(tab.labelRes), selected, CollectionItemInfo(0, 1, index, 1)) { onSelect(tab) },
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
            // Low 26: a little room each side, so a long word shrinks a step instead of touching the edge.
            modifier = Modifier.padding(horizontal = Spacing.xxs),
        )
    }
}

@Composable
private fun AppNavigationRail(currentTab: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    val tabs = TopLevelDestination.entries
    NavigationRail(Modifier.semantics { collectionInfo = CollectionInfo(rowCount = tabs.size, columnCount = 1) }) {
        tabs.forEachIndexed { index, tab ->
            val selected = tab == currentTab
            NavigationRailItem(
                modifier = Modifier.tabSemantics(stringResource(tab.labelRes), selected, CollectionItemInfo(index, 1, 0, 1)) { onSelect(tab) },
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

/**
 * VT-27: one TalkBack stop per tab that says "Home, Tab, Selected, 1 of 4". Material's item puts
 * the modifier on an outer box and the tab's own semantics on an inner one, so the place never
 * reached the node TalkBack focuses; here the outer box is that node, with the name, role, state,
 * the tap and the place. Touches still go to Material's item.
 */
private fun Modifier.tabSemantics(label: String, selected: Boolean, place: CollectionItemInfo, onSelect: () -> Unit): Modifier =
    clearAndSetSemantics {
        role = Role.Tab
        this.selected = selected
        text = AnnotatedString(label)
        collectionItemInfo = place
        onClick { onSelect(); true }
    }

private fun NavDestination.isInTab(tab: TopLevelDestination): Boolean =
    hierarchy.any { it.hasRoute(tab.graph::class) }

/**
 * Switches tab, saving the old tab's back stack and restoring the new one's. Home is the exception
 * (VT-05): it always opens Home itself. A request page, Request blood or Become a donor left open
 * in it isn't restored (5 of 5 testers tapped Home and got the old request page back).
 */
internal fun NavController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.graph) {
        popUpTo<Home> { saveState = true }
        launchSingleTop = true
        restoreState = tab != TopLevelDestination.HOME
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
private fun NavGraphBuilder.authGraph(navController: NavController, pendingRequest: State<String?>) {
    navigation<AuthGraph>(startDestination = SignIn) {
        composable<SignIn> { entry ->
            val prefill by entry.savedStateHandle.getStateFlow<String?>(PREFILL_EMAIL, null).collectAsStateWithLifecycle()
            SignInRoute(
                onCreateAccount = { email -> navController.navigate(SignUp(email)) { launchSingleTop = true } },
                onForgotPassword = { navController.navigate(ForgotPassword) { launchSingleTop = true } },
                prefillEmail = prefill,
                onPrefillUsed = { entry.savedStateHandle[PREFILL_EMAIL] = null },
                waitingRequest = pendingRequest.value != null,
            )
        }
        // Back to the existing sign-in screen, never a second copy on top (BUG-031).
        composable<SignUp> { entry ->
            SignUpRoute(
                prefillEmail = entry.toRoute<SignUp>().email,
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
/** The notification rationale comes before the app (Android 13+, not granted yet). */
private fun notificationsNext(context: Context): Boolean {
    val granted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return needsNotificationPermission(granted)
}

private fun NavGraphBuilder.sessionScreens(
    navController: NavController,
    session: SessionState,
    pendingRequest: State<String?>,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    composable<VerifyEmail> { VerifyEmailRoute() }
    // D28: the choice, then About you (step 1 of 4 on the donor path), then the donor steps.
    composable<SetupChoice> {
        SetupChoiceRoute(
            onContinue = { donor -> navController.navigate(ProfileSetup(donor)) { launchSingleTop = true } },
            waitingRequest = pendingRequest.value != null,
        )
    }
    composable<ProfileSetup> { entry ->
        ProfileSetupRoute(donorPath = entry.toRoute<ProfileSetup>().donor, onBack = dropUnlessResumed { navController.popBackStack() })
    }
    composable<DonorSetup> {
        val context = LocalContext.current
        // Finished, or "Need blood now? Skip this": the alerts page (Android 13+), then Home.
        BecomeDonorRoute(
            onDone = dropUnlessResumed { navController.enterZone(if (notificationsNext(context)) GateZone.NOTIFICATION_PERMISSION else GateZone.MAIN) },
            setupMode = true,
        )
    }
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
                onAskDonors = dropUnlessResumed { navController.navigateToTab(TopLevelDestination.DONORS) },
                onSeeNearby = dropUnlessResumed { navController.navigateToTab(TopLevelDestination.REQUESTS) },
                onEditProfile = dropUnlessResumed { navController.navigate(EditProfile) { launchSingleTop = true } },
            )
        }
        composable<BecomeDonor>(deepLinks = listOf(navDeepLink<BecomeDonor>(basePath = BECOME_DONOR_DEEP_LINK))) {
            BecomeDonorRoute(onDone = { navController.popBackStack() })
        }
        composable<CreateRequest> {
            CreateRequestRoute(
                onClose = { navController.popBackStack() },
                onPosted = { id, notice ->
                    navController.navigate(RequestDetail(id = id, notice = notice?.name)) {
                        popUpTo<CreateRequest> { inclusive = true }
                    }
                },
                // D21: Requests opens on My requests, not on the tab used last. Request blood closes
                // first, so it isn't kept in the old tab's back stack.
                onOpenMyRequests = dropUnlessResumed {
                    RequestsTabRequest.open(RequestsTab.MINE)
                    navController.popBackStack()
                    navController.navigateToTab(TopLevelDestination.REQUESTS)
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

/** D31: the request link kept on this phone ([PendingRequestStore]), for [AppNavHost]. */
@HiltViewModel
class PendingRequestViewModel @Inject constructor(private val store: PendingRequestStore) : ViewModel() {
    val pending: StateFlow<String?> = store.pending.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    suspend fun take(): String? = store.take()
}
