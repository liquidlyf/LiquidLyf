package io.github.liquidlyf.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.MainActivity
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * D21 in the real app, on the Firebase emulators: a new account (limit 1) with one open request
 * taps "+ Request blood" and gets the limit message. Its "See my requests" opens Requests on My
 * requests, even though Requests was last left on Nearby (the maintainer's hand test).
 * Needs the emulators and adb reverse for 8085 and 9099; skipped otherwise.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RequestLimitTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var session: SessionManager
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository

    private var scenario: ActivityScenario<MainActivity>? = null
    private val dhakaSouth = Area("BD30", "BD3026", "BD30262000")
    private val hospital = "Limit Test Hospital"

    @Before
    fun setUp() = runBlocking {
        assumeTrue(BuildConfig.USE_FIREBASE_EMULATOR && EmulatorAccounts.emulatorsUp())
        hilt.inject()
        val email = EmulatorAccounts.newVerifiedUser(auth, "limit")
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft()).ok()
        val me = profiles.profile(uid, refresh = true).ok()!!
        requests.create(me, requestDraft()).ok()
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun str(@StringRes id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    /** Waits for [text]; on timeout the error lists what the screen shows. */
    private fun waitFor(text: String) {
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val screen = compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString(" / ") { it.texts().joinToString(" | ") }
            throw AssertionError("\"$text\" not shown. Screen: $screen", e)
        }
        compose.waitForIdle()
    }

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.texts() }

    private fun tap(text: String) {
        waitFor(text)
        compose.onNode(hasText(text) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theLimitMessageOpensMyRequestsNotTheTabUsedLast() {
        // Requests, left on Nearby: the tab keeps that screen while Home is open.
        tap(str(R.string.nav_requests))
        compose.onNode(hasText(str(R.string.requests_tab_nearby)) and isSelectable()).assertIsSelected()
        tap(str(R.string.nav_home))

        // The extended button carries its label as its description (LLExtendedFab).
        val requestBlood = hasContentDescription(str(R.string.action_request_blood)) and hasClickAction()
        compose.waitUntil(15_000) { compose.onAllNodes(requestBlood).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(requestBlood).performClick()
        waitFor(str(R.string.create_limit_title))
        tap(str(R.string.create_limit_action))

        waitFor(hospital)
        compose.onNode(hasText(str(R.string.nav_requests)) and isSelectable()).assertIsSelected()
        compose.onNode(hasText(str(R.string.requests_tab_mine)) and isSelectable()).assertIsSelected()
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun profileDraft() = ProfileDraft(
        fullName = "Limit Tester", phone = "+8801812345678", bloodGroup = null, gender = null, birthYear = null,
        weightOk = null, area = dhakaSouth, available = false, lastDonationAt = null, showInDonorSearch = false,
        locale = AppLocale.EN, termsVersion = "draft-2026-11",
    )

    private fun requestDraft() = RequestDraft(
        bloodGroup = BloodGroup.O_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = hospital, hospitalAddress = null,
        division = dhakaSouth.division, district = dhakaSouth.district, upazila = dhakaSouth.upazila, location = null,
        urgency = Urgency.URGENT, neededBy = Instant.now().plus(6, ChronoUnit.HOURS), notes = null,
        contactName = "Limit Tester", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )
}
