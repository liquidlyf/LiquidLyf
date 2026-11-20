package io.github.liquidlyf

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.link.PendingRequestStore
import io.github.liquidlyf.core.data.link.RequestLinks
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.testing.EmulatorAccounts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * D31 end to end, on the phone and the Firebase emulators: the real app is opened by a request's
 * real links (the share's https://liquidlyf.github.io/r/#id, and the web page button's
 * liquidlyf://request/id) and lands on that real request, signed in or after signing in (email or
 * Google). Closed and missing requests show today's pages, with no Share. The way through sign-up,
 * the email and setup is PendingRequestNavTest's; the 3-day limit and the link's forms are unit tests.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RequestLinkE2ETest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var requests: RequestRepository
    @Inject lateinit var pending: PendingRequestStore

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dhakaSouth = Area("BD30", "BD3026", "BD30262000")
    private val hospital = "Dhaka Medical College Hospital"
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        assumeTrue(EmulatorAccounts.emulatorsUp())
        hilt.inject()
        runBlocking { pending.take() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        if (::pending.isInitialized) runBlocking { pending.take() }
        if (::auth.isInitialized) auth.signOut()
    }

    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun waitFor(text: String, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun open(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
        scenario = ActivityScenario.launch<MainActivity>(intent).also { s ->
            s.onActivity { it.window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        }
    }

    /** A second link tapped while the app is open (singleTop: onNewIntent). */
    private fun tapAgain(link: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** First run on this install: the welcome pages, as a new person sees them. */
    private fun pastWelcome() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(str(R.string.action_skip)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(str(R.string.title_sign_in)).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(str(R.string.action_skip)).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasText(str(R.string.action_skip)) and hasClickAction()).performClick()
            waitFor(str(R.string.action_i_understand))
            compose.onNode(hasText(str(R.string.action_i_understand)) and hasClickAction()).performClick()
        }
    }

    private fun signInOnScreen(email: String) {
        compose.onNode(hasText(str(R.string.label_email)) and hasSetTextAction()).performTextInput(email)
        compose.onNode(hasText(str(R.string.label_password)) and hasSetTextAction()).performTextInput(EmulatorAccounts.PASSWORD)
        Espresso.closeSoftKeyboard()
        compose.onNode(hasText(str(R.string.action_sign_in)) and hasClickAction()).performClick()
    }

    private fun assertOnTheRequest() {
        waitFor(hospital, 20_000)
        // Back goes Home, not out of the app.
        Espresso.pressBack()
        waitFor(str(R.string.nav_home))
    }

    /** Someone else's open request; returns its ID, signed out again. */
    private suspend fun someonesRequest(tag: String): String {
        val email = EmulatorAccounts.newVerifiedUser(auth, tag)
        lastRequesterEmail = email
        val uid = auth.uid!!
        profiles.create(uid, email, profileDraft()).ok()
        val me = profiles.profile(uid, refresh = true).ok()!!
        val id = requests.create(me, requestDraft()).ok()
        auth.signOut()
        return id
    }

    /** A person with an account and a profile, signed out; returns the email. */
    private suspend fun someoneSignedOut(tag: String): String {
        val email = EmulatorAccounts.newVerifiedUser(auth, tag)
        profiles.create(auth.uid!!, email, profileDraft()).ok()
        auth.signOut()
        return email
    }

    @Test
    fun signedIn_theShareLinkOpensTheRequest() = runBlocking<Unit> {
        val id = someonesRequest("link-req1")
        val email = someoneSignedOut("link-in")
        EmulatorAccounts.signIn(auth, email)
        open(RequestLinks.of(id))
        assertOnTheRequest()
    }

    @Test
    fun signedOut_signInThenTheRequest() = runBlocking<Unit> {
        val id = someonesRequest("link-req2")
        val email = someoneSignedOut("link-out")
        open(RequestLinks.of(id))
        pastWelcome()
        waitFor(str(R.string.pending_request_line))
        signInOnScreen(email)
        assertOnTheRequest()
    }

    @Test
    fun thePageButtonsLink_signedOut() = runBlocking<Unit> {
        val id = someonesRequest("link-req3")
        val email = someoneSignedOut("link-btn")
        open("liquidlyf://request/$id")
        pastWelcome()
        waitFor(str(R.string.pending_request_line))
        signInOnScreen(email)
        assertOnTheRequest()
    }

    @Test
    fun googleSignIn_thenTheRequest() = runBlocking<Unit> {
        val id = someonesRequest("link-req4")
        // A Google account with a profile (the emulator's own Google token).
        val gmail = "link-g-${System.nanoTime()}@gmail.com"
        val token = """{"sub":"g-${System.nanoTime()}","email":"$gmail","email_verified":true}"""
        auth.signOut()
        val uid = auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user!!.uid
        profiles.create(uid, gmail, profileDraft()).ok()
        auth.signOut()
        open(RequestLinks.of(id))
        pastWelcome()
        waitFor(str(R.string.pending_request_line))
        authRepository.signInWithGoogle(token).ok() // as "Continue with Google" does
        assertOnTheRequest()
    }

    @Test
    fun twoLinks_theNewestWins() = runBlocking<Unit> {
        val first = someonesRequest("link-req5")
        val second = someonesRequest("link-req6")
        val email = someoneSignedOut("link-two")
        open(RequestLinks.of(first))
        pastWelcome()
        waitFor(str(R.string.pending_request_line))
        tapAgain(RequestLinks.of(second))
        compose.waitUntil(5_000) { runBlocking { pending.pending.first() } == second }
        signInOnScreen(email)
        compose.waitUntil(20_000) { compose.onAllNodesWithText(hospital, substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The request page reached is the second one: the store held it, and it's used.
        assertEquals(null, runBlocking { pending.pending.first() })
    }

    @Test
    fun aClosedRequest_showsClosed_noShare() = runBlocking<Unit> {
        val id = someonesRequest("link-req7")
        closeAsRequester(id) // its requester closes it
        val email = someoneSignedOut("link-closed")
        EmulatorAccounts.signIn(auth, email)
        open(RequestLinks.of(id))
        waitFor(str(R.string.request_status_cancelled), 20_000)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasContentDescription(str(R.string.action_share))).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(str(R.string.action_share)).fetchSemanticsNodes().size)
    }

    @Test
    fun aMissingRequest_showsNotAvailable() = runBlocking<Unit> {
        val email = someoneSignedOut("link-missing")
        EmulatorAccounts.signIn(auth, email)
        open(RequestLinks.of("NoSuchRequestXyz1234"))
        waitFor(str(R.string.detail_missing_title), 20_000)
    }

    private var lastRequesterEmail: String? = null

    private suspend fun closeAsRequester(id: String) {
        EmulatorAccounts.signIn(auth, lastRequesterEmail!!)
        val request = (requests.byIds(listOf(id)) as AppResult.Success).data.single()
        requests.close(request, RequestStatus.CANCELLED, null).ok()
        auth.signOut()
    }

    private fun <T> AppResult<T>.ok(): T = when (this) {
        is AppResult.Success -> data
        is AppResult.Failure -> throw AssertionError("Expected success, got $error")
    }

    private fun profileDraft() = ProfileDraft(
        fullName = "Link Person", phone = "+8801712345678", bloodGroup = null, gender = null, birthYear = null, weightOk = null,
        area = dhakaSouth, available = false, lastDonationAt = null, showInDonorSearch = false,
        locale = AppLocale.EN, termsVersion = "draft-2026-11",
    )

    private fun requestDraft() = RequestDraft(
        bloodGroup = BloodGroup.O_POS, component = BloodComponent.WHOLE_BLOOD, acceptCompatible = false, unitsNeeded = 1,
        condition = null, hospitalId = null, hospitalName = hospital, hospitalAddress = null,
        division = dhakaSouth.division, district = dhakaSouth.district, upazila = dhakaSouth.upazila, location = null,
        urgency = Urgency.URGENT, neededBy = Instant.now().plus(Duration.ofHours(6)), notes = null,
        contactName = "Test Requester", contactPhone = "+8801812345678", patientName = null, wardOrBed = null,
    )
}
