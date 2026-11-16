package io.github.liquidlyf.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.feature.auth.SignInScreen
import io.github.liquidlyf.feature.auth.SignInUiState
import io.github.liquidlyf.feature.auth.SignUpScreen
import io.github.liquidlyf.feature.auth.SignUpUiState
import io.github.liquidlyf.feature.onboarding.OnboardingScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The maintainer's bug (16 Nov): a new user couldn't pick Bangla before sign-in. Onboarding offers
 * "English | বাংলা" from the first page, and sign-in and sign-up have a top-bar button naming the
 * other language. These tests check the callbacks only; the real store would keep the choice for
 * the tests after.
 */
@RunWith(AndroidJUnit4::class)
class LanguageChoiceTest {

    @get:Rule
    val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val chosen = mutableListOf<AppLanguage>()

    private fun context(tag: String): Context =
        target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    private fun show(tag: String, content: @Composable () -> Unit) {
        val ctx = context(tag)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides ctx,
                LocalConfiguration provides ctx.resources.configuration,
                LocalResources provides ctx.resources,
                LocalSnackbarHostState provides SnackbarHostState(),
            ) {
                LiquidLyfTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theFirstOnboardingPageOffersBangla() {
        show("en") { OnboardingScreen(onFinished = {}, onLanguageChange = { chosen += it }) }
        compose.onNodeWithText("English").assertIsSelected()
        compose.onNodeWithText("বাংলা").assertIsNotSelected().performClick()
        // The language already shown changes nothing, so "Phone's language" stays until a real switch.
        compose.onNodeWithText("English").performClick()
        assertEquals(listOf(AppLanguage.BANGLA), chosen)
    }

    @Test
    fun onboardingInBanglaMarksBanglaAndOffersEnglish() {
        show("bn") { OnboardingScreen(onFinished = {}, onLanguageChange = { chosen += it }) }
        compose.onNodeWithText("বাংলা").assertIsSelected()
        compose.onNodeWithText("English").performClick()
        assertEquals(listOf(AppLanguage.ENGLISH), chosen)
    }

    @Test
    fun signInOffersBangla() {
        show("en") { SignInScreen(SignInUiState(), {}, {}, {}, {}, {}, onLanguageChange = { chosen += it }) }
        compose.onAllNodesWithText("English").assertCountEquals(0)
        compose.onNodeWithText("বাংলা").performClick()
        assertEquals(listOf(AppLanguage.BANGLA), chosen)
    }

    @Test
    fun signUpInBanglaOffersEnglish() {
        show("bn") { SignUpScreen(SignUpUiState(), {}, {}, {}, {}, {}, {}, onLanguageChange = { chosen += it }) }
        compose.onAllNodesWithText("বাংলা").assertCountEquals(0)
        compose.onNodeWithText("English").performClick()
        assertEquals(listOf(AppLanguage.ENGLISH), chosen)
    }
}
