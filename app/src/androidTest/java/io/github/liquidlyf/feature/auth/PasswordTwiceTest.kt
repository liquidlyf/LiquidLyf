package io.github.liquidlyf.feature.auth

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.testing.FakeAuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * D24 on the real Create account screen (a fake sign-in behind it), with the phone's own keyboard:
 * the keyboard's Next goes to the second box; a short password turns red only once it's left; a
 * wrong letter in the second box shows its red line at once, above the keyboard and the pinned
 * button, even at the largest Bangla text; and TalkBack never gets the count.
 */
@RunWith(AndroidJUnit4::class)
class PasswordTwiceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private var context: Context = target

    private fun show(language: String, fontScale: Float): SignUpViewModel {
        context = target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
        val vm = SignUpViewModel(FakeAuthRepository())
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() } // as MainActivity: the screen handles the keyboard itself
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalSnackbarHostState provides remember { SnackbarHostState() },
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                LiquidLyfTheme(darkTheme = false) {
                    val state by vm.uiState.collectAsState()
                    SignUpScreen(
                        state, vm::onNameChange, vm::onEmailChange, vm::onPasswordChange, vm::onConsentChange, vm::signUp, onBack = {},
                        onPasswordAgainChange = vm::onPasswordAgainChange, onPasswordFocus = vm::onPasswordFocus,
                        onPasswordAgainFocus = vm::onPasswordAgainFocus,
                    )
                }
            }
        }
        return vm
    }

    private fun str(@StringRes id: Int): String = context.getString(id)

    /** A box by its name: on the box's border, or above it with large text (then its description). */
    private fun box(@StringRes label: Int): SemanticsMatcher = hasSetTextAction() and (hasText(str(label)) or hasContentDescription(str(label)))

    private fun typeInto(@StringRes label: Int, text: String) {
        compose.onNode(box(label)).performScrollTo().performClick()
        waitForKeyboard()
        compose.onNode(box(label)).performTextInput(text)
        settle()
    }

    private fun imeBottom(): Int =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun waitForKeyboard() {
        compose.waitUntil(KEYBOARD_WAIT_MS) { imeBottom() > 0 }
        settle()
    }

    /** The keyboard's slide and the scroll that follows it. */
    private fun settle() {
        Thread.sleep(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun assertAboveTheBar(text: String, what: String) {
        val line = compose.onNode(hasText(text)).fetchSemanticsNode()
        val button = compose.onNode(hasText(str(R.string.action_create_account)) and hasClickAction()).fetchSemanticsNode()
        val barTop = button.positionInRoot.y - with(compose.density) { Spacing.sm.toPx() }
        val top = line.positionInRoot.y
        val bottom = top + line.size.height
        assertTrue("$what: the line ends at $bottom px, the pinned bar starts at $barTop px", bottom <= barTop + 0.5f)
        assertTrue("$what: the line starts at $top px, above the screen", top >= 0f)
    }

    @Test
    fun nextGoesToTheSecondBox_aShortPasswordTurnsRedOnlyOnceLeft_andTalkBackGetsNoCount() {
        show("en", 1f)
        typeInto(R.string.label_password, "rokto")
        // Grey while typing, with the rule from the start; the count is for the eye only.
        assertTrue(compose.onAllNodesWithText(str(R.string.error_password_short)).fetchSemanticsNodes().isEmpty())
        val rule = compose.onNode(hasText(str(R.string.password_rule))).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, rule.config[SemanticsProperties.LiveRegion])
        assertTrue("the count reached TalkBack", compose.onAllNodesWithText("5/8", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        compose.onNode(box(R.string.label_password)).performImeAction() // Next
        settle()
        compose.onNode(box(R.string.label_confirm_password)).assertIsFocused()
        val red = compose.onNode(hasText(str(R.string.error_password_short))).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, red.config[SemanticsProperties.LiveRegion])

        // A correct start in the second box: no line yet.
        compose.onNode(box(R.string.label_confirm_password)).performTextInput("rokto")
        settle()
        assertTrue(compose.onAllNodesWithText(str(R.string.error_passwords_differ)).fetchSemanticsNodes().isEmpty())

        // Long enough now: the first box's red goes at once; the second box, left unfinished, is red.
        typeInto(R.string.label_password, "2026")
        assertTrue(compose.onAllNodesWithText(str(R.string.error_password_short)).fetchSemanticsNodes().isEmpty())
        // A44: at 8 the words stay; TalkBack hears the tick once: "Done, At least 8 characters".
        val met = compose.onNode(hasText(str(R.string.password_rule)) and hasContentDescription(str(R.string.password_rule_met))).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, met.config[SemanticsProperties.LiveRegion])
        assertTrue("the second box, left unfinished", compose.onAllNodesWithText(str(R.string.error_passwords_differ)).fetchSemanticsNodes().isNotEmpty())

        typeInto(R.string.label_confirm_password, "2026")
        assertAboveTheBar(str(R.string.passwords_match), "Passwords match")
        assertTrue(compose.onAllNodesWithText(str(R.string.error_passwords_differ)).fetchSemanticsNodes().isEmpty())

        // VT-13: Done on the second box closes the keyboard and shows the 18+ box.
        compose.onNode(box(R.string.label_confirm_password)).performImeAction()
        compose.waitUntil(KEYBOARD_WAIT_MS) { imeBottom() == 0 }
        settle()
        assertAboveTheBar(str(R.string.sign_up_consent), "the 18+ box, after Done")
    }

    @Test fun aWrongLetterShowsTheRedLineAboveTheKeyboard_banglaAt200() = wrongLetter("bn", 2f)

    @Test fun aWrongLetterShowsTheRedLineAboveTheKeyboard_banglaAt130() = wrongLetter("bn", 1.3f)

    @Test fun aWrongLetterShowsTheRedLineAboveTheKeyboard_englishAt100() = wrongLetter("en", 1f)

    private fun wrongLetter(language: String, fontScale: Float) {
        show(language, fontScale)
        typeInto(R.string.label_password, "rokto2026")
        typeInto(R.string.label_confirm_password, "rokto2O") // a capital O for the zero
        assertAboveTheBar(str(R.string.error_passwords_differ), "$language at $fontScale, a wrong letter")
    }

    @Test
    fun createAccountWithADifferentSecondBoxBringsItsRedLineIntoView_banglaAt200() {
        show("bn", 2f)
        typeInto(R.string.label_full_name, "Nusrat Jahan")
        typeInto(R.string.label_email, "nusrat@example.test")
        typeInto(R.string.label_password, "rokto2026")
        typeInto(R.string.label_confirm_password, "rokto") // a correct start, then the button
        compose.onNode(hasText(str(R.string.sign_up_consent)) and hasClickAction()).performScrollTo().performClick()
        settle()
        compose.onNode(hasText(str(R.string.action_create_account)) and hasClickAction()).performClick()
        waitForKeyboard() // the red box gets the focus, and the keyboard comes up
        compose.onNode(box(R.string.label_confirm_password)).assertIsFocused()
        assertAboveTheBar(str(R.string.error_passwords_differ), "after Create account")
    }

    private companion object {
        const val KEYBOARD_WAIT_MS = 5_000L
        const val SETTLE_MS = 900L
    }
}
