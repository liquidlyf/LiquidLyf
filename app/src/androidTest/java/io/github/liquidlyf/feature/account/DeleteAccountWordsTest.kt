package io.github.liquidlyf.feature.account

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.testing.FakeAccountDeleter
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * A23 on the phone: account deletion is confirmed with DELETE (any case) or মুছুন, in either
 * language. The real screen and view model, on fakes (nothing is deleted).
 */
@RunWith(AndroidJUnit4::class)
class DeleteAccountWordsTest {

    @get:Rule
    val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val auth = FakeAuthRepository()
    private val deleter = FakeAccountDeleter()

    private fun inLanguage(tag: String): Context =
        target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    private fun deleteWith(word: String, language: String) {
        auth.accounts["rahim@example.test"] = FakeAuthRepository.Account("u1", "old-password", "Rahim", verified = true)
        runBlocking { auth.signInEmail("rahim@example.test", "old-password") }
        val session = FakeSessionManager(SessionState.Ready(TestData.user("u1"), TestData.profile("u1")))
        val vm = DeleteAccountViewModel(auth, deleter, session)
        val context = inLanguage(language)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalSnackbarHostState provides SnackbarHostState(),
            ) {
                LiquidLyfTheme { DeleteAccountRoute(onBack = {}, viewModel = vm) }
            }
        }
        val instruction = context.getString(R.string.delete_account_type, context.getString(R.string.delete_account_word))
        compose.onNode(hasSetTextAction() and hasText(instruction)).performTextInput(word)
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.label_password))).performTextInput("old-password")
        compose.onNode(hasClickAction() and hasText(context.getString(R.string.action_delete_account))).performScrollTo().performClick()
        compose.waitUntil(5_000) { deleter.pendingUid != null }
        assertEquals("u1", deleter.pendingUid)
    }

    @Test
    fun theBanglaWordDeletesInBangla() = deleteWith("মুছুন", "bn")

    @Test
    fun deleteInAnyCaseDeletesInBangla() = deleteWith("delete", "bn")

    @Test
    fun theBanglaWordAlsoDeletesInEnglish() = deleteWith("মুছুন", "en")
}
