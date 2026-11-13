package io.github.liquidlyf

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.SystemBarScrims
import io.github.liquidlyf.core.designsystem.isDark
import io.github.liquidlyf.navigation.AppNavHost

/**
 * The only activity. AppCompatActivity (not ComponentActivity) so per-app language works
 * on Android 12 and older (Phase 8). Content is drawn edge to edge; each screen's Scaffold
 * keeps it clear of the status, navigation and keyboard areas (BUG-015).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        enableEdgeToEdge()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = uiState as? MainUiState.Ready ?: return@setContent
            val darkTheme = ready.themeMode.isDark()

            // System bar icons follow the app theme, which can differ from the phone's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(SystemBarScrims.LIGHT, SystemBarScrims.DARK) { darkTheme },
                )
                onDispose {}
            }

            LiquidLyfTheme(darkTheme = darkTheme) {
                AppNavHost(
                    startWithOnboarding = ready.startWithOnboarding,
                    initialTab = ready.initialTab,
                    onOnboardingFinished = viewModel::onOnboardingFinished,
                    onTabSelected = viewModel::onTabSelected,
                )
            }
        }
    }
}
