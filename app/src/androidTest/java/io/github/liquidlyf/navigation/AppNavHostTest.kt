package io.github.liquidlyf.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
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
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.HiltTestActivity
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On the phone: the auth gate moves the user as the session changes and clears the back stack
 * (BUG-003); tabs keep their own back stacks, Back goes to the tab root, then Home, then closes
 * the app; sign-in screens never stack (BUG-031, BUG-032). The session is driven by the test, so
 * no Firebase is needed. Run: ./gradlew connectedDemoDebugAndroidTest (phone unlocked).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hilt.inject()

    private val session = mutableStateOf<SessionState>(SessionState.SignedOut)
    private val ready = SessionState.Ready(TestData.user(), TestData.profile())
    private val selectedTabs = mutableListOf<TopLevelDestination>()
    private var onboardingFinished = false
    private var retries = 0
    private var signOuts = 0

    private fun str(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun launch(start: GateZone, state: SessionState, initialTab: TopLevelDestination? = null) {
        session.value = state
        compose.setContent {
            LiquidLyfTheme {
                AppNavHost(
                    startZone = start,
                    session = session.value,
                    initialTab = initialTab,
                    onOnboardingFinished = { onboardingFinished = true },
                    onTabSelected = { selectedTabs += it },
                    onRetrySession = { retries++ },
                    onSignOut = { signOuts++ },
                )
            }
        }
    }

    private fun setSession(state: SessionState) {
        compose.runOnUiThread { session.value = state }
        compose.waitForIdle()
    }

    /** Waits up to 5 s: some screens load in the background (profile setup reads the area list). */
    private fun assertOnScreen(@StringRes text: Int, vararg args: Any) {
        val expected = str(text, *args)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(expected).onFirst().assertIsDisplayed()
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

    // ---- the auth gate ----

    @Test
    fun theGateFollowsTheSessionFromSignUpToTheAppAndBackOnLogout() {
        launch(GateZone.SIGNED_OUT, SessionState.SignedOut)
        assertOnScreen(R.string.title_sign_in)

        setSession(SessionState.Unverified(TestData.user(verified = false)))
        assertOnScreen(R.string.title_verify_email)

        setSession(SessionState.NeedsProfile(TestData.user()))
        assertOnScreen(R.string.title_profile_setup)
        assertOnScreen(R.string.setup_step, 1, 4)

        // API 30 has no notification permission: setup goes straight to the app.
        setSession(ready)
        assertOnScreen(R.string.nav_home)

        // Logout from deep inside a tab: sign-in, and Back leaves the app (BUG-003).
        tab(R.string.nav_profile).performClick()
        compose.onNode(hasClickAction() and hasContentDescription(str(R.string.action_open_settings))).performClick()
        compose.waitForIdle()
        clickButton(R.string.action_sign_out)
        assertEquals(1, signOuts)
        setSession(SessionState.SignedOut)
        assertOnScreen(R.string.title_sign_in)
        assertAppClosesOnBack()
    }

    @Test
    fun anOfflineProfileOffersRetryAndSignOut() {
        launch(GateZone.SESSION_PROBLEM, SessionState.ProfileUnavailable(TestData.user(), AppError.Network))
        assertOnScreen(R.string.error_network)
        clickButton(R.string.action_retry)
        assertEquals(1, retries)
        setSession(ready)
        assertOnScreen(R.string.nav_home)
    }

    @Test
    fun onboardingIsShownOnceAndBackFromSignInExits() {
        launch(GateZone.ONBOARDING, SessionState.SignedOut)
        assertOnScreen(R.string.onboarding_find_title)
        clickButton(R.string.action_next)
        assertOnScreen(R.string.onboarding_alert_title)
        clickButton(R.string.action_next)
        assertOnScreen(R.string.onboarding_money_title)
        clickButton(R.string.action_get_started)
        assertOnScreen(R.string.title_sign_in)
        assertTrue(onboardingFinished)

        assertAppClosesOnBack()
    }

    @Test
    fun signInScreensNeverStack() {
        launch(GateZone.SIGNED_OUT, SessionState.SignedOut)
        clickButton(R.string.action_create_account)
        assertOnScreen(R.string.label_full_name)
        clickButton(R.string.action_sign_in)
        assertOnScreen(R.string.action_forgot_password)
        clickButton(R.string.action_forgot_password)
        assertOnScreen(R.string.forgot_password_body)
        clickButton(R.string.action_back_to_sign_in)

        // Only one sign-in screen: Back now closes the app.
        assertAppClosesOnBack()
    }

    // ---- inside the app ----

    @Test
    fun tabsKeepTheirOwnBackStacksAndBackReturnsHomeThenExits() {
        launch(GateZone.MAIN, ready)
        tab(R.string.nav_home).assertIsSelected()

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
    fun fullScreenDestinationOpensAboveTheTabsAndBackReturns() {
        launch(GateZone.MAIN, ready)
        assertOnScreen(R.string.home_urgent_near_you)
        // Home's button opens Create request, above the tabs. Found by its spoken label, so this
        // also checks TalkBack can read it (Material's extended FAB alone announced just "Button").
        compose.onNode(hasContentDescription(str(R.string.action_request_blood)) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertOnScreen(R.string.create_patient_title)
        compose.onAllNodes(isSelectable() and hasText(str(R.string.nav_requests))).assertCountEquals(0)

        pressBack()
        assertOnScreen(R.string.home_urgent_near_you)
        tab(R.string.nav_home).assertIsSelected()
    }

    @Test
    fun aLiftedSuspensionLeavesTheSuspendedScreen() {
        launch(GateZone.SUSPENDED, SessionState.Suspended(TestData.user()))
        assertOnScreen(R.string.suspended_title)

        setSession(ready)
        assertOnScreen(R.string.nav_home)
        assertTrue(compose.onAllNodesWithText(str(R.string.suspended_title)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun lastTabIsRestoredWhenTheAppOpens() {
        launch(GateZone.MAIN, ready, initialTab = TopLevelDestination.DONORS)
        assertOnScreen(R.string.nav_donors)
        tab(R.string.nav_donors).assertIsSelected()

        pressBack()
        assertOnScreen(R.string.nav_home)
    }
}
