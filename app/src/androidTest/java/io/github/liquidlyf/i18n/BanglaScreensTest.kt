package io.github.liquidlyf.i18n

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Phase 8.4: the screens of each Bangla review set, drawn in Bangla. Each test checks that the
 * Bangla text is what shows (not the English fallback), and saves a screenshot for the review to
 * the app's files folder on the phone:
 * `/sdcard/Android/data/io.github.liquidlyf.demo/files/bn-review/`.
 */
@RunWith(AndroidJUnit4::class)
class BanglaScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val bangla = target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag("bn")) })

    private fun bn(@StringRes id: Int, vararg args: Any): String = bangla.getString(id, *args)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides bangla,
                LocalConfiguration provides bangla.resources.configuration,
                LocalResources provides bangla.resources,
                LocalSnackbarHostState provides SnackbarHostState(),
            ) {
                LiquidLyfTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun assertShows(@StringRes id: Int, vararg args: Any) {
        val text = bn(id, *args)
        // The phone runs in English, so a missing translation would show the English text here.
        check(text != target.getString(id, *args)) { "No Bangla for ${target.resources.getResourceEntryName(id)}" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun save(name: String, dialog: Boolean = false) {
        val image = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        val dir = File(target.getExternalFilesDir(null), "bn-review").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---- set 1: safety and medical ----

    @Test
    fun set1OnboardingEndsWithTheMedicalDisclaimer() {
        show { io.github.liquidlyf.feature.onboarding.OnboardingScreen(onFinished = {}) }
        assertShows(R.string.onboarding_find_title)
        save("set1-onboarding-1")
        compose.onNode(androidx.compose.ui.test.hasText(bn(R.string.action_skip))).performClick()
        assertShows(R.string.onboarding_medical_title)
        compose.waitForIdle()
        save("set1-onboarding-medical")
    }

    @Test
    fun set1SafetyTips() {
        show { io.github.liquidlyf.feature.safety.SafetyTipsScreen(onBack = {}, onCallEmergency = {}) }
        assertShows(R.string.title_safety_tips)
        assertShows(R.string.safety_tip_money_title)
        save("set1-safety-tips")
    }

    @Test
    fun set1MedicalDisclaimerAndMatching() {
        show { io.github.liquidlyf.feature.about.MedicalDisclaimerScreen(onBack = {}) }
        assertShows(R.string.title_medical_disclaimer)
        save("set1-medical-disclaimer")
    }

    @Test
    fun set1HowMatchingWorks() {
        show { io.github.liquidlyf.feature.about.HowMatchingWorksScreen(onBack = {}) }
        assertShows(R.string.matching_who_title)
        save("set1-how-matching-works")
    }

    @Test
    fun set1ReportDialog() {
        show { io.github.liquidlyf.feature.safety.ReportDialog(R.string.detail_report_title, onDismiss = {}, onReport = { _, _ -> }) }
        assertShows(R.string.report_reason_money)
        save("set1-report", dialog = true)
    }

    @Test
    fun set1Suspended() {
        show { io.github.liquidlyf.feature.session.SuspendedScreen(onSignOut = {}) }
        assertShows(R.string.suspended_title)
        save("set1-suspended")
    }
}
