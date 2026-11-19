package io.github.liquidlyf.feature.request.detail

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.util.Clock
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * D17 on the real request page (fakes behind it): the requester taps Confirm, then Mark donated,
 * at large text. Each time the card's next step stays fully above the Share bar; after Confirm only
 * a third of Mark donated showed (the maintainer's hand test, VT-09 at the largest text).
 */
@RunWith(AndroidJUnit4::class)
class OfferScrollTest {
    @get:Rule val compose = createComposeRule()

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private var context: Context = target
    private val now: Instant = Instant.now()
    private val geo = target.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) }
    private val requester = TestData.profile(uid = "requester-1").copy(fullName = "Karim Hossain", email = "karim@example.test")
    private val donor = TestData.profile(uid = "donor").copy(fullName = "Nusrat Jahan")
    private val request = TestData.request(id = "r1", neededBy = now.plus(Duration.ofHours(5))).copy(
        unitsNeeded = 2, notes = "Thalassemia patient, needs blood every month. Please bring your donor card.",
        hospitalAddress = "Bakshibazar, Dhaka", createdAt = now.minus(Duration.ofMinutes(12)),
    )

    private fun viewModel(): RequestDetailViewModel {
        val requests = FakeRequestRepository(now).apply {
            put(request)
            contacts[request.id] = RequestContact("requester-1", "Karim Hossain", "+8801700000000", "Ayesha Begum", "Ward 5, bed 12")
        }
        val offers = FakeOfferRepository(requests)
        runBlocking { offers.offer(request, donor, "I can come at 4 pm") }
        return RequestDetailViewModel(
            SavedStateHandle(mapOf("id" to request.id)),
            FakeSessionManager(SessionState.Ready(TestData.user(uid = requester.uid), requester)),
            requests, offers, FakeProfileRepository(now), FakeSafetyRepository(), FakeWorkerClient(),
            object : GeoRepository { override suspend fun geo() = this@OfferScrollTest.geo },
            EligibilityCalculator(), Clock { now }, FakeNetworkMonitor(), FakeReminders(),
        )
    }

    // Bangla is where it showed: its hint takes 3 lines at 200%, and the card sat on the bar's edge.
    @Test fun banglaAt130() = confirmThenMarkDonated(1.3f, "bn")

    @Test fun banglaAt160() = confirmThenMarkDonated(1.6f, "bn")

    @Test fun banglaAt200() = confirmThenMarkDonated(2f, "bn")

    @Test fun englishAt200() = confirmThenMarkDonated(2f, "en")

    private fun confirmThenMarkDonated(fontScale: Float, language: String) {
        context = target.createConfigurationContext(Configuration(target.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
        val vm = viewModel()
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources,
                LocalSnackbarHostState provides remember { SnackbarHostState() },
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                LiquidLyfTheme(darkTheme = false) { RequestDetailRoute(onBack = {}, viewModel = vm) }
            }
        }
        val confirm = context.getString(R.string.detail_confirm)
        val donated = context.getString(R.string.detail_mark_donated)
        val onPage = !hasAnyAncestor(isDialog())

        val confirmButton = hasText(confirm) and hasClickAction()
        waitFor(confirmButton)
        assertAboveTheBar(confirmButton, "Confirm, on opening at $fontScale")

        compose.onNode(confirmButton).performClick()
        val donatedButton = hasText(donated) and hasClickAction() and onPage
        waitFor(donatedButton)
        assertAboveTheBar(donatedButton, "Mark donated, after Confirm at $fontScale")

        compose.onNode(donatedButton).performClick()
        val dialogButton = hasText(donated) and hasClickAction() and hasAnyAncestor(isDialog())
        waitFor(dialogButton)
        Thread.sleep(DIALOG_MS) // its window takes touches once it has opened
        compose.onNode(dialogButton).performClick()
        val name = hasText("Nusrat", substring = true) and onPage
        compose.waitUntil(10_000) { compose.onAllNodes(donatedButton).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertAboveTheBar(name, "the donor's card, after Mark donated at $fontScale")
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /**
     * Fully on screen and over the Share bar's top edge (its divider). Its whole size, not the part
     * the scrolling clips: a button below the fold has no height left, so clipped bounds would pass.
     */
    private fun assertAboveTheBar(matcher: SemanticsMatcher, what: String) {
        val node = compose.onNode(matcher).fetchSemanticsNode()
        val share = compose.onNode(hasText(context.getString(R.string.action_share)) and hasClickAction()).fetchSemanticsNode()
        val gap = with(compose.density) { (Spacing.sm + 1.dp).toPx() }
        val barTop = share.positionInRoot.y - gap
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        assertTrue("$what: its bottom ($bottom px) is under the Share bar (top $barTop px)", bottom <= barTop + 0.5f)
        assertTrue("$what: its top ($top px) is off the screen", top >= 0f)
    }

    private companion object {
        const val DIALOG_MS = 500L
    }
}
