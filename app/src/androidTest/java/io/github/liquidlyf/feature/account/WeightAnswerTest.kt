package io.github.liquidlyf.feature.account

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Locale

/**
 * D23 a on the real Edit profile screen (fakes behind it): a donor who answered না changes it to
 * হ্যাঁ and taps Save; the "list me" question comes up with nothing chosen, and its yes lists them.
 * A listed donor who changes হ্যাঁ to না is taken off the list by the same Save, with no question.
 */
@RunWith(AndroidJUnit4::class)
class WeightAnswerTest {
    @get:Rule val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private var context: Context = target
    private val now: Instant = Instant.now()
    private val geo: BdGeo = target.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) }
    private lateinit var vm: EditProfileViewModel

    /** Saved and about to close (the screen closes after its "saved" snackbar, which has no host here). */
    private val saved get() = vm.uiState.value.saved

    private fun show(me: UserProfile, language: String, fontScale: Float): FakeProfileRepository {
        context = target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
        val profiles = FakeProfileRepository(now).apply { profiles[me.uid] = me }
        vm = EditProfileViewModel(
            FakeSessionManager(SessionState.Ready(TestData.user(uid = me.uid), me)), profiles, FakeAuthRepository(),
            object : GeoRepository { override suspend fun geo() = this@WeightAnswerTest.geo }, Clock { now }, EligibilityCalculator(),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalSnackbarHostState provides remember { SnackbarHostState() },
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                LiquidLyfTheme(darkTheme = false) { EditProfileRoute(onDone = {}, viewModel = vm) }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(str(R.string.setup_weight_question))).fetchSemanticsNodes().isNotEmpty() }
        return profiles
    }

    private fun str(@StringRes id: Int): String = context.getString(id)

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun answer(@StringRes id: Int) {
        val onPage = !hasAnyAncestor(isDialog())
        compose.onNode(hasText(str(id)) and hasClickAction() and onPage).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun save() {
        compose.onNode(hasText(str(R.string.action_save)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test fun backToYesAsksListMeAgain_englishAt100() = backToYesAsksListMeAgain("en", 1f)

    @Test fun backToYesAsksListMeAgain_banglaAt200() = backToYesAsksListMeAgain("bn", 2f)

    private fun backToYesAsksListMeAgain(language: String, fontScale: Float) {
        val me = TestData.profile(uid = "me").copy(fullName = "Rina Akter", weightOk = false, showInDonorSearch = false, consentAt = null)
        val profiles = show(me, language, fontScale)
        answer(R.string.answer_yes)
        save()

        // The question, with the app's own words and nothing chosen for them.
        val yes = hasText(str(R.string.consent_yes)) and hasClickAction() and hasAnyAncestor(isDialog())
        waitFor(yes)
        compose.onNode(hasText(str(R.string.home_eligible_now)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertEquals(true, profiles.profiles.getValue("me").weightOk)
        assertFalse(profiles.profiles.getValue("me").showInDonorSearch)
        assertFalse("Edit profile closed before the question", saved)

        Thread.sleep(DIALOG_MS) // its window takes touches once it has opened
        compose.onNode(yes).performClick()
        compose.waitUntil(10_000) { saved }
        assertTrue(profiles.profiles.getValue("me").showInDonorSearch)
        assertEquals(listOf(true), profiles.listingWrites)
    }

    @Test
    fun yesToNoUnlistsInTheSameSave() {
        val me = TestData.profile(uid = "me").copy(fullName = "Rina Akter") // listed, weight হ্যাঁ
        val profiles = show(me, "bn", 1f)
        answer(R.string.answer_no)
        save()
        compose.waitUntil(10_000) { saved }
        assertEquals(false, profiles.profiles.getValue("me").weightOk)
        assertFalse(profiles.profiles.getValue("me").showInDonorSearch)
        assertTrue(compose.onAllNodes(hasAnyAncestor(isDialog())).fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        const val DIALOG_MS = 500L
    }
}
