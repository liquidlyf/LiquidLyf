package io.github.liquidlyf

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.feature.auth.ForgotPasswordScreen
import io.github.liquidlyf.feature.auth.ForgotPasswordUiState
import io.github.liquidlyf.feature.auth.SignInScreen
import io.github.liquidlyf.feature.auth.SignInUiState
import io.github.liquidlyf.feature.auth.SignUpScreen
import io.github.liquidlyf.feature.auth.SignUpUiState
import io.github.liquidlyf.feature.auth.VerifyEmailScreen
import io.github.liquidlyf.feature.auth.VerifyEmailUiState
import io.github.liquidlyf.feature.onboarding.OnboardingScreen
import io.github.liquidlyf.feature.profilesetup.ProfileSetupScreen
import io.github.liquidlyf.feature.profilesetup.ProfileSetupUiState
import io.github.liquidlyf.feature.profilesetup.SetupDraft
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 acceptance "TalkBack can complete sign-in": every control on the sign-in and setup
 * screens has a label TalkBack can read (text or a content description), every text field has a
 * label, and every control is at least 48 dp in both directions. Runs the real screens on the phone.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityLabelsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                LiquidLyfTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNode.label(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.takeIf { it.isNotBlank() }
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.takeIf { it.isNotBlank() }

    /** Fails with a list of every unlabelled or too-small control on the screen. */
    private fun assertControlsAreLabelledAndBigEnough(screen: String) {
        val problems = mutableListOf<String>()
        val minPx = with(compose.density) { 48.dp.toPx() } - 1f // rounding
        val controls = compose.onAllNodes(hasClickAction() or hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("$screen: found no controls at all, so the check proves nothing", controls.size >= 2)
        for (node in controls) {
            val label = node.label()
            if (label == null) problems += "no label: node ${node.id} ${node.config}"
            val size = node.touchBoundsInRoot
            if (size.width < minPx || size.height < minPx) {
                problems += "smaller than 48 dp (${size.width.toInt()}x${size.height.toInt()} px): \"$label\""
            }
        }
        assertTrue("$screen:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun onboarding() {
        show { OnboardingScreen(onFinished = {}) }
        assertControlsAreLabelledAndBigEnough("Onboarding")
    }

    @Test
    fun signInIncludingGoogle() {
        show { SignInScreen(SignInUiState(), {}, {}, {}, {}, {}, onContinueWithGoogle = {}) }
        assertControlsAreLabelledAndBigEnough("Sign in")
    }

    @Test
    fun signUp() {
        show { SignUpScreen(SignUpUiState(), {}, {}, {}, {}, {}, {}) }
        assertControlsAreLabelledAndBigEnough("Sign up")
    }

    @Test
    fun verifyEmail() {
        show { VerifyEmailScreen(VerifyEmailUiState(email = "rahim@example.test", resendIn = 0), {}, {}, {}) }
        assertControlsAreLabelledAndBigEnough("Verify email")
    }

    @Test
    fun forgotPassword() {
        show { ForgotPasswordScreen(ForgotPasswordUiState(), {}, {}, {}) }
        assertControlsAreLabelledAndBigEnough("Forgot password")
    }

    @Test
    fun everyProfileSetupStep() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val geo = context.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) }
        val draft = SetupDraft(uid = "u1", division = "BD30", district = "BD3026", upazila = "BD30262000", bloodGroup = "B_POS")
        val step = mutableIntStateOf(0)
        show {
            val state = ProfileSetupUiState(draft = draft.copy(step = step.intValue), geo = geo, loaded = true, needsTerms = true)
            ProfileSetupScreen(state, AppLocale.EN, {}, {}, {}, {}, {})
        }
        for (s in 0..3) {
            compose.runOnUiThread { step.intValue = s }
            compose.waitForIdle()
            assertControlsAreLabelledAndBigEnough("Profile setup step ${s + 1}")
        }
    }
}
