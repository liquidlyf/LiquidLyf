package io.github.liquidlyf

import android.accessibilityservice.AccessibilityService
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.core.firebase.dto.donorRef
import io.github.liquidlyf.core.firebase.dto.statsRef
import io.github.liquidlyf.core.firebase.dto.userRef
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject

/**
 * Phase 3.14 and D1: the whole way from install to Home in the real app, on the Firebase emulators
 * (demo flavor) with their rules: sign up → verify (the Auth emulator's admin API stands in for the
 * email link) → the short setup → Home with "Want to donate too?" → Become a donor's 3 steps → Home
 * as a listed donor. The users, userStats and donors documents are checked after each part.
 * Needs `npm run emulators` in firebase/ and adb reverse for 8085 and 9099; skipped otherwise.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SignUpToHomeTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var db: FirebaseFirestore

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        assumeTrue(BuildConfig.USE_FIREBASE_EMULATOR && emulatorsUp())
        hilt.inject()
        auth.signOut()
        scenario = ActivityScenario.launch(MainActivity::class.java).also { s ->
            // No "save password?" sheet from Android Autofill during the test: it would stay on
            // screen and take the focus from the tests that run after this one.
            s.onActivity { it.window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        }
    }

    @After
    fun tearDown() {
        // The app's own "save password?" offer may still be showing: a system Back closes it.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(2) {
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(500)
        }
        scenario?.close()
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    /** Waits for [text]; on timeout the error lists what the screen shows, to see why. */
    private fun waitFor(text: String, timeoutMs: Long = 10_000) {
        try {
            compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val screen = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
                .joinToString(" / ") { root -> root.textsBelow().joinToString(" | ") }
            throw AssertionError("\"$text\" not shown. Screen: $screen", e)
        }
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.textsBelow(): List<String> =
        config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.let { listOf("[" + it.text + "]") }.orEmpty() +
            children.flatMap { it.textsBelow() }

    private fun tap(@StringRes label: Int) = tapText(str(label))

    private fun tapText(text: String) {
        waitFor(text)
        val node = compose.onNode(hasText(text) and hasClickAction())
        // Inside the scrolling form, bring it into view first: with the keyboard up it can sit
        // under the bottom bar, and the tap would land on the bar instead.
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private fun type(@StringRes label: Int, value: String) {
        compose.onNode(hasText(str(label)) and hasSetTextAction()).performTextInput(value)
        Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    /**
     * Opens a dropdown field and picks [item] from its menu (the menu item is the last match). The
     * area pickers' labels sit above their boxes (N3), so the box is found by its description.
     */
    private fun pick(@StringRes field: Int, item: String) {
        compose.onNode((hasText(str(field)) or hasContentDescription(str(field), substring = true)) and hasClickAction()).performClick()
        waitFor(item)
        compose.onAllNodesWithText(item).onLast().performClick()
        compose.waitForIdle()
    }

    @Test
    fun signUpVerifySetUpAndLandOnHome() = runBlocking {
        val email = "ui-${System.nanoTime()}@example.test"

        // First run shows onboarding; later runs start at sign-in.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(str(R.string.action_skip)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(str(R.string.action_create_account)).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(str(R.string.action_skip)).fetchSemanticsNodes().isNotEmpty()) {
            // Skip leads to the medical disclaimer (8.3), which "I understand" closes.
            tap(R.string.action_skip)
            waitFor(str(R.string.onboarding_medical_title))
            tap(R.string.action_i_understand)
        }

        // Sign up
        tap(R.string.action_create_account)
        waitFor(str(R.string.label_full_name))
        type(R.string.label_full_name, "Test Donor")
        type(R.string.label_email, email)
        type(R.string.label_password, "long-enough-1")
        compose.onNode(hasText(str(R.string.sign_up_consent)) and hasClickAction()).performClick()
        tap(R.string.action_create_account)

        // Verify: the emulator's admin API plays the email link.
        waitFor(str(R.string.action_i_have_verified))
        // The verification email really went out, although the gate replaced the sign-up screen
        // at once (A15: the first email was never sent).
        assertTrue("No verification email was sent to $email", verificationEmailSent(email))
        verifyEmail(auth.currentUser!!.uid)
        tap(R.string.action_i_have_verified)

        // The short setup (D1): the name from sign-up, the number and the area, nothing else.
        waitFor(str(R.string.setup_donor_later))
        type(R.string.label_phone, "01712345678")
        pick(R.string.label_division, "Dhaka")
        pick(R.string.label_district, "Dhaka")
        pick(R.string.label_upazila, "Dhaka South City Corporation")
        tap(R.string.action_continue)

        // Home as a requester (Android 11 has no notification permission screen)
        waitFor(str(R.string.home_become_donor_title), timeoutMs = 15_000)
        val uid = auth.currentUser!!.uid
        val requester = db.userRef(uid).get(Source.SERVER).await()
        assertEquals("+8801712345678", requester.getString("phone"))
        listOf("bloodGroup", "gender", "birthYear", "weightOk", "lastDonationAt", "consentAt").forEach {
            assertTrue("A requester's profile has no $it", requester.get(it) == null)
        }
        assertEquals(false, requester.getBoolean("showInDonorSearch"))
        assertEquals(false, requester.getBoolean("available"))
        assertEquals(0L, db.statsRef(uid).get(Source.SERVER).await().getLong("openRequests"))
        assertTrue("No donor card before Become a donor", !db.donorRef(uid).get(Source.SERVER).await().exists())

        // Become a donor: one profile edit with the details, the listing and the card.
        tap(R.string.action_become_donor)
        waitFor(str(R.string.setup_blood_title))
        tapText("B+")
        tap(R.string.action_next)
        waitFor(str(R.string.become_donor_details_title))
        tap(R.string.gender_male)
        type(R.string.label_birth_year, "1995")
        tap(R.string.answer_yes)
        tap(R.string.action_next)
        waitFor(str(R.string.become_donor_history_title))
        tap(R.string.last_donation_never)
        tap(R.string.consent_yes)
        tap(R.string.action_finish)

        // Home as a listed donor
        waitFor(str(R.string.home_urgent_near_you), timeoutMs = 15_000)
        val user = db.userRef(uid).get(Source.SERVER).await()
        assertEquals("MALE", user.getString("gender"))
        assertEquals(1995L, user.getLong("birthYear"))
        assertEquals(true, user.getBoolean("weightOk"))
        assertEquals(true, user.getBoolean("available"))
        assertEquals("B_POS", user.getString("bloodGroup"))
        assertEquals("+8801712345678", user.getString("phone"))
        assertEquals("BD30262000", user.getString("upazila"))
        assertEquals(true, user.getBoolean("showInDonorSearch"))
        assertTrue(user.getTimestamp("consentAt") != null)
        assertEquals(0L, db.statsRef(uid).get(Source.SERVER).await().getLong("openRequests"))
        val card = db.donorRef(uid).get(Source.SERVER).await()
        assertEquals("Test D.", card.getString("displayName"))
        assertEquals(0L, card.getTimestamp("eligibleFrom")!!.seconds)
        assertTrue("The card must hold no phone", !card.contains("phone"))
    }

    /** The Auth emulator lists every email it "sent"; waits up to 10 s for this one. */
    private suspend fun verificationEmailSent(email: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = URL("http://${BuildConfig.EMULATOR_HOST}:9099/emulator/v1/projects/demo-liquidlyf/oobCodes")
        repeat(20) {
            val http = url.openConnection() as HttpURLConnection
            val body = try { http.inputStream.bufferedReader().readText() } finally { http.disconnect() }
            val codes = org.json.JSONObject(body).optJSONArray("oobCodes")
            val sent = codes != null && (0 until codes.length()).any { i ->
                val code = codes.getJSONObject(i)
                code.optString("email") == email && code.optString("requestType") == "VERIFY_EMAIL"
            }
            if (sent) return@withContext true
            kotlinx.coroutines.delay(500)
        }
        false
    }

    private suspend fun verifyEmail(uid: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val url = URL("http://${BuildConfig.EMULATOR_HOST}:9099/identitytoolkit.googleapis.com/v1/projects/demo-liquidlyf/accounts:update")
        val http = url.openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer owner")
            http.setRequestProperty("Content-Type", "application/json")
            http.outputStream.use { it.write("""{"localId":"$uid","emailVerified":true}""".toByteArray()) }
            check(http.responseCode == 200) { "Auth emulator refused: ${http.responseCode}" }
        } finally {
            http.disconnect()
        }
    }

    private fun emulatorsUp(): Boolean = listOf(8085, 9099).all { port ->
        try {
            Socket().use { it.connect(InetSocketAddress(BuildConfig.EMULATOR_HOST, port), 1_000) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
