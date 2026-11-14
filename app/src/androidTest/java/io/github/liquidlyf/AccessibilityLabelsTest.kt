package io.github.liquidlyf

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.auth.ForgotPasswordScreen
import io.github.liquidlyf.feature.auth.ForgotPasswordUiState
import io.github.liquidlyf.feature.auth.SignInScreen
import io.github.liquidlyf.feature.auth.SignInUiState
import io.github.liquidlyf.feature.auth.SignUpScreen
import io.github.liquidlyf.feature.auth.SignUpUiState
import io.github.liquidlyf.feature.auth.VerifyEmailScreen
import io.github.liquidlyf.feature.auth.VerifyEmailUiState
import io.github.liquidlyf.feature.home.HomeScreen
import io.github.liquidlyf.feature.home.HomeUiState
import io.github.liquidlyf.feature.onboarding.OnboardingScreen
import io.github.liquidlyf.feature.profilesetup.ProfileSetupScreen
import io.github.liquidlyf.feature.profilesetup.ProfileSetupUiState
import io.github.liquidlyf.feature.profilesetup.SetupDraft
import io.github.liquidlyf.feature.request.create.CreateRequestScreen
import io.github.liquidlyf.feature.request.create.CreateRequestUiState
import io.github.liquidlyf.feature.request.create.CreateRequestViewModel
import io.github.liquidlyf.feature.request.create.OpenRequestLimit
import io.github.liquidlyf.feature.request.create.RequestForm
import io.github.liquidlyf.feature.request.detail.RequestDetailRoute
import io.github.liquidlyf.feature.request.detail.RequestDetailViewModel
import io.github.liquidlyf.feature.request.list.OfferItem
import io.github.liquidlyf.feature.request.list.PagedList
import io.github.liquidlyf.feature.request.list.RequestsScreen
import io.github.liquidlyf.feature.request.list.RequestsTab
import io.github.liquidlyf.feature.request.list.RequestsUiState
import io.github.liquidlyf.testing.FakeNetworkMonitor
import io.github.liquidlyf.testing.FakeOfferRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSafetyRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeWorkerClient
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.request
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 acceptance "TalkBack can complete sign-in", and the same for the Phase 4 request loop:
 * every control on these screens has a label TalkBack can read (text or a content description),
 * every text field has a label, and every control is at least 48 dp in both directions. Runs the
 * real screens on the phone.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityLabelsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides SnackbarHostState()) {
                LiquidLyfTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun SemanticsNode.label(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.takeIf { it.isNotBlank() }
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.takeIf { it.isNotBlank() }

    /** Fails with a list of every unlabelled or too-small control on the screen. */
    private fun assertControlsAreLabelledAndBigEnough(screen: String) {
        val problems = mutableListOf<String>()
        val minPx = with(compose.density) { 48.dp.toPx() } - 1f // rounding
        val controls = compose.onAllNodes(hasClickAction() or hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("$screen: found no controls at all, so the check proves nothing", controls.size >= 2)
        for (node in controls) {
            val label = node.label()
            if (label == null) problems += "no label: node ${node.id} ${node.config}"
            // Touch bounds are clipped to the screen, so a control scrolled out of view measures 0;
            // its own layout size still counts there.
            val touch = node.touchBoundsInRoot
            val width = maxOf(touch.width, node.size.width.toFloat())
            val height = maxOf(touch.height, node.size.height.toFloat())
            if (width < minPx || height < minPx) {
                problems += "smaller than 48 dp (${width.toInt()}x${height.toInt()} px): \"$label\""
            }
        }
        assertTrue("$screen:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun onboarding() {
        show { OnboardingScreen(onFinished = {}) }
        assertControlsAreLabelledAndBigEnough("Onboarding")
    }

    @Test
    fun signInIncludingGoogle() {
        show { SignInScreen(SignInUiState(), {}, {}, {}, {}, {}, onContinueWithGoogle = {}) }
        assertControlsAreLabelledAndBigEnough("Sign in")
    }

    @Test
    fun signUp() {
        show { SignUpScreen(SignUpUiState(), {}, {}, {}, {}, {}, {}) }
        assertControlsAreLabelledAndBigEnough("Sign up")
    }

    @Test
    fun verifyEmail() {
        show { VerifyEmailScreen(VerifyEmailUiState(email = "rahim@example.test", resendIn = 0), {}, {}, {}) }
        assertControlsAreLabelledAndBigEnough("Verify email")
    }

    @Test
    fun forgotPassword() {
        show { ForgotPasswordScreen(ForgotPasswordUiState(), {}, {}, {}) }
        assertControlsAreLabelledAndBigEnough("Forgot password")
    }

    @Test
    fun everyProfileSetupStep() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val geo = context.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) }
        val draft = SetupDraft(uid = "u1", division = "BD30", district = "BD3026", upazila = "BD30262000", bloodGroup = "B_POS")
        val step = mutableIntStateOf(0)
        show {
            val state = ProfileSetupUiState(draft = draft.copy(step = step.intValue), geo = geo, loaded = true, needsTerms = true)
            ProfileSetupScreen(state, AppLocale.EN, {}, {}, {}, {}, {})
        }
        for (s in 0..3) {
            compose.runOnUiThread { step.intValue = s }
            compose.waitForIdle()
            assertControlsAreLabelledAndBigEnough("Profile setup step ${s + 1}")
        }
    }

    // ---- Phase 4: the request loop ----

    private val geo by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) }
    }

    @Test
    fun home() {
        val me = TestData.profile()
        val eligibility = EligibilityCalculator().evaluate(me.birthYear, me.weightOk, me.gender, me.lastDonationAt, TestData.NOW)
        val feed = listOf(TestData.request(id = "r1"), TestData.request(id = "r2").copy(condition = PatientCondition.SURGERY))
        show { HomeScreen(HomeUiState(me, eligibility, available = true, requests = feed, geo = geo), online = false, {}, {}, {}, {}, {}) }
        assertControlsAreLabelledAndBigEnough("Home")
    }

    @Test
    fun everyRequestsTab() {
        val r = TestData.request(id = "r1")
        val offer = DonorResponse("uid-1", "r1", "requester-1", ResponseStatus.OFFERED, "Rahim U.", BloodGroup.B_POS, null, null, TestData.NOW, TestData.NOW)
        val tab = mutableStateOf(RequestsTab.NEARBY)
        show {
            val state = RequestsUiState(
                tab = tab.value,
                nearby = PagedList(listOf(r, TestData.request(id = "r2")), loaded = true),
                mine = PagedList(loaded = true), // empty: the "Request blood" action
                offers = PagedList(listOf(OfferItem(offer, r)), loaded = true),
                geo = geo,
            )
            RequestsScreen(state, online = true, {}, {}, {}, {}, {})
        }
        for (t in RequestsTab.entries) {
            compose.runOnUiThread { tab.value = t }
            compose.waitForIdle()
            assertControlsAreLabelledAndBigEnough("Requests, ${t.name}")
        }
    }

    @Test
    fun everyCreateRequestStepAndTheLimit() {
        val form = RequestForm(
            bloodGroup = "B_POS", division = "BD30", district = "BD3026", upazila = "BD30262000",
            hospitalName = "Dhaka Medical College Hospital", urgency = "PLANNED", contactName = "Rahim", contactPhone = "01712345678",
        )
        val state = mutableStateOf(CreateRequestUiState(form, loaded = true, geo = geo))
        show { CreateRequestScreen(state.value, online = true, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}) }
        for (s in 0 until CreateRequestViewModel.STEPS) {
            compose.runOnUiThread { state.value = state.value.copy(form = form.copy(step = s)) }
            compose.waitForIdle()
            assertControlsAreLabelledAndBigEnough("Create request step ${s + 1}")
        }
        compose.runOnUiThread { state.value = state.value.copy(limit = OpenRequestLimit(max = 3, newAccount = false)) }
        compose.waitForIdle()
        assertControlsAreLabelledAndBigEnough("Create request, open-request limit")
    }

    private val donor = TestData.profile(uid = "donor")
    private val requester = TestData.profile(uid = "requester-1")

    /** The real detail screen on fakes: a donor or the requester, with or without an offer. */
    private fun detail(viewer: UserProfile, donorOffered: Boolean): RequestDetailViewModel {
        val requests = FakeRequestRepository().apply {
            put(TestData.request(id = "r1").copy(notes = "Surgery at 10 am", condition = PatientCondition.SURGERY))
            contacts["r1"] = RequestContact("requester-1", "Karim Hossain", "+8801812345678", "Abdul", "Ward 5")
        }
        val offers = FakeOfferRepository(requests)
        if (donorOffered) runBlocking { offers.offer(requests.requests.value.getValue("r1"), donor, "I can come at 4 pm") }
        val vm = RequestDetailViewModel(
            SavedStateHandle(mapOf("id" to "r1")),
            FakeSessionManager(SessionState.Ready(TestData.user(uid = viewer.uid), viewer)),
            requests, offers, FakeProfileRepository(), FakeSafetyRepository(), FakeWorkerClient(),
            object : GeoRepository {
                override suspend fun geo() = this@AccessibilityLabelsTest.geo
            },
            EligibilityCalculator(), Clock { TestData.NOW }, FakeNetworkMonitor(),
        )
        show { RequestDetailRoute(onBack = {}, viewModel = vm) }
        compose.waitUntil(5_000) { vm.uiState.value.request != null }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun requestDetailForADonorBeforeOfferingAndTheSelfCheck() {
        detail(donor, donorOffered = false)
        assertControlsAreLabelledAndBigEnough("Request detail, donor")
        compose.onNode(hasText(str(R.string.detail_i_can_donate)) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertControlsAreLabelledAndBigEnough("Before you offer sheet")
    }

    @Test
    fun requestDetailForADonorWhoOffered() {
        val vm = detail(donor, donorOffered = true)
        compose.waitUntil(5_000) { vm.uiState.value.contact != null }
        compose.waitForIdle()
        assertControlsAreLabelledAndBigEnough("Request detail, donor who offered")
    }

    @Test
    fun requestDetailForTheRequesterAndItsMenu() {
        val vm = detail(requester, donorOffered = true)
        compose.waitUntil(5_000) { vm.uiState.value.responses.isNotEmpty() }
        compose.waitForIdle()
        assertControlsAreLabelledAndBigEnough("Request detail, requester")
        compose.onNode(hasContentDescription(str(R.string.action_more))).performClick()
        compose.waitForIdle()
        assertControlsAreLabelledAndBigEnough("Request detail, requester menu")
    }

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
