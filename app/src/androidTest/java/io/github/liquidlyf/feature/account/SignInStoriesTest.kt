package io.github.liquidlyf.feature.account

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.firebase.dto.userRef
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
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
 * D30 step 1, on the Auth emulator: what happens in the 3 sign-in stories and the 2 rare cases, so
 * P can be told in plain words before Change email is built. The stories record what they see
 * (logcat tag LLStories) rather than asserting it; the last test checks the fix. "Continue with
 * Google" is the emulator's fake Google token. The emulator doesn't sign the phone out after a
 * change of email, nor mail the old address an undo link, as real Firebase does.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SignInStoriesTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var db: FirebaseFirestore
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var session: SessionManager

    private val password = EmulatorAccounts.PASSWORD
    private val run = System.nanoTime() % 1_000_000

    @Before
    fun setUp() {
        assumeTrue(EmulatorAccounts.emulatorsUp())
        hilt.inject()
    }

    private fun log(story: String, what: String) = Log.i(TAG, "$story | $what")

    private fun google(sub: String, email: String) =
        GoogleAuthProvider.getCredential("""{"sub":"$sub","email":"$email","email_verified":true}""", null)

    /** Tries [block]; logs the user it ends with, or the error. */
    private suspend fun attempt(story: String, step: String, block: suspend () -> Unit): String? {
        return try {
            block()
            val u = auth.currentUser
            log(story, "$step: OK uid=${u?.uid} email=${u?.email} verified=${u?.isEmailVerified} providers=${u?.providerData?.map { it.providerId + ":" + it.email }}")
            u?.uid
        } catch (e: Exception) {
            log(story, "$step: FAILED ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun signInPassword(story: String, step: String, email: String) = attempt(story, step) {
        auth.signOut()
        auth.signInWithEmailAndPassword(email, password).await()
    }

    private suspend fun signInGoogle(story: String, step: String, sub: String, email: String) = attempt(story, step) {
        auth.signOut()
        auth.signInWithCredential(google(sub, email)).await()
    }

    /** Asks for the change, then plays the link in the email the emulator "sent" to [newEmail]. */
    private suspend fun changeEmail(story: String, newEmail: String) {
        attempt(story, "verifyBeforeUpdateEmail($newEmail)") { auth.currentUser!!.verifyBeforeUpdateEmail(newEmail).await() }
        val code = oobCodes().firstOrNull { it.optString("requestType") == "VERIFY_AND_CHANGE_EMAIL" && it.optString("newEmail") == newEmail }
        log(story, "change link sent: ${code != null}")
        if (code != null) attempt(story, "open the change link") { auth.applyActionCode(code.getString("oobCode")).await() }
        attempt(story, "reload on this phone") { auth.currentUser?.reload()?.await() }
    }

    private suspend fun oobCodes(): List<JSONObject> = withContext(Dispatchers.IO) {
        val http = URL("http://${BuildConfig.EMULATOR_HOST}:9099/emulator/v1/projects/demo-liquidlyf/oobCodes").openConnection() as HttpURLConnection
        val body = try { http.inputStream.bufferedReader().readText() } finally { http.disconnect() }
        val codes = JSONObject(body).optJSONArray("oobCodes") ?: return@withContext emptyList()
        (0 until codes.length()).map { codes.getJSONObject(it) }
    }

    private suspend fun recoverySentTo(email: String) = oobCodes().any { it.optString("email") == email && it.optString("requestType") == "RECOVER_EMAIL" }

    private suspend fun profileEmail(uid: String): String? = runCatching {
        db.userRef(uid).get(Source.SERVER).await().getString("email")
    }.getOrElse { "read failed: ${it.message}" }

    private suspend fun makeProfile(uid: String, email: String) {
        val draft = ProfileDraft(
            fullName = "Story Person", phone = "+8801712345678", bloodGroup = null, gender = null, birthYear = null, weightOk = null,
            area = Area("BD30", "BD3026", "BD30262000"), available = false, lastDonationAt = null, showInDonorSearch = false,
            locale = AppLocale.EN, termsVersion = "draft-2026-11",
        )
        log("setup", "profile for $uid: ${profiles.create(uid, email, draft)}")
    }

    @Test
    fun story1_gmailWithPasswordThenGoogle() = runBlocking<Unit> {
        for (verified in listOf(true, false)) {
            val s = "1 (" + (if (verified) "verified" else "unverified") + ")"
            val gmail = "story1$run${if (verified) "v" else "u"}@gmail.com"
            auth.signOut()
            val uid = auth.createUserWithEmailAndPassword(gmail, password).await().user!!.uid
            if (verified) EmulatorAccounts.verifyEmail(uid)
            log(s, "made with password: uid=$uid")
            val after = signInGoogle(s, "Continue with Google, same Gmail", "g1$run$verified", gmail)
            log(s, "same account: ${after == uid}")
            signInPassword(s, "password again", gmail)
        }
    }

    @Test
    fun story2_passwordThenChangeEmail() = runBlocking<Unit> {
        val s = "2"
        val old = "story2old$run@example.test"
        val new = "story2new$run@example.test"
        auth.signOut()
        val uid = auth.createUserWithEmailAndPassword(old, password).await().user!!.uid
        EmulatorAccounts.verifyEmail(uid)
        signInPassword(s, "sign in (verified)", old)
        makeProfile(uid, old)
        changeEmail(s, new)
        log(s, "undo email to the old address: ${recoverySentTo(old)}")
        signInPassword(s, "new email + password", new)
        log(s, "users/{uid}.email after the change: ${profileEmail(uid)}")
        signInPassword(s, "old email + password", old)
    }

    @Test
    fun story3_googleAddPasswordThenChangeEmail_andTheRareCases() = runBlocking<Unit> {
        val s = "3"
        val gmail = "story3$run@gmail.com"
        val sub = "g3$run"
        val uid = signInGoogle(s, "Continue with Google (new account)", sub, gmail)!!
        makeProfile(uid, gmail)
        // The app's own Add password (it handles the emulator's "in use" answer for this same account).
        attempt(s, "Add password") { log(s, "addPassword: ${authRepository.addPassword(password)}") }
        // The new address is itself another Google account (rare case a).
        val newGmail = "story3new$run@gmail.com"
        changeEmail(s, newGmail)
        log(s, "users/{uid}.email after the change: ${profileEmail(uid)}")
        val viaOldGoogle = signInGoogle(s, "Continue with Google, the old Gmail", sub, gmail)
        log(s, "old Google = same account: ${viaOldGoogle == uid}")
        val viaNewPassword = signInPassword(s, "new email + password", newGmail)
        log(s, "new email + password = same account: ${viaNewPassword == uid}")
        signInPassword(s, "old Gmail + password", gmail)

        val ra = "rare a"
        val viaNewGoogle = signInGoogle(ra, "Continue with Google with the new address's own Google account", "g3new$run", newGmail)
        log(ra, "same account: ${viaNewGoogle == uid}, a new account: ${viaNewGoogle != null && viaNewGoogle != uid}")

        val rb = "rare b"
        auth.signOut()
        attempt(rb, "Create account with the old Gmail") { auth.createUserWithEmailAndPassword(gmail, password).await() }
        val afterB = signInGoogle(rb, "then Continue with Google, the old Gmail", sub, gmail)
        log(rb, "Google still opens the first account: ${afterB == uid}")
        auth.signOut()
    }

    /** The fix (D30): the app moves the profile's copy to the new address (B's rule allows only that). */
    @Test
    fun theEmailCopyFollowsAChangeOfEmail() = runBlocking<Unit> {
        val old = "sync-old$run@example.test"
        val new = "sync-new$run@example.test"
        auth.signOut()
        val uid = auth.createUserWithEmailAndPassword(old, password).await().user!!.uid
        EmulatorAccounts.verifyEmail(uid)
        auth.signOut()
        auth.signInWithEmailAndPassword(old, password).await()
        makeProfile(uid, old)
        session.refresh()
        withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.user.uid == uid } }

        auth.currentUser!!.verifyBeforeUpdateEmail(new).await()
        val code = oobCodes().first { it.optString("requestType") == "VERIFY_AND_CHANGE_EMAIL" && it.optString("newEmail") == new }
        auth.applyActionCode(code.getString("oobCode")).await()
        // As when the app comes back: the session reads the account again.
        authRepository.reloadUser()
        withTimeout(20_000) { while (profileEmail(uid) != new) delay(300) }
        val ready = withTimeout(20_000) { session.state.first { it is SessionState.Ready && it.profile.email == new } } as SessionState.Ready
        assertEquals(new, ready.user.email)
        auth.signOut()
    }

    private companion object {
        const val TAG = "LLStories"
    }
}
