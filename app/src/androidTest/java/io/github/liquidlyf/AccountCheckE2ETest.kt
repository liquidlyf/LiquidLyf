package io.github.liquidlyf

import android.view.View
import androidx.annotation.StringRes
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.core.data.auth.AccountChecker
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.auth.SignInNotice
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.firebase.dto.userRef
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * D32 on the phone and the Firebase emulators (A49's tests on the live project): after a change of
 * email the app shows the new one as soon as it comes back, and the profile's copy follows; when
 * Firebase ends the session, Sign in opens with the new address filled in and says why. Ended for
 * another reason, Sign in says only "You were signed out". The Auth emulator doesn't end sessions
 * after an email change as Firebase does, so the test ends them itself (validSince, as the Admin
 * SDK's revokeRefreshTokens).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccountCheckE2ETest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var session: SessionManager
    @Inject lateinit var checker: AccountChecker
    @Inject lateinit var db: FirebaseFirestore

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private val run = System.nanoTime() % 1_000_000

    @Before
    fun setUp() {
        assumeTrue(EmulatorAccounts.emulatorsUp())
        hilt.inject()
    }

    @After
    fun tearDown() {
        scenario?.close()
        if (::auth.isInitialized) auth.signOut()
    }

    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun waitFor(text: String, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** Signed in with a profile, the app open on Home. */
    private suspend fun signedInApp(old: String): String {
        auth.signOut()
        val uid = auth.createUserWithEmailAndPassword(old, EmulatorAccounts.PASSWORD).await().user!!.uid
        EmulatorAccounts.verifyEmail(uid)
        EmulatorAccounts.signIn(auth, old)
        authRepository.reloadUser()
        val draft = ProfileDraft(
            fullName = "Check Person", phone = "+8801712345678", bloodGroup = null, gender = null, birthYear = null, weightOk = null,
            area = Area("BD30", "BD3026", "BD30262000"), available = false, lastDonationAt = null, showInDonorSearch = false,
            locale = AppLocale.EN, termsVersion = "draft-2026-11",
        )
        (profiles.create(uid, old, draft) as AppResult.Success)
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }
        scenario = ActivityScenario.launch(MainActivity::class.java).also { s ->
            s.onActivity { it.window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        }
        waitFor(str(R.string.nav_home), 20_000)
        return uid
    }

    /** "Change email" in the app, then the link opened (as in the email). */
    private suspend fun changeEmailTo(new: String) {
        assertEquals(AppResult.Success(Unit), authRepository.changeEmail(new))
        val code = oobCodes().first { it.optString("requestType") == "VERIFY_AND_CHANGE_EMAIL" && it.optString("newEmail") == new }
        auth.applyActionCode(code.getString("oobCode")).await()
    }

    /** Away from the app and back: onStart runs. */
    private fun leaveAndComeBack() {
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
    }

    /** As the Admin SDK's revokeRefreshTokens: sessions from before now end. */
    private suspend fun endSessions(uid: String) = withContext(Dispatchers.IO) {
        delay(1_100) // validSince is in whole seconds, after the tokens already given
        val url = URL("http://${BuildConfig.EMULATOR_HOST}:9099/identitytoolkit.googleapis.com/v1/projects/demo-liquidlyf/accounts:update")
        val http = url.openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer owner")
            http.setRequestProperty("Content-Type", "application/json")
            val since = System.currentTimeMillis() / 1000
            http.outputStream.use { it.write("""{"localId":"$uid","validSince":"$since"}""".toByteArray()) }
            check(http.responseCode == 200) { "Auth emulator refused: ${http.responseCode}" }
        } finally {
            http.disconnect()
        }
    }

    private suspend fun oobCodes(): List<JSONObject> = withContext(Dispatchers.IO) {
        val http = URL("http://${BuildConfig.EMULATOR_HOST}:9099/emulator/v1/projects/demo-liquidlyf/oobCodes").openConnection() as HttpURLConnection
        val body = try { http.inputStream.bufferedReader().readText() } finally { http.disconnect() }
        val codes = JSONObject(body).optJSONArray("oobCodes") ?: return@withContext emptyList()
        (0 until codes.length()).map { codes.getJSONObject(it) }
    }

    @Test
    fun aChangedEmailShowsOnReturn_andTheCopyFollows() = runBlocking<Unit> {
        val old = "check-old$run@example.test"
        val new = "check-new$run@example.test"
        val uid = signedInApp(old)
        changeEmailTo(new)
        leaveAndComeBack()
        val ready = withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.profile.email == new } } as SessionState.Ready
        assertEquals(new, ready.user.email)
        assertEquals(new, db.userRef(uid).get(Source.SERVER).await().getString("email"))
    }

    /** D34: Edit profile shows the new address waiting, then the change and "Your email is now …". */
    @Test
    fun editProfileShowsTheWaitingEmail_thenTheChange() = runBlocking<Unit> {
        val old = "check-old4$run@example.test"
        val new = "check-new4$run@example.test"
        signedInApp(old)
        compose.onNode(hasText(str(R.string.nav_profile)) and hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(str(R.string.title_edit_profile)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasContentDescription(str(R.string.title_edit_profile)) and hasClickAction())[0].performClick()
        waitFor(str(R.string.label_email_readonly, old))
        // "Change email" on the page, as a person does.
        compose.onNode(hasText(str(R.string.action_change_email)) and hasClickAction()).performClick()
        compose.onNode(hasText(str(R.string.label_new_email)) and hasSetTextAction()).performTextInput(new)
        compose.onNode(hasText(str(R.string.action_send_link)) and hasClickAction()).performClick()
        waitFor(str(R.string.email_pending_title, new))
        waitFor(str(R.string.email_pending_body, old))
        waitFor(str(R.string.action_resend_in, 5).substringBefore("5")) // "Resend in N s", counting down
        // The link opened, then back to the app.
        val code = oobCodes().first { it.optString("requestType") == "VERIFY_AND_CHANGE_EMAIL" && it.optString("newEmail") == new }
        auth.applyActionCode(code.getString("oobCode")).await()
        leaveAndComeBack()
        waitFor(str(R.string.email_changed_done, new), 20_000)
        waitFor(str(R.string.label_email_readonly, new))
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(str(R.string.email_pending_title, new)).fetchSemanticsNodes().size)
        assertEquals(null, authRepository.requestedEmail.value)
    }

    @Test
    fun anEndedSessionAfterAChange_signInWithTheNewEmailFilledIn() = runBlocking<Unit> {
        val old = "check-old2$run@example.test"
        val new = "check-new2$run@example.test"
        val uid = signedInApp(old)
        changeEmailTo(new)
        endSessions(uid)
        leaveAndComeBack()
        waitFor(str(R.string.signed_out_email_changed, new), 20_000)
        // The address is filled in.
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText(new)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(SignInNotice.EmailChanged(new), checker.signInNotice.value)
    }

    @Test
    fun endedForAnotherReason_onlySignedOut() = runBlocking<Unit> {
        val old = "check-old3$run@example.test"
        val uid = signedInApp(old)
        endSessions(uid)
        checker.check() // the 10-minute limit would wait: check now
        waitFor(str(R.string.signed_out_session_ended), 20_000)
        assertEquals(0, compose.onAllNodesWithText(str(R.string.signed_out_email_changed, old), substring = true).fetchSemanticsNodes().size)
    }
}
