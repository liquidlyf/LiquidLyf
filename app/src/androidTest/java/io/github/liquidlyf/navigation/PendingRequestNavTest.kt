package io.github.liquidlyf.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.HiltTestActivity
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.link.PendingRequestStore
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * D31 on the phone, every way from a request link to the request page that needs no Firebase (the
 * session is driven by the test, as in AppNavHostTest): the request kept on this phone survives
 * the welcome pages, sign-in, the email, setup (both D28 paths and Skip) and the alerts question,
 * then opens once, over Home; the waiting line shows on Sign in and setup's first page; the newest
 * link wins. Without the emulators the request page shows its error state, which is still the
 * request's page (title, Back to Home). Real requests (open, closed, missing) and the real links: RequestLinkE2ETest.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PendingRequestNavTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Inject lateinit var pending: PendingRequestStore

    private val session = mutableStateOf<SessionState>(SessionState.SignedOut)
    private val ready = SessionState.Ready(TestData.user(), TestData.profile())
    private lateinit var nav: NavHostController
    private val first = "AbCdEfGhIjKlMnOpQrSt"
    private val second = "ZyXwVuTsRqPoNmLkJiHg"

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { pending.take() } // nothing left from another test
    }

    @After
    fun tearDown() {
        runBlocking { pending.take() }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun launch(start: GateZone, state: SessionState) {
        session.value = state
        compose.setContent {
            nav = rememberNavController()
            LiquidLyfTheme {
                AppNavHost(
                    startZone = start,
                    session = session.value,
                    initialTab = null,
                    onOnboardingFinished = {},
                    onTabSelected = {},
                    onRetrySession = {},
                    onSignOut = {},
                    navController = nav,
                )
            }
        }
    }

    private fun setSession(state: SessionState) {
        compose.runOnUiThread { session.value = state }
        compose.waitForIdle()
    }

    private fun assertOnScreen(@StringRes text: Int, vararg args: Any) {
        val expected = str(text, *args)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(expected).onFirst().assertIsDisplayed()
    }

    private fun assertNotOnScreen(@StringRes text: Int) {
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(str(text)).fetchSemanticsNodes().size)
    }

    private fun clickButton(@StringRes label: Int) {
        compose.onNode(hasText(str(label)) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun choose(@StringRes option: Int) {
        compose.onNode(hasText(str(option), substring = true) and isSelectable()).performClick()
        compose.waitForIdle()
    }

    private fun keep(id: String) = runBlocking { pending.keep(id) }

    /** The request page for [id] is showing, the kept one is gone, and Back goes to Home. */
    private fun assertRequestOpened(id: String) {
        // Read on the main thread: a back stack entry's arguments live there.
        compose.waitUntil(5_000) {
            compose.runOnUiThread<Boolean> {
                val entry = nav.currentBackStackEntry
                entry?.destination?.hasRoute(RequestDetail::class) == true && entry.toRoute<RequestDetail>().id == id
            }
        }
        assertNull(runBlocking { pending.pending.first() })
        Espresso.pressBack()
        compose.waitForIdle()
        assertOnScreen(R.string.nav_home)
    }

    @Test
    fun signedIn_opensAtOnce() {
        keep(first)
        launch(GateZone.MAIN, ready)
        assertRequestOpened(first)
    }

    @Test
    fun signedOut_signInShowsTheLineThenOpens() {
        keep(first)
        launch(GateZone.SIGNED_OUT, SessionState.SignedOut)
        assertOnScreen(R.string.pending_request_line)
        // Google or email: either way the session becomes Ready.
        setSession(ready)
        assertRequestOpened(first)
    }

    @Test
    fun newUser_welcomeSignUpEmailDonorSetupAndAlerts_thenOpens() {
        keep(first)
        launch(GateZone.ONBOARDING, SessionState.SignedOut)
        assertOnScreen(R.string.onboarding_find_title)
        // Skip, as in a hurry: the medical note still comes before Sign in.
        clickButton(R.string.action_skip)
        assertOnScreen(R.string.onboarding_medical_title)
        clickButton(R.string.action_i_understand)
        assertOnScreen(R.string.pending_request_line)
        clickButton(R.string.action_create_account)
        // Signed up, email not confirmed yet: still kept.
        setSession(SessionState.Unverified(TestData.user(verified = false)))
        assertOnScreen(R.string.title_verify_email)
        setSession(SessionState.NeedsProfile(TestData.user()))
        assertOnScreen(R.string.setup_choice_title)
        assertOnScreen(R.string.pending_request_line)
        choose(R.string.setup_choice_donate)
        clickButton(R.string.action_continue)
        assertOnScreen(R.string.setup_step, 1, 4)
        setSession(ready) // About you saved the account
        assertOnScreen(R.string.setup_step, 2, 4)
        assertNotOnScreen(R.string.nav_home)
        clickButton(R.string.setup_skip_donor)
        assertRequestOpened(first)
    }

    @Test
    fun askOnlySetup_thenOpens() {
        keep(first)
        launch(GateZone.PROFILE_SETUP, SessionState.NeedsProfile(TestData.user()))
        assertOnScreen(R.string.pending_request_line)
        choose(R.string.setup_choice_need)
        clickButton(R.string.action_continue)
        assertOnScreen(R.string.setup_about_title)
        setSession(ready)
        assertRequestOpened(first)
    }

    @Test
    fun theAlertsQuestionKeepsIt_thenOpens() {
        keep(first)
        launch(GateZone.NOTIFICATION_PERMISSION, ready)
        assertOnScreen(R.string.notifications_rationale_title)
        assertEquals(first, runBlocking { pending.pending.first() })
        clickButton(R.string.action_not_now)
        assertRequestOpened(first)
    }

    @Test
    fun theNewestLinkWins() {
        keep(first)
        launch(GateZone.SIGNED_OUT, SessionState.SignedOut)
        keep(second)
        setSession(ready)
        assertRequestOpened(second)
    }

    @Test
    fun aLinkTappedWhileInTheAppOpensItThen() {
        launch(GateZone.MAIN, ready)
        assertOnScreen(R.string.nav_home)
        keep(second) // as onNewIntent does
        assertRequestOpened(second)
    }

    @Test
    fun noLinkNoLine() {
        launch(GateZone.SIGNED_OUT, SessionState.SignedOut)
        assertOnScreen(R.string.title_sign_in)
        assertNotOnScreen(R.string.pending_request_line)
    }
}
