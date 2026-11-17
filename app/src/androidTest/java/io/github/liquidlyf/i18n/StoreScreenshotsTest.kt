package io.github.liquidlyf.i18n

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.domain.BadgeCalculator
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.donors.DonorFilters
import io.github.liquidlyf.feature.donors.FindDonorsActions
import io.github.liquidlyf.feature.donors.FindDonorsScreen
import io.github.liquidlyf.feature.donors.FindDonorsUiState
import io.github.liquidlyf.feature.home.HomeScreen
import io.github.liquidlyf.feature.home.HomeUiState
import io.github.liquidlyf.feature.profile.ProfileScreen
import io.github.liquidlyf.feature.profile.ProfileUiState
import io.github.liquidlyf.feature.request.detail.RequestDetailRoute
import io.github.liquidlyf.feature.request.detail.RequestDetailViewModel
import io.github.liquidlyf.navigation.AppNavigationBar
import io.github.liquidlyf.navigation.TopLevelDestination
import io.github.liquidlyf.testing.FakeNetworkMonitor
import io.github.liquidlyf.testing.FakeOfferRepository
import io.github.liquidlyf.testing.FakeProfileRepository
import io.github.liquidlyf.testing.FakeReminders
import io.github.liquidlyf.testing.FakeRequestRepository
import io.github.liquidlyf.testing.FakeSafetyRepository
import io.github.liquidlyf.testing.FakeSessionManager
import io.github.liquidlyf.testing.FakeWorkerClient
import io.github.liquidlyf.testing.TestData
import io.github.liquidlyf.testing.request
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Screenshots for the README and the website (C's request): Home, a request as a donor sees it,
 * Find donors with results and Profile, in English and Bangla, with made-up people only (no
 * phone number on screen). With `screenshots=true` each is saved to the app's files folder,
 * `store-<en|bn>/`; the status bar isn't drawn (the PC adds a plain band). Otherwise it only
 * checks that each screen draws in both languages.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {

    @get:Rule
    val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val save = InstrumentationRegistry.getArguments().getString("screenshots") == "true"
    private val geo by lazy { target.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) } }
    private val now: Instant = Instant.now()

    // phoneVerified false: phone verification is v1.1 (the Worker sets it), so no v1.0 user shows that badge.
    private val me = TestData.profile(uid = "me").copy(fullName = "Nusrat Jahan", phoneVerified = false)
    private val request = TestData.request(id = "r1", neededBy = now.plus(Duration.ofHours(5)))
        .copy(urgency = Urgency.URGENT, unitsNeeded = 2, condition = PatientCondition.THALASSEMIA, createdAt = now.minus(Duration.ofMinutes(12)))

    private fun context(tag: String): Context =
        target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    private val language = mutableStateOf("en")

    private fun show(content: @Composable (AppLocale) -> Unit) {
        compose.setContent {
            val ctx = context(language.value)
            CompositionLocalProvider(
                LocalContext provides ctx,
                LocalConfiguration provides ctx.resources.configuration,
                LocalResources provides ctx.resources,
                LocalSnackbarHostState provides SnackbarHostState(),
            ) {
                LiquidLyfTheme(darkTheme = false) { content(if (language.value == "bn") AppLocale.BN else AppLocale.EN) }
            }
        }
    }

    /**
     * Draws [content] in English, then in Bangla, waiting for [marker] each time, and saves both.
     * [tab] adds the app's bottom bar, as on a real phone; [prepare] runs before each capture.
     */
    private fun both(
        name: String,
        marker: Int,
        tab: TopLevelDestination? = null,
        prepare: () -> Unit = {},
        content: @Composable (AppLocale) -> Unit,
    ) {
        show { locale ->
            if (tab == null) {
                content(locale)
            } else {
                Scaffold(bottomBar = { AppNavigationBar(tab, onSelect = {}) }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content(locale) }
                }
            }
        }
        for (tag in listOf("en", "bn")) {
            compose.runOnUiThread { language.value = tag }
            val text = context(tag).getString(marker)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
            prepare()
            compose.waitForIdle()
            if (save) {
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                val dir = File(target.getExternalFilesDir(null), "store-$tag").apply { mkdirs() }
                File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test
    fun home() {
        val eligibility = EligibilityCalculator().evaluate(me, now)!!
        both("home", R.string.home_urgent_near_you, tab = TopLevelDestination.HOME) {
            HomeScreen(
                HomeUiState(me, eligibility, available = true, requests = listOf(request), geo = geo),
                online = true, {}, {}, {}, {}, {}, notificationsEnabled = true,
            )
        }
    }

    @Test
    fun requestAsADonorSeesIt() {
        val requests = FakeRequestRepository().apply {
            put(request)
            contacts["r1"] = RequestContact("requester-1", "Karim Hossain", "+8800000000000", null, null)
        }
        val donor = me.copy(uid = "donor")
        val vm = RequestDetailViewModel(
            SavedStateHandle(mapOf("id" to "r1")),
            FakeSessionManager(SessionState.Ready(TestData.user(uid = donor.uid), donor)),
            requests, FakeOfferRepository(requests), FakeProfileRepository(), FakeSafetyRepository(), FakeWorkerClient(),
            object : GeoRepository {
                override suspend fun geo() = this@StoreScreenshotsTest.geo
            },
            EligibilityCalculator(), Clock { now }, FakeNetworkMonitor(), FakeReminders(),
        )
        both("request", R.string.detail_i_can_donate) { RequestDetailRoute(onBack = {}, viewModel = vm) }
    }

    @Test
    fun findDonors() {
        val card = { uid: String, name: String, donations: Int, verified: Int -> DonorCard(
            uid, name, BloodGroup.B_POS, TestData.DHAKA_SOUTH, available = true,
            eligibleFrom = Instant.EPOCH, donationCount = donations, verifiedDonations = verified, phoneVerified = false, lastActiveAt = now,
        ) }
        val filters = DonorFilters(groups = setOf(BloodGroup.B_POS), division = me.area.division, district = me.area.district)
        val results = listOf(card("a", "Rahim U.", 5, 3), card("b", "Sadia A.", 2, 1), card("c", "Tanvir H.", 1, 0))
        // The results are under the filters: scroll so they show.
        val toResults = { compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tanvir H.")) }
        both("find-donors", R.string.donors_eligible_now, tab = TopLevelDestination.DONORS, prepare = { toResults() }) {
            FindDonorsScreen(
                FindDonorsUiState(enabled = true, filters = filters, applied = filters, results = results, total = results.size.toLong(), geo = geo, me = me),
                FindDonorsActions(),
                now = now,
            )
        }
    }

    @Test
    fun profile() {
        val donor = me.copy(lastDonationAt = now.minus(Duration.ofDays(40)), donationCount = 3, verifiedDonations = 2)
        val eligibility = EligibilityCalculator().evaluate(donor, now)!!
        val history = listOf(
            Donation("d1", now.minus(Duration.ofDays(40)), BloodComponent.WHOLE_BLOOD, "Dhaka Medical College Hospital", requestId = "r9", verified = true, source = DonationSource.REQUEST, createdAt = now),
            Donation("d2", now.minus(Duration.ofDays(200)), BloodComponent.WHOLE_BLOOD, "Shaheed Suhrawardy Medical College Hospital", requestId = "r8", verified = true, source = DonationSource.REQUEST, createdAt = now),
            Donation("d3", now.minus(Duration.ofDays(420)), BloodComponent.PLATELETS, null, requestId = null, verified = false, source = DonationSource.SELF, createdAt = now),
        )
        val calc = BadgeCalculator()
        both("profile", R.string.profile_impact_title, tab = TopLevelDestination.PROFILE) {
            ProfileScreen(
                ProfileUiState(
                    profile = donor, eligibility = eligibility, donations = history, geo = geo,
                    badges = calc.badges(history, donor.gender, rapidResponder = true, now), impact = calc.impact(history),
                ),
                onEditProfile = {}, onOpenSettings = {}, onAddDonation = {}, onRemoveDonation = {}, onRetry = {}, now = now,
            )
        }
    }
}
