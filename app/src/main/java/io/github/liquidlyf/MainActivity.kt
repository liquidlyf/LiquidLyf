package io.github.liquidlyf

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.liquidlyf.core.data.auth.AccountChecker
import io.github.liquidlyf.core.data.auth.RequestedEmailStore
import io.github.liquidlyf.core.data.link.PendingRequestStore
import io.github.liquidlyf.core.data.link.RequestLinks
import io.github.liquidlyf.core.data.settings.OffScreenLanguage
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.SystemBarScrims
import io.github.liquidlyf.core.designsystem.components.LocalStartLogoHandoff
import io.github.liquidlyf.core.designsystem.components.StartLogoHandoff
import io.github.liquidlyf.core.designsystem.isDark
import io.github.liquidlyf.feature.session.SigningOutDialog
import io.github.liquidlyf.navigation.AppNavHost
import io.github.liquidlyf.notifications.NotificationChannels
import io.github.liquidlyf.start.StartClock
import io.github.liquidlyf.start.StartLogo
import io.github.liquidlyf.start.StartLogoArts
import io.github.liquidlyf.start.StartScreen
import io.github.liquidlyf.start.StartViews
import io.github.liquidlyf.start.animationsOff
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The only activity. AppCompatActivity (not ComponentActivity) so per-app language works
 * on Android 12 and older (Phase 8). Content is drawn edge to edge; each screen's Scaffold
 * keeps it clear of the status, navigation and keyboard areas (BUG-015).
 *
 * D25, at a fresh start: Android shows the still picture (the stacked logo) until the start data is
 * read, as before, but no longer than [StartClock.HOLD_MS]. From the app's first frame, [StartLogo]
 * draws the same logo in the same place, while loading and while the first screen is built under it,
 * then moves it into that screen's logo. Turning the phone or a restore shows no start logo.
 *
 * Nothing is composed while the still picture is up and loading goes on (as before), so the first
 * screen isn't delayed: the copy is composed with it, or just before the hold's end if still loading.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var pendingRequests: PendingRequestStore
    @Inject lateinit var accountChecker: AccountChecker
    @Inject lateinit var requestedEmails: RequestedEmailStore

    override fun onStart() {
        super.onStart()
        // D32: the account is read again at start and on each return (at most every 10 minutes).
        accountChecker.onAppVisible()
    }

    /**
     * The app's language for notifications ([OffScreenLanguage]): keeps a choice made before it was
     * kept there, or (Android 13+) in the phone's own app settings, and names the channels in it
     * (a language change restarts this screen, so they follow it).
     */
    private fun keepLanguageForNotifications() {
        val tag = AppCompatDelegate.getApplicationLocales()[0]?.language.orEmpty()
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            OffScreenLanguage.remember(app, tag)
            NotificationChannels.createAll(OffScreenLanguage.wrap(app))
        }
    }

    /**
     * D31: a request link (a share's https://liquidlyf.github.io/r/#id, or the web page's button) is
     * kept on this phone until the person is in the app, which then opens it ([AppNavHost]). Signed
     * in, that's at once; otherwise after sign-in, the email and setup.
     */
    private fun keepRequestLink(intent: Intent?) {
        val id = RequestLinks.idFromAny(intent?.dataString) ?: return
        lifecycleScope.launch { pendingRequests.keep(id) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: a link tapped while the app is open comes here, not to a new screen.
        keepRequestLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val start = if (savedInstanceState == null) StartClock.begin() else null
        keepLanguageForNotifications()
        if (savedInstanceState == null) {
            lifecycleScope.launch { pendingRequests.dropIfOld() } // before a new one is kept
            lifecycleScope.launch { requestedEmails.dropIfOld() } // D34: 3 days after "Change email"
            keepRequestLink(intent)
        }
        val arts = start?.let { StartLogoArts(resources, it) }
        val reduced = animationsOff(this)
        // The drop and the line on the render thread (A46); not with Remove animations, nor before 7.1.
        var views: StartViews? = null
        // Still loading near the hold's end: compose the copy, so it's there when the still picture goes.
        val copyWanted = mutableStateOf(start != null && start.now() >= StartClock.HOLD_MS - COPY_LEAD_MS)
        var copyComposed = false
        var released = false
        // Polled every frame while the still picture is up.
        splashScreen.setKeepOnScreenCondition {
            val loading = viewModel.uiState.value is MainUiState.Loading
            if (start != null && loading && start.now() >= StartClock.HOLD_MS - COPY_LEAD_MS) copyWanted.value = true
            val keep = loading && (start == null || !start.holdOver() || !copyComposed)
            if (!keep && !released) {
                released = true
                // The app's first frame: still loading, the drop and the line start; ready, they're not needed.
                if (loading) views?.show(appDark()) else views?.remove()
            }
            keep
        }
        if (start != null) {
            lifecycleScope.launch {
                viewModel.uiState.first { it is MainUiState.Ready }
                start.log("start data read")
            }
        }
        // Android 12+ would fade its still picture out over the app's identical copy: remove it at once.
        if (start != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        val handoff = start?.let { StartLogoHandoff() }
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = uiState as? MainUiState.Ready
            val starting = handoff != null && !handoff.done
            val showCopy = starting && (ready != null || copyWanted.value)
            if (ready == null && !showCopy) return@setContent
            // While loading, the theme setting (once read), else the phone's, as the still picture.
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val darkTheme = (ready?.themeMode ?: themeMode)?.isDark() ?: isSystemInDarkTheme()

            // System bar icons follow the app theme, which can differ from the phone's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(SystemBarScrims.LIGHT, SystemBarScrims.DARK) { darkTheme },
                )
                onDispose {}
            }

            val landing = remember { mutableStateOf<StartScreen?>(null) }
            LiquidLyfTheme(darkTheme = darkTheme) {
                CompositionLocalProvider(LocalStartLogoHandoff provides handoff?.takeIf { starting }) {
                    if (ready != null) {
                        AppNavHost(
                            startZone = ready.startZone,
                            session = ready.session,
                            initialTab = ready.initialTab,
                            onOnboardingFinished = viewModel::onOnboardingFinished,
                            onTabSelected = viewModel::onTabSelected,
                            onRetrySession = viewModel::retrySession,
                            onSignOut = viewModel::signOut,
                            onStartScreen = { screen ->
                                start?.log("screen $screen")
                                landing.value = screen
                            },
                        )
                        val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
                        if (signingOut) SigningOutDialog()
                    }
                }
                if (showCopy && handoff != null && start != null && arts != null) {
                    StartLogo(
                        handoff, start, arts,
                        landing = { landing.value },
                        reduced = reduced,
                        views = views,
                        loading = { viewModel.uiState.value is MainUiState.Loading },
                        onComposed = { copyComposed = true },
                    )
                }
            }
        }
        if (start != null && !reduced && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            views = StartViews(this, start, phoneDark()) { viewModel.uiState.value is MainUiState.Loading }
        }
    }

    /** The app's theme setting once it's read, else the phone's, as the still picture. */
    private fun appDark(): Boolean = when (viewModel.themeMode.value) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM, null -> phoneDark()
    }

    private fun phoneDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private companion object {
        /** How long before the hold's end a still-loading start composes the copy. */
        const val COPY_LEAD_MS = 200L
    }
}
