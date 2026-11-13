package io.github.liquidlyf.navigation

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 acceptance on a real phone: tabs keep their own back stacks, Back goes to the tab
 * root, then to Home, then closes the app; sign-in screens never stack (BUG-031, BUG-032).
 * Run: ./gradlew connectedDemoDebugAndroidTest (phone unlocked).
 */
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val selectedTabs = mutableListOf<TopLevelDestination>()
    private var onboardingFinished = false

    private fun str(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun launch(startWithOnboarding: Boolean = false, initialTab: TopLevelDestination? = null) {
        compose.setContent {
            LiquidLyfTheme {
                AppNavHost(
                    startWithOnboarding = startWithOnboarding,
                    initialTab = initialTab,
                    onOnboardingFinished = { onboardingFinished = true },
                    onTabSelected = { selectedTabs += it },
                )
            }
        }
    }

    /** Placeholder screens show their title in the top bar (first match). */
    private fun assertOnScreen(@StringRes title: Int) {
        compose.onAllNodesWithText(str(title)).onFirst().assertIsDisplayed()
    }

    private fun tab(@StringRes label: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(str(label)) and isSelectable())

    private fun clickButton(@StringRes label: Int) {
        compose.onNode(hasText(str(label)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun pressBack() {
        Espresso.pressBack()
        compose.waitForIdle()
    }

    private fun assertAppClosesOnBack() {
        val activity = compose.activity
        Espresso.pressBackUnconditionally()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("Back should close the app here", activity.isFinishing || activity.isDestroyed)
    }

    private fun signIn() {
        assertOnScreen(R.string.title_sign_in)
        clickButton(R.string.placeholder_continue_to_app)
        assertOnScreen(R.string.nav_home)
    }

    @Test
    fun tabsKeepTheirOwnBackStacksAndBackReturnsHomeThenExits() {
        launch()
        signIn()
        tab(R.string.nav_home).assertIsSelected()

        // Profile tab: open Settings inside the tab.
        tab(R.string.nav_profile).performClick()
        tab(R.string.nav_profile).assertIsSelected()
        compose.onNode(hasClickAction() and hasContentDescription(str(R.string.action_open_settings))).performClick()
        compose.waitForIdle()
        assertOnScreen(R.string.title_settings)

        // Switch to Requests and back to Profile: Settings is still open (state restored).
        tab(R.string.nav_requests).performClick()
        assertOnScreen(R.string.nav_requests)
        tab(R.string.nav_profile).performClick()
        assertOnScreen(R.string.title_settings)

        // Re-selecting the current tab returns to its root.
        tab(R.string.nav_profile).performClick()
        assertOnScreen(R.string.nav_profile)

        // Back from a tab root goes to Home, and Back from Home closes the app.
        pressBack()
        assertOnScreen(R.string.nav_home)
        tab(R.string.nav_home).assertIsSelected()
        assertEquals(
            listOf(TopLevelDestination.PROFILE, TopLevelDestination.REQUESTS, TopLevelDestination.PROFILE),
            selectedTabs,
        )
        assertAppClosesOnBack()
    }

    @Test
    fun detailScreenOpensAboveTheTabsAndBackReturns() {
        launch()
        signIn()
        tab(R.string.nav_requests).performClick()
        clickButton(R.string.placeholder_open_sample_request)
        assertOnScreen(R.string.title_request_detail)
        compose.onAllNodesWithText(str(R.string.placeholder_request_id, "sample-request")).onFirst().assertIsDisplayed()

        pressBack()
        assertOnScreen(R.string.nav_requests)
        pressBack()
        assertOnScreen(R.string.nav_home)
    }

    @Test
    fun signInScreensNeverStack() {
        launch()
        clickButton(R.string.placeholder_go_to_sign_up)
        assertOnScreen(R.string.title_sign_up)
        clickButton(R.string.placeholder_go_to_sign_in)
        assertOnScreen(R.string.title_sign_in)

        // Only one sign-in screen: Back now closes the app.
        assertAppClosesOnBack()
    }

    @Test
    fun onboardingIsShownOnceAndBackFromSignInExits() {
        launch(startWithOnboarding = true)
        assertOnScreen(R.string.title_onboarding)
        clickButton(R.string.action_get_started)
        assertOnScreen(R.string.title_sign_in)
        assertTrue(onboardingFinished)

        assertAppClosesOnBack()
    }

    @Test
    fun lastTabIsRestoredAfterSignIn() {
        launch(initialTab = TopLevelDestination.DONORS)
        clickButton(R.string.placeholder_continue_to_app)
        assertOnScreen(R.string.nav_donors)
        tab(R.string.nav_donors).assertIsSelected()

        pressBack()
        assertOnScreen(R.string.nav_home)
    }
}
