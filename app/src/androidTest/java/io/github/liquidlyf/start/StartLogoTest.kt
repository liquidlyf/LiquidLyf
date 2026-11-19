package io.github.liquidlyf.start

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.HiltTestActivity
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalStartLogoHandoff
import io.github.liquidlyf.core.designsystem.components.StartLogoHandoff
import io.github.liquidlyf.navigation.AppNavHost
import io.github.liquidlyf.navigation.GateZone
import io.github.liquidlyf.navigation.TopLevelDestination
import io.github.liquidlyf.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D25 on the phone: which way the start logo leaves for each first screen, that it lands (the
 * screen's own logo then shows), "Remove animations", and what TalkBack hears. The app's real
 * screens with fake data behind them, as AppNavHostTest; the start is timed from 2 s before.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StartLogoTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hilt.inject()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val handoff = StartLogoHandoff()
    private val reported = mutableListOf<StartScreen>()

    private fun str(@StringRes id: Int): String = context.getString(id)

    /** As MainActivity: the first screen under the start logo, which moves into it when it's reported. */
    private fun start(zone: GateZone, session: SessionState, initialTab: TopLevelDestination? = null) {
        val clock = StartClock(SystemClock.uptimeMillis() - SINCE_TAP_MS)
        val arts = StartLogoArts(context.resources, clock)
        compose.setContent {
            val landing = remember { mutableStateOf<StartScreen?>(null) }
            LiquidLyfTheme {
                CompositionLocalProvider(LocalStartLogoHandoff provides handoff.takeUnless { it.done }) {
                    AppNavHost(
                        startZone = zone,
                        session = session,
                        initialTab = initialTab,
                        onOnboardingFinished = {},
                        onTabSelected = {},
                        onRetrySession = {},
                        onSignOut = {},
                        onStartScreen = {
                            reported += it
                            landing.value = it
                        },
                    )
                }
                if (!handoff.done) StartLogo(handoff, clock, arts, landing = { landing.value })
            }
        }
        compose.waitUntil(10_000) { handoff.done }
        compose.waitForIdle()
    }

    /** The screen's own logo: its label is the app's name, and only it has one (the moving copy says nothing). */
    private fun assertTheScreensLogoShows() {
        val logo = compose.onNode(hasContentDescription(str(R.string.app_name)))
        logo.assertIsDisplayed()
        assertFalse(handoff.covering)
    }

    @Test
    fun theFirstStartAfterInstallingGlidesIntoTheWelcomePage() {
        start(GateZone.ONBOARDING, SessionState.SignedOut)
        assertEquals(listOf(StartScreen.WELCOME), reported)
        assertTheScreensLogoShows()
    }

    @Test
    fun signedOutGlidesIntoSignIn() {
        start(GateZone.SIGNED_OUT, SessionState.SignedOut)
        assertEquals(listOf(StartScreen.SIGN_IN), reported)
        assertTheScreensLogoShows()
    }

    @Test
    fun signedInShrinksIntoHomesTopBar() {
        start(GateZone.MAIN, SessionState.Ready(TestData.user(), TestData.profile()), TopLevelDestination.HOME)
        assertEquals(listOf(StartScreen.HOME), reported)
        assertTheScreensLogoShows()
    }

    @Test
    fun theLastTabUsedOpensBehindTheLogoWhichFades() {
        start(GateZone.MAIN, SessionState.Ready(TestData.user(), TestData.profile()), TopLevelDestination.PROFILE)
        // Reported once, on Profile (the hop to it was made behind the logo), so it fades.
        assertEquals(listOf(StartScreen.OTHER), reported)
        compose.onNode(hasText(str(R.string.nav_profile)) and isSelectable()).assertIsSelected()
        assertFalse(handoff.covering)
    }

    @Test
    fun aScreenWithNoLogoFades() {
        start(GateZone.VERIFY_EMAIL, SessionState.Unverified(TestData.user(verified = false)))
        assertEquals(listOf(StartScreen.OTHER), reported)
    }

    @Test
    fun whileLoadingTalkBackHearsLoadingOnceAndTheLogoCopySaysNothing() {
        showLoading(reduced = false)
        val loading = str(R.string.state_loading)
        // The thin line: read as "Loading…", once, as it appears (a polite live region).
        compose.onNode(hasContentDescription(loading) and isPoliteLiveRegion).assertExists()
        // The copy of the logo isn't something to read.
        assertEquals(0, compose.onAllNodes(hasContentDescription(str(R.string.app_name))).fetchSemanticsNodes().size)
    }

    @Test
    fun withRemoveAnimationsTheStillWordsShowInsteadOfTheLine() {
        showLoading(reduced = true)
        val loading = str(R.string.state_loading)
        compose.onNode(hasText(loading) and isPoliteLiveRegion).assertIsDisplayed()
    }

    @Test
    fun onceTheStartDataIsReadTheLineDoesntShowOrSpeak() {
        val clock = StartClock(SystemClock.uptimeMillis() - SINCE_TAP_MS)
        val arts = StartLogoArts(context.resources, clock)
        compose.setContent {
            LiquidLyfTheme { StartLogo(handoff, clock, arts, landing = { null }, reduced = false, loading = { false }) }
        }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasContentDescription(str(R.string.state_loading))).fetchSemanticsNodes().size)
    }

    /** Still loading 2 s after the tap: the line (or the words) shows at once. */
    private fun showLoading(reduced: Boolean) {
        val clock = StartClock(SystemClock.uptimeMillis() - SINCE_TAP_MS)
        val arts = StartLogoArts(context.resources, clock)
        compose.setContent {
            LiquidLyfTheme { StartLogo(handoff, clock, arts, landing = { null }, reduced = reduced) }
        }
        val loading = str(R.string.state_loading)
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(loading) or hasText(loading)).fetchSemanticsNodes().isNotEmpty() }
    }

    private val isPoliteLiveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private companion object {
        const val SINCE_TAP_MS = 2_000L
    }
}
