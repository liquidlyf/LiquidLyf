package io.github.liquidlyf

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.SystemBarScrims
import io.github.liquidlyf.core.designsystem.components.LocalStartLogoHandoff
import io.github.liquidlyf.core.designsystem.components.StartLogoHandoff
import io.github.liquidlyf.core.designsystem.isDark
import io.github.liquidlyf.feature.session.SigningOutDialog
import io.github.liquidlyf.navigation.AppNavHost
import io.github.liquidlyf.start.StartClock
import io.github.liquidlyf.start.StartLogo
import io.github.liquidlyf.start.StartLogoArts
import io.github.liquidlyf.start.StartScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        val start = if (savedInstanceState == null) StartClock.begin() else null
        val arts = start?.let { StartLogoArts(resources, it) }
        // Still loading near the hold's end: compose the copy, so it's there when the still picture goes.
        val copyWanted = mutableStateOf(false)
        var copyComposed = false
        splashScreen.setKeepOnScreenCondition {
            viewModel.uiState.value is MainUiState.Loading && (start == null || !start.holdOver() || !copyComposed)
        }
        if (start != null) {
            val copyIn = (StartClock.HOLD_MS - COPY_LEAD_MS - start.now()).coerceAtLeast(0)
            Handler(Looper.getMainLooper()).postDelayed({ copyWanted.value = true }, copyIn)
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
                    StartLogo(handoff, start, arts, landing = { landing.value }, onComposed = { copyComposed = true })
                }
            }
        }
    }

    private companion object {
        /** How long before the hold's end a still-loading start composes the copy. */
        const val COPY_LEAD_MS = 200L
    }
}
