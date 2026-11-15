package io.github.liquidlyf.navigation

import android.app.Activity
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * A16 (15 Nov 2026, prod): after opening a request from its alert, the launcher icon stacked a
 * second MainActivity, and signing out then crashed the app: the hidden request screen's listener
 * was refused (PERMISSION_DENIED) and nothing caught it. Here the same path runs on the emulators.
 * A crash ends the whole test run, so reaching the end is the check.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SignOutAfterNotificationTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var session: SessionManager
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dhakaSouth = Area("BD30", "BD3026", "BD30262000")

    @Before
    fun setUp() {
        assumeTrue("Firebase emulators not reachable", EmulatorAccounts.emulatorsUp())
        hilt.inject()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { liveMainActivities().forEach(Activity::finish) }
        auth.signOut()
    }

    @Test
    fun aLauncherTapAfterAnAlertReusesTheScreenAndSigningOutThereDoesNotCrash() = runBlocking {
        // A requester posts; a matching donor gets the alert.
        val requesterEmail = EmulatorAccounts.newVerifiedUser(auth, "so-r")
        val requesterUid = auth.uid!!
        profiles.create(requesterUid, requesterEmail, profileDraft(BloodGroup.A_POS, listed = false, phone = "+8801812345678")).ok()
        ready(requesterUid)
        val requester = profiles.profile(requesterUid, refresh = true).ok()!!
        val requestId = requests.create(requester, requestDraft()).ok()
        val donorEmail = EmulatorAccounts.newVerifiedUser(auth, "so-d")
        val donorUid = auth.uid!!
        profiles.create(donorUid, donorEmail, profileDraft(BloodGroup.O_POS, listed = true, phone = "+8801712345678")).ok()
        ready(donorUid)

        // The alert's tap: the same intent AlertNotifier builds.
        context.startActivity(
            Intent(Intent.ACTION_VIEW, "liquidlyf://request/$requestId".toUri(), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        waitFor(str(R.string.detail_i_can_donate))
        assertEquals(1, countMainActivities())

        // Later the donor opens the app from its icon: the request is still on screen, in the same activity.
        context.startActivity(
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
        Thread.sleep(1_500)
        assertEquals("a launcher tap must not stack a second MainActivity", 1, countMainActivities())
        waitFor(str(R.string.detail_i_can_donate))

        // Signing out with the request open: from now on its listener is refused.
        session.signOut()
        waitFor(str(R.string.action_create_account)) // the sign-in screen (the demo flavor has no Google button)
        Thread.sleep(3_000) // time for the refused listener to arrive; uncaught, it ended the process here
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals(1, countMainActivities())
    }

    private fun liveMainActivities(): List<Activity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.PRE_ON_CREATE, Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED)
            .flatMap { monitor.getActivitiesInStage(it) }
            .filter { it is MainActivity && !it.isFinishing }
            .distinct()
    }

    private fun countMainActivities(): Int {
        var count = 0
        instrumentation.runOnMainSync { count = liveMainActivities().size }
        return count
    }

    private suspend fun ready(uid: String) {
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }
    }

    private fun str(@StringRes id: Int): String = context.getString(id)

    /** Waits for [text]; on timeout the error lists what the screen shows. */
    private fun waitFor(text: String, timeoutMs: Long = 15_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val screen = compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString(" / ") { root ->
                root.textsBelow().joinToString(" | ")
            }
            throw AssertionError("\"$text\" not shown. Screen: $screen", e)
        }
    }

    private fun SemanticsNode.textsBelow(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.textsBelow() }

    private fun profileDraft(group: BloodGroup, listed: Boolean, phone: String) = ProfileDraft(
        fullName = "Test Donor", phone = phone, bloodGroup = group, gender = Gender.FEMALE, birthYear = 1995,
        weightOk = true, area = dhakaSouth, available = true, lastDonationAt = null, showInDonorSearch = listed,
        locale = AppLocale.EN, termsVersion = "draft-2026-11",
    )

    private fun requestDraft() = RequestDraft(
        bloodGroup = BloodGroup.O_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = "Dhaka Medical College Hospital", hospitalAddress = null,
        division = dhakaSouth.division, district = dhakaSouth.district, upazila = dhakaSouth.upazila, location = null,
        urgency = Urgency.URGENT, neededBy = Instant.now().plus(6, ChronoUnit.HOURS), notes = null,
        contactName = "Test Requester", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }
}
