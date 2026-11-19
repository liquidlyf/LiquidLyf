package io.github.liquidlyf.gallery

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.BuildConfig
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.backend.AlertStatus
import io.github.liquidlyf.core.data.backend.AlertStatusStore
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.hospital.Hospital
import io.github.liquidlyf.core.data.push.LocalAlert
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.SystemBarScrims
import io.github.liquidlyf.core.designsystem.components.AppSnackbarHost
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarClearance
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.LocalStartLogoHandoff
import io.github.liquidlyf.core.designsystem.components.SnackbarClearance
import io.github.liquidlyf.core.designsystem.components.StartLogoHandoff
import io.github.liquidlyf.core.designsystem.components.SuccessVisuals
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.domain.NewPassword
import io.github.liquidlyf.core.domain.BadgeCalculator
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.DonationSource
import io.github.liquidlyf.core.model.DonorCard
import io.github.liquidlyf.core.model.DonorResponse
import io.github.liquidlyf.core.model.InboxItem
import io.github.liquidlyf.core.model.InboxType
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.PlaceName
import io.github.liquidlyf.core.model.Report
import io.github.liquidlyf.core.model.ReportReason
import io.github.liquidlyf.core.model.ReportStatus
import io.github.liquidlyf.core.model.ReportTarget
import io.github.liquidlyf.core.model.RequestContact
import io.github.liquidlyf.core.model.RequestStatus
import io.github.liquidlyf.core.model.Responder
import io.github.liquidlyf.core.model.ResponseStatus
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.feature.about.AboutScreen
import io.github.liquidlyf.feature.about.HowMatchingWorksScreen
import io.github.liquidlyf.feature.about.LicencesScreen
import io.github.liquidlyf.feature.about.MedicalDisclaimerScreen
import io.github.liquidlyf.feature.account.AddPasswordScreen
import io.github.liquidlyf.feature.account.AddPasswordUiState
import io.github.liquidlyf.feature.account.ChangePasswordRoute
import io.github.liquidlyf.feature.account.ChangePasswordViewModel
import io.github.liquidlyf.feature.account.DeleteAccountRoute
import io.github.liquidlyf.feature.account.DeleteAccountViewModel
import io.github.liquidlyf.feature.account.DeletingAccountRoute
import io.github.liquidlyf.feature.account.DeletingAccountViewModel
import io.github.liquidlyf.feature.account.EditProfileRoute
import io.github.liquidlyf.feature.account.EditProfileViewModel
import io.github.liquidlyf.feature.auth.ForgotPasswordScreen
import io.github.liquidlyf.feature.auth.ForgotPasswordUiState
import io.github.liquidlyf.feature.auth.SignInScreen
import io.github.liquidlyf.feature.auth.SignInUiState
import io.github.liquidlyf.feature.auth.SignUpScreen
import io.github.liquidlyf.feature.auth.SignUpUiState
import io.github.liquidlyf.feature.auth.VerifyEmailScreen
import io.github.liquidlyf.feature.auth.VerifyEmailUiState
import io.github.liquidlyf.feature.donors.DonorFilters
import io.github.liquidlyf.feature.donors.FindDonorsActions
import io.github.liquidlyf.feature.donors.FindDonorsRoute
import io.github.liquidlyf.feature.donors.FindDonorsScreen
import io.github.liquidlyf.feature.donors.FindDonorsUiState
import io.github.liquidlyf.feature.donors.FindDonorsViewModel
import io.github.liquidlyf.feature.home.AreaSheetState
import io.github.liquidlyf.feature.home.HomeScreen
import io.github.liquidlyf.feature.home.HomeUiState
import io.github.liquidlyf.feature.moderation.ModerationItem
import io.github.liquidlyf.feature.moderation.ModerationScreen
import io.github.liquidlyf.feature.moderation.ModerationUiState
import io.github.liquidlyf.feature.notifications.InboxEntry
import io.github.liquidlyf.feature.notifications.InboxScreen
import io.github.liquidlyf.feature.notifications.InboxUiState
import io.github.liquidlyf.feature.onboarding.OnboardingScreen
import io.github.liquidlyf.feature.profile.DonationDraft
import io.github.liquidlyf.feature.profile.ProfileRoute
import io.github.liquidlyf.feature.profile.ProfileScreen
import io.github.liquidlyf.feature.profile.ProfileUiState
import io.github.liquidlyf.feature.profile.ProfileViewModel
import io.github.liquidlyf.feature.profilesetup.BecomeDonorScreen
import io.github.liquidlyf.feature.profilesetup.BecomeDonorUiState
import io.github.liquidlyf.feature.profilesetup.ProfileSetupScreen
import io.github.liquidlyf.feature.profilesetup.ProfileSetupUiState
import io.github.liquidlyf.feature.profilesetup.SetupDraft
import io.github.liquidlyf.feature.profilesetup.SetupField
import io.github.liquidlyf.feature.request.create.CreateRequestRoute
import io.github.liquidlyf.feature.request.create.CreateRequestScreen
import io.github.liquidlyf.feature.request.create.CreateRequestUiState
import io.github.liquidlyf.feature.request.create.CreateRequestViewModel
import io.github.liquidlyf.feature.request.create.OpenRequestLimit
import io.github.liquidlyf.feature.request.create.RequestField
import io.github.liquidlyf.feature.request.create.RequestForm
import io.github.liquidlyf.feature.request.detail.RequestDetailRoute
import io.github.liquidlyf.feature.request.detail.RequestDetailViewModel
import io.github.liquidlyf.feature.request.list.OfferItem
import io.github.liquidlyf.feature.request.list.PagedList
import io.github.liquidlyf.feature.request.list.RequestsScreen
import io.github.liquidlyf.feature.request.list.RequestsTab
import io.github.liquidlyf.feature.request.list.RequestsUiState
import io.github.liquidlyf.feature.safety.BlockedPeopleScreen
import io.github.liquidlyf.feature.safety.BlockedPeopleUiState
import io.github.liquidlyf.feature.safety.BlockedPerson
import io.github.liquidlyf.feature.safety.SafetyTipsScreen
import io.github.liquidlyf.feature.session.NotificationPermissionScreen
import io.github.liquidlyf.feature.session.SessionProblemScreen
import io.github.liquidlyf.feature.session.SigningOutDialog
import io.github.liquidlyf.feature.session.SuspendedScreen
import io.github.liquidlyf.feature.settings.NotificationPrefsScreen
import io.github.liquidlyf.feature.settings.NotificationPrefsUiState
import io.github.liquidlyf.feature.settings.SettingsContent
import io.github.liquidlyf.feature.settings.SettingsUiState
import io.github.liquidlyf.navigation.AppNavigationBar
import io.github.liquidlyf.navigation.TopLevelDestination
import io.github.liquidlyf.start.StartClock
import io.github.liquidlyf.start.StartLogo
import io.github.liquidlyf.start.StartLogoArts
import io.github.liquidlyf.start.StartScreen
import io.github.liquidlyf.testing.FakeAccountDeleter
import io.github.liquidlyf.testing.FakeAlertStore
import io.github.liquidlyf.testing.FakeAuthRepository
import io.github.liquidlyf.testing.FakeDonorRepository
import io.github.liquidlyf.testing.FakeFeatureFlags
import io.github.liquidlyf.testing.FakeHospitalRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * The screen gallery for the UI/UX review (project-plan 13, step 1): every screen, sheet and
 * dialog in each state it has (loading, empty, error, filled…), each in light and dark, English
 * and Bangla, at 100% and 200% text. The real screens draw on the phone with made-up people
 * only; each picture is the whole phone screen, status bar included, and a screen that scrolls
 * also gets `-p2`, `-p3`… for the rest of it. Names are `<screen>-<state>-<light|dark>-<en|bn>-<100|200>.png`.
 *
 * Each first page also gets its UI tree, `trees/<name>.xml`, in `uiautomator dump`'s format (what
 * TalkBack sees, with exact bounds), so tap sizes can be measured at 200% too.
 *
 * Runs only with `gallery=true` (`app/scripts/screen_gallery.py` runs it, pulls the pictures to
 * the PC and indexes them). `galleryOnly=home,requests` limits it to names that start so, and
 * `galleryVariants=light-en-100,dark-bn-200` to some variants. 200% text is the fontScale the
 * phone would use (linear on Android 11–13, as here).
 */
@RunWith(AndroidJUnit4::class)
class ScreenGalleryTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val target = instrumentation.targetContext
    private val only = arguments.getString("galleryOnly")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    private val outDir by lazy { File(target.getExternalFilesDir(null), "gallery").apply { mkdirs() } }
    private val treeDir by lazy { File(outDir, "trees").apply { mkdirs() } }

    @Before
    fun onlyWhenAsked() = assumeTrue("the gallery runs only with gallery=true", arguments.getString("gallery") == "true")

    // ---- how a picture is taken ----

    private data class Variant(val dark: Boolean, val language: String, val fontScale: Float) {
        val tag = "${if (dark) "dark" else "light"}-$language-${if (fontScale > 1f) 200 else 100}"
    }

    private val variants = listOf(false, true).flatMap { dark ->
        listOf("en", "bn").flatMap { language -> listOf(1f, 2f).map { Variant(dark, language, it) } }
    }.filter { v -> arguments.getString("galleryVariants")?.split(',')?.contains(v.tag) ?: true }

    /**
     * One screen state. [tab] draws the app's bottom bar around it, as on the phone. [animated]
     * (a spinner or skeleton) lets it move for a moment, since tests stop endless animations.
     * [overlay] is a sheet, menu or dialog over the screen: only [scrollIn] (the sheet's scrolling
     * part) is paged then. [prepare] taps the way there.
     */
    private class Shot(
        val name: String,
        val tab: TopLevelDestination? = null,
        val animated: Boolean = false,
        /** How far into its animation an [animated] shot is taken. */
        val advanceMs: Long = 700,
        val overlay: Boolean = false,
        val scrollIn: (Steps.() -> SemanticsMatcher)? = null,
        val prepare: Steps.() -> Unit = {},
        val content: @Composable () -> Unit,
    )

    private val current = mutableStateOf<Pair<Shot, Variant>?>(null)
    private val contexts = mutableMapOf<Variant, Context>()

    private fun contextFor(v: Variant): Context = contexts.getOrPut(v) {
        val config = Configuration(target.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(v.language))
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (v.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            fontScale = v.fontScale
        }
        target.createConfigurationContext(config)
    }

    private fun gallery(vararg shots: Shot) {
        compose.setContent { Host() }
        // The window has opened and drawn once, before a first shot that stops the clock.
        compose.waitForIdle()
        Thread.sleep(OVERLAY_SETTLE_MS)
        for (shot in shots) {
            if (only.isNotEmpty() && only.none { shot.name.startsWith(it) }) continue
            for (v in variants) take(shot, v)
        }
    }

    /** D25: [screen] under the start logo, as MainActivity draws them, [sinceTapMs] after the tap. */
    @Composable
    private fun StartOver(sinceTapMs: Long, landing: StartScreen?, reduced: Boolean = false, screen: @Composable () -> Unit) {
        val resources = LocalResources.current
        val handoff = remember { StartLogoHandoff() }
        val clock = remember { StartClock(SystemClock.uptimeMillis() - sinceTapMs) }
        val arts = remember { StartLogoArts(resources, clock) }
        Box {
            CompositionLocalProvider(LocalStartLogoHandoff provides handoff.takeUnless { it.done }) { screen() }
            if (!handoff.done) StartLogo(handoff, clock, arts, landing = { landing }, reduced = reduced)
        }
    }

    @Composable
    private fun Host() {
        val (shot, v) = current.value ?: return
        val activity = LocalActivity.current as ComponentActivity // read before the language's context replaces LocalContext
        // As MainActivity: edge to edge, with the system bar icons following the app's theme.
        DisposableEffect(v.dark) {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { v.dark },
                navigationBarStyle = SystemBarStyle.auto(SystemBarScrims.LIGHT, SystemBarScrims.DARK) { v.dark },
            )
            onDispose {}
        }
        val context = contextFor(v)
        CompositionLocalProvider(
            // The language's context isn't the activity: hand over what screens find through it.
            LocalActivityResultRegistryOwner provides activity,
            LocalOnBackPressedDispatcherOwner provides activity,
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
            LocalResources provides context.resources,
            LocalDensity provides Density(LocalDensity.current.density, v.fontScale),
        ) {
            LiquidLyfTheme(darkTheme = v.dark) {
                key(shot, v) {
                    CompositionLocalProvider(
                        LocalSnackbarHostState provides remember { SnackbarHostState() },
                        LocalSnackbarClearance provides remember { SnackbarClearance() },
                    ) {
                        val tab = shot.tab
                        if (tab == null) {
                            shot.content()
                        } else {
                            Scaffold(bottomBar = { AppNavigationBar(tab, onSelect = {}) }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
                                Box(Modifier.padding(padding).consumeWindowInsets(padding)) { shot.content() }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Dialogs, sheets and menus are windows of their own, built from the activity's resources,
     * not from the composition's: they get the variant's language, font scale and theme from here
     * (on a phone, the system font size and the app's language reach them the same way).
     */
    private fun applyToActivity(v: Variant) = compose.runOnUiThread {
        val resources = compose.activity.resources
        val config = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(v.language))
            fontScale = v.fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (v.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        @Suppress("DEPRECATION") // test only: there's no other way to change a running activity's configuration
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun take(shot: Shot, v: Variant) {
        val steps = Steps(v)
        applyToActivity(v)
        val base = "${shot.name}-${v.tag}"
        if (shot.animated) {
            // Endless animations only run while the test clock is driven by hand.
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { current.value = shot to v }
            compose.mainClock.advanceTimeBy(shot.advanceMs)
            save(base)
            saveTree(base)
            compose.mainClock.autoAdvance = true
            return
        }
        compose.runOnUiThread { current.value = shot to v }
        compose.waitForIdle()
        runCatching { shot.prepare(steps) }.onFailure { throw AssertionError("$base: could not get there: ${it.message}", it) }
        compose.waitForIdle()
        // A dialog's or sheet's window fades in under the phone's own animation scale.
        save(base, settle = if (shot.overlay) OVERLAY_SETTLE_MS else SETTLE_MS)
        saveTree(base)
        if (shot.overlay && shot.scrollIn == null) return
        val scroller = scroller(steps, shot) ?: return
        var page = 2
        while (page <= MAX_PAGES && scrollDown(scroller)) save("$base-p${page++}")
    }

    private fun save(name: String, settle: Long = SETTLE_MS) {
        Thread.sleep(settle) // the last frame reaches the screen
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("no screenshot for $name")
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** The app's windows as an accessibility service sees them, written like `uiautomator dump`. */
    private fun saveTree(name: String) {
        val automation = instrumentation.uiAutomation
        // Setting the service info also clears UiAutomation's node cache: without it, a screen
        // drawn in the same window as the one before reads as that one.
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        runCatching { automation.waitForIdle(200, 2_000) } // a spinner never idles
        val xml = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">")
        automation.windows.mapNotNull { it.root }.filter { it.packageName == target.packageName }.forEachIndexed { i, root -> appendNode(xml, root, i) }
        xml.append("</hierarchy>")
        File(treeDir, "$name.xml").writeText(xml.toString())
    }

    private fun appendNode(xml: StringBuilder, n: AccessibilityNodeInfo, index: Int) {
        if (!n.refresh()) return // gone meanwhile
        fun esc(v: CharSequence?) = v?.toString().orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val r = Rect().also { n.getBoundsInScreen(it) }
        xml.append("<node index=\"$index\" text=\"${esc(n.text)}\" resource-id=\"${esc(n.viewIdResourceName)}\" class=\"${esc(n.className)}\" ")
            .append("package=\"${esc(n.packageName)}\" content-desc=\"${esc(n.contentDescription)}\" checkable=\"${n.isCheckable}\" checked=\"${n.isChecked}\" ")
            .append("clickable=\"${n.isClickable}\" enabled=\"${n.isEnabled}\" focusable=\"${n.isFocusable}\" focused=\"${n.isFocused}\" ")
            .append("scrollable=\"${n.isScrollable}\" long-clickable=\"${n.isLongClickable}\" password=\"${n.isPassword}\" selected=\"${n.isSelected}\" ")
            .append("heading=\"${n.isHeading}\" bounds=\"[${r.left},${r.top}][${r.right},${r.bottom}]\">")
        for (i in 0 until n.childCount) n.getChild(i)?.let { appendNode(xml, it, i) }
        xml.append("</node>")
    }

    /** The screen's main scrolling part (the biggest), or the sheet's ([Shot.scrollIn], the smallest that matches). */
    private fun scroller(steps: Steps, shot: Shot): SemanticsNode? {
        val inside = shot.scrollIn?.invoke(steps)
        var matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasScrollAction()
        if (inside != null) matcher = matcher and inside
        val nodes = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().filter { it.size.height > 100 }
        val area = { n: SemanticsNode -> n.size.width.toLong() * n.size.height }
        return if (inside != null) nodes.minByOrNull(area) else nodes.maxByOrNull(area)
    }

    /**
     * Scrolls one page down; false at the end. Judged by what moved on screen: a lazy list's
     * scroll range counts items, not pixels, so a page of tall rows can be less than one step.
     */
    private fun scrollDown(node: SemanticsNode): Boolean {
        val scrollBy = node.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
        val before = onScreen()
        compose.runOnUiThread { scrollBy(0f, node.size.height * 0.8f) }
        compose.waitForIdle()
        return onScreen() != before
    }

    /** Every text on screen with its position: changes when anything scrolls. */
    private fun onScreen(): List<Pair<String, Float>> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .map { n -> n.config[SemanticsProperties.Text].joinToString { it.text } to n.boundsInRoot.top }

    /** Taps in the variant's language. */
    private inner class Steps(v: Variant) {
        private val context = contextFor(v)

        /** The variant's language, for names from the area list. */
        val locale: AppLocale = if (v.language == "bn") AppLocale.BN else AppLocale.EN

        /** Opens an area box (its label sits above it, so it's found by its description) and picks [item]. */
        fun pick(@StringRes field: Int, item: String) {
            tapOn((hasText(str(field)) or hasContentDescription(str(field), substring = true)) and hasClickAction())
            waitForText(item)
            compose.onAllNodes(hasText(item)).onLast().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }

        fun str(@StringRes id: Int): String = context.getString(id)

        fun tap(@StringRes id: Int) = tapOn(hasText(str(id)) and hasClickAction())

        fun tapDescribed(@StringRes id: Int) = tapOn(hasContentDescription(str(id)) and hasClickAction())

        /** For a label with a placeholder ("More about %1$s"): the first button whose label starts so. */
        fun tapDescribedStart(@StringRes id: Int) = tapOn(hasContentDescription(str(id).substringBefore("%").trim(), substring = true) and hasClickAction())

        fun tapOn(matcher: SemanticsMatcher) {
            val found = { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
            if (!runCatching { compose.waitUntil(3_000) { found() } }.isSuccess) {
                // Below the fold in a lazy list (200% text): not composed until scrolled to.
                compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().indices.forEach { i ->
                    if (!found()) runCatching { compose.onAllNodes(hasScrollAction())[i].performScrollToNode(matcher) }
                }
                compose.waitUntil(10_000) { found() }
            }
            compose.onAllNodes(matcher).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }

        fun waitForText(text: String) =
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

        fun waitFor(@StringRes id: Int) = waitForText(str(id))

        /** For a text with a placeholder ("Ask %1$s to donate"): its part before the placeholder. */
        fun waitForStart(@StringRes id: Int) = waitForText(str(id).substringBefore("%").trim().ifEmpty { str(id).substringAfterLast("$").drop(1).trim() })

        fun sheetWith(@StringRes id: Int): SemanticsMatcher = hasAnyDescendant(hasText(str(id), substring = true))

        /** VT-13: focus the screen's last text field, then press the keyboard's Done. */
        fun doneOnLastField() {
            compose.onAllNodes(hasSetTextAction()).onLast().performSemanticsAction(SemanticsActions.OnClick)
            Thread.sleep(KEYBOARD_MS)
            compose.onAllNodes(hasSetTextAction()).onLast().performImeAction()
            Thread.sleep(KEYBOARD_MS)
        }
    }

    // ---- made-up people and requests (no real names or numbers) ----

    private val now: Instant = Instant.now()
    private val geo by lazy { target.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) } }
    private val geoRepository = object : GeoRepository {
        override suspend fun geo() = this@ScreenGalleryTest.geo
    }
    private val clock = Clock { now }

    private val me: UserProfile = TestData.profile(uid = "me").copy(
        fullName = "Nusrat Jahan", email = "nusrat@example.test", phone = "+8801700000000", phoneVerified = false,
    )
    private val requesterProfile = TestData.profile(uid = "requester-1").copy(fullName = "Karim Hossain", email = "karim@example.test")
    private val hospital = "Dhaka Medical College Hospital"

    /** D24's test password (as in D's picture) typed twice. */
    private val matched = NewPassword("rokto2026", "rokto2026")

    /** D24: the password states D checks, besides empty and the button's errors. */
    private val passwordStates = listOf(
        "short" to NewPassword("rokto"), // typing: grey, 5/8
        "short-left" to NewPassword("rokto").focusValue(false), // left with 5: red, 5/8
        "typing-again" to NewPassword("rokto2026").focusAgain(true).typeAgain("rokto"), // a correct start: no colour
        "match" to matched,
        "no-match" to NewPassword("rokto2026").focusAgain(true).typeAgain("rokto2O"), // a wrong letter: red at once
    )

    /** Change password's view model, typed into the way [target] was made. */
    private fun ChangePasswordViewModel.typeTo(target: NewPassword) {
        onNewChange(target.value)
        if (target.shortShown) { onNewFocus(true); onNewFocus(false) }
        onNewAgainFocus(target.typingAgain)
        if (target.again.isNotEmpty()) onNewAgainChange(target.again)
    }

    private fun eligibility(p: UserProfile) = EligibilityCalculator().evaluate(p, now)

    private val feed: List<BloodRequest> = listOf(
        TestData.request(id = "r1", neededBy = now.plus(Duration.ofHours(5))).copy(
            urgency = Urgency.URGENT, unitsNeeded = 2, condition = PatientCondition.THALASSEMIA, createdAt = now.minus(Duration.ofMinutes(12)),
        ),
        TestData.request(id = "r2", requesterUid = "requester-2", group = BloodGroup.O_NEG, neededBy = now.plus(Duration.ofHours(2))).copy(
            requesterDisplayName = "Shirin A.", urgency = Urgency.CRITICAL, condition = PatientCondition.SURGERY,
            hospitalName = "Shaheed Suhrawardy Medical College Hospital", acceptCompatible = true,
            acceptedGroups = listOf(BloodGroup.O_NEG), responseCount = 1, createdAt = now.minus(Duration.ofMinutes(40)),
        ),
        TestData.request(id = "r3", requesterUid = "requester-3", group = BloodGroup.A_POS, neededBy = now.plus(Duration.ofDays(2))).copy(
            requesterDisplayName = "Tanvir R.", urgency = Urgency.PLANNED, component = BloodComponent.PLATELETS, unitsNeeded = 3,
            unitsFulfilled = 1, hospitalName = "Holy Family Red Crescent Medical College Hospital", createdAt = now.minus(Duration.ofHours(3)),
        ),
        // D15 a, VT-03: a note that mentions money is listed with the amber tag.
        TestData.request(id = "r4", requesterUid = "requester-4", group = BloodGroup.B_POS, neededBy = now.plus(Duration.ofHours(20))).copy(
            requesterDisplayName = "Newcomer D.", urgency = Urgency.URGENT, flagged = true, notes = "Will pay 2000 taka by bKash for each bag.",
            createdAt = now.minus(Duration.ofMinutes(25)),
        ),
    )

    private val myRequests: List<BloodRequest> = listOf(
        TestData.request(id = "m1", requesterUid = "me", neededBy = now.plus(Duration.ofHours(8))).copy(
            requesterDisplayName = "Nusrat J.", urgency = Urgency.URGENT, unitsNeeded = 2, responseCount = 2, createdAt = now.minus(Duration.ofHours(1)),
        ),
        TestData.request(id = "m2", requesterUid = "me", status = RequestStatus.FULFILLED, neededBy = now.minus(Duration.ofDays(3))).copy(
            requesterDisplayName = "Nusrat J.", unitsFulfilled = 1, createdAt = now.minus(Duration.ofDays(4)),
        ),
        TestData.request(id = "m3", requesterUid = "me", status = RequestStatus.CANCELLED, neededBy = now.minus(Duration.ofDays(20))).copy(
            requesterDisplayName = "Nusrat J.", createdAt = now.minus(Duration.ofDays(21)),
        ),
    )

    private fun response(request: BloodRequest, status: ResponseStatus, message: String? = null) = DonorResponse(
        "me", request.id, request.requesterUid, status, "Nusrat J.", BloodGroup.B_POS, null, message,
        now.minus(Duration.ofMinutes(30)), now.minus(Duration.ofMinutes(10)),
    )

    private val myOffers: List<OfferItem> = listOf(
        OfferItem(response(feed[0], ResponseStatus.OFFERED, "I can come at 4 pm"), feed[0]),
        OfferItem(response(feed[1], ResponseStatus.CONFIRMED), feed[1]),
        OfferItem(response(myRequests[1].copy(id = "o3"), ResponseStatus.DONATED), myRequests[1].copy(id = "o3", requesterUid = "requester-4", requesterDisplayName = "Rafiq M.")),
        OfferItem(response(feed[2], ResponseStatus.DECLINED), feed[2]),
    )

    private fun card(uid: String, name: String, donations: Int, verified: Int, restingDays: Long = 0) = DonorCard(
        uid, name, BloodGroup.B_POS, TestData.DHAKA_SOUTH, available = true,
        eligibleFrom = if (restingDays > 0) now.plus(Duration.ofDays(restingDays)) else Instant.EPOCH,
        donationCount = donations, verifiedDonations = verified, phoneVerified = false, lastActiveAt = now.minus(Duration.ofHours(donations + 1L)),
    )

    private val donorCards = listOf(card("a", "Rahim U.", 5, 3), card("b", "Sadia A.", 2, 1), card("c", "Tanvir H.", 1, 0), card("d", "Farhana K.", 0, 0))

    private val history = listOf(
        Donation("d1", now.minus(Duration.ofDays(40)), BloodComponent.WHOLE_BLOOD, hospital, requestId = "r9", verified = true, source = DonationSource.REQUEST, createdAt = now),
        Donation("d2", now.minus(Duration.ofDays(200)), BloodComponent.WHOLE_BLOOD, "Shaheed Suhrawardy Medical College Hospital", requestId = "r8", verified = true, source = DonationSource.REQUEST, createdAt = now),
        Donation("d3", now.minus(Duration.ofDays(420)), BloodComponent.PLATELETS, null, requestId = null, verified = false, source = DonationSource.SELF, createdAt = now),
    )
    private val donorMe = me.copy(lastDonationAt = now.minus(Duration.ofDays(40)), donationCount = 3, verifiedDonations = 2)

    /** D1: the same person straight from the short setup, before Become a donor. */
    private fun requesterOnly(p: UserProfile) = p.copy(
        bloodGroup = null, gender = null, birthYear = null, weightOk = null, available = false, showInDonorSearch = false,
        consentAt = null, lastDonationAt = null,
    )

    private fun ready(p: UserProfile) = FakeSessionManager(SessionState.Ready(TestData.user(uid = p.uid).copy(email = p.email ?: "", displayName = p.fullName), p))

    // ---- before sign-in ----

    @Test
    fun beforeSignIn() {
        val signInErrors = SignInUiState(email = "nusrat@", emailError = R.string.error_invalid_email, passwordError = R.string.error_password_required)
        val draft = SetupDraft(uid = "me", division = "BD30", district = "BD3026", upazila = "BD30262000", bloodGroup = "B_POS",
            fullName = "Nusrat Jahan", gender = "FEMALE", birthYear = "1998", weightOk = true, phone = "01700000000", neverDonated = true)
        // D1: the short setup, then Become a donor's 3 steps.
        val setup = { d: SetupDraft, errors: Set<SetupField>, terms: Boolean ->
            @Composable {
                ProfileSetupScreen(ProfileSetupUiState(d, geo, loaded = true, errors = errors, needsTerms = terms), currentAppLocale(), {}, {}, {}, {})
            }
        }
        val donorStep = { step: Int, d: SetupDraft, errors: Set<SetupField> ->
            @Composable { BecomeDonorScreen(BecomeDonorUiState(d.copy(step = step), geo = geo, area = d.district?.let { Area(d.division.orEmpty(), it, d.upazila.orEmpty()) }, errors = errors), {}, {}, {}) }
        }
        val empty = SetupDraft(uid = "me")
        gallery(
            // D25: the start logo. Still (as the phone's still picture), loading after 1.5 s (the drop
            // part drained, the thin line), the still words with Remove animations, and 0.12 s into the
            // glide into the welcome page and Sign in.
            Shot("start-still", animated = true, advanceMs = 100) { StartOver(sinceTapMs = 300, landing = null) {} },
            Shot("start-loading", animated = true, advanceMs = 400) { StartOver(sinceTapMs = 2_000, landing = null) {} },
            Shot("start-loading-no-animations", animated = true, advanceMs = 100) { StartOver(sinceTapMs = 2_000, landing = null, reduced = true) {} },
            Shot("start-glide-welcome", animated = true, advanceMs = 120) {
                StartOver(sinceTapMs = 1_000, landing = StartScreen.WELCOME) { OnboardingScreen(onFinished = {}) }
            },
            Shot("start-glide-sign-in", animated = true, advanceMs = 120) {
                StartOver(sinceTapMs = 1_000, landing = StartScreen.SIGN_IN) { SignInScreen(SignInUiState(), {}, {}, {}, {}, {}, onContinueWithGoogle = {}) }
            },
            Shot("onboarding-1") { OnboardingScreen(onFinished = {}) },
            Shot("onboarding-2", prepare = { tap(R.string.action_next) }) { OnboardingScreen(onFinished = {}) },
            Shot("onboarding-3", prepare = { repeat(2) { tap(R.string.action_next) } }) { OnboardingScreen(onFinished = {}) },
            Shot("onboarding-4", prepare = { repeat(3) { tap(R.string.action_next) } }) { OnboardingScreen(onFinished = {}) },
            Shot("sign-in-empty") { SignInScreen(SignInUiState(), {}, {}, {}, {}, {}, onContinueWithGoogle = {}) },
            Shot("sign-in-errors") { SignInScreen(signInErrors, {}, {}, {}, {}, {}, onContinueWithGoogle = {}) },
            Shot("sign-in-wrong-password") {
                SignInScreen(SignInUiState(email = "nusrat@example.test", password = "secret12", formError = AppError.InvalidCredentials.messageRes), {}, {}, {}, {}, {}, onContinueWithGoogle = {})
            },
            Shot("sign-in-loading", animated = true) {
                SignInScreen(SignInUiState(email = "nusrat@example.test", password = "secret12", loading = true), {}, {}, {}, {}, {}, onContinueWithGoogle = {})
            },
            Shot("sign-up-empty") { SignUpScreen(SignUpUiState(), {}, {}, {}, {}, {}, {}, onContinueWithGoogle = {}) },
            Shot("sign-up-errors") {
                SignUpScreen(
                    SignUpUiState(name = "N", email = "nusrat@", password = NewPassword("12").check(), nameError = R.string.error_name_invalid, emailError = R.string.error_invalid_email,
                        consentError = R.string.error_consent_required),
                    {}, {}, {}, {}, {}, {}, onContinueWithGoogle = {},
                )
            },
            Shot("sign-up-email-in-use") {
                // As SignUpViewModel shows it: on the email field (FORM-04), with Sign in next to it.
                SignUpScreen(SignUpUiState(name = "Nusrat Jahan", email = "nusrat@example.test", password = matched, consent = true, emailError = AppError.EmailInUse.messageRes),
                    {}, {}, {}, {}, {}, {}, onContinueWithGoogle = {})
            },
            // VT-13: Done on the second password box closes the keyboard and shows the 18+ box.
            Shot("sign-up-password-done", prepare = { doneOnLastField() }) {
                SignUpScreen(SignUpUiState(name = "Nusrat Jahan", email = "nusrat@example.test", password = matched), {}, {}, {}, {}, {}, {}, onContinueWithGoogle = {})
            },
            // D24: the two password boxes while typing, as NewPassword decides them.
            *passwordStates.map { (state, password) ->
                Shot("sign-up-password-$state") {
                    SignUpScreen(SignUpUiState(name = "Nusrat Jahan", email = "nusrat@example.test", password = password), {}, {}, {}, {}, {}, {}, onContinueWithGoogle = {})
                }
            }.toTypedArray(),
            Shot("verify-email-wait") { VerifyEmailScreen(VerifyEmailUiState(email = "nusrat@example.test", sender = SENDER, resendIn = 42), {}, {}, {}) },
            Shot("verify-email-can-resend") { VerifyEmailScreen(VerifyEmailUiState(email = "nusrat@example.test", sender = SENDER, resendIn = 0), {}, {}, {}) },
            Shot("forgot-password-empty") { ForgotPasswordScreen(ForgotPasswordUiState(sender = SENDER), {}, {}, {}) },
            Shot("forgot-password-sent") { ForgotPasswordScreen(ForgotPasswordUiState(email = "nusrat@example.test", sentTo = "nusrat@example.test", sender = SENDER), {}, {}, {}) },
            Shot("profile-setup-empty", content = setup(empty, emptySet(), false)),
            Shot("profile-setup-errors", content = setup(empty, SetupField.entries.toSet(), true)),
            Shot("profile-setup-filled", content = setup(draft, emptySet(), false)),
            Shot("profile-setup-google-terms", content = setup(draft, emptySet(), true)),
            Shot("become-donor-1-empty", content = donorStep(0, empty, emptySet())),
            Shot("become-donor-1-errors", content = donorStep(0, empty, SetupField.entries.toSet())),
            Shot("become-donor-1-filled", content = donorStep(0, draft, emptySet())),
            Shot("become-donor-1-blood-help", overlay = true, prepare = { tap(R.string.setup_blood_unknown) }, content = donorStep(0, empty, emptySet())),
            Shot("become-donor-2-empty", content = donorStep(1, empty, emptySet())),
            Shot("become-donor-2-errors", content = donorStep(1, empty, SetupField.entries.toSet())),
            Shot("become-donor-2-filled", content = donorStep(1, draft, emptySet())),
            // VT-13: Done on the year of birth shows the weight question.
            Shot("become-donor-2-year-done", prepare = { doneOnLastField() }, content = donorStep(1, draft.copy(weightOk = null), emptySet())),
            Shot("become-donor-3-empty", content = donorStep(2, empty.copy(bloodGroup = "B_POS"), emptySet())),
            Shot("become-donor-3-errors", content = donorStep(2, empty.copy(bloodGroup = "B_POS"), SetupField.entries.toSet())),
            Shot("become-donor-3-filled", content = donorStep(2, draft.copy(listed = true), emptySet())),
            Shot("become-donor-3-no-group", content = donorStep(2, draft.copy(bloodGroup = null), emptySet())),
            // D23 a: one card says why, in place of "list me".
            Shot("become-donor-3-weight-no") {
                BecomeDonorScreen(BecomeDonorUiState(draft.copy(step = 2, weightOk = false), geo = geo, listingBlock = IneligibleReason.LOW_WEIGHT), {}, {}, {})
            },
            Shot("become-donor-3-over-60") {
                BecomeDonorScreen(BecomeDonorUiState(draft.copy(step = 2, birthYear = "1960"), geo = geo, listingBlock = IneligibleReason.TOO_OLD), {}, {}, {})
            },
            Shot("notification-permission") { NotificationPermissionScreen(onDone = {}) },
            Shot("session-problem") { SessionProblemScreen(AppError.Network, onRetry = {}, onSignOut = {}) },
            Shot("suspended") { SuspendedScreen(onSignOut = {}) },
            Shot("signing-out", animated = true) { SigningOutDialog() },
        )
    }

    // ---- Home ----

    @Test
    fun home() {
        val home = { p: UserProfile, requests: List<BloodRequest>?, online: Boolean, unread: Boolean, notifications: Boolean, available: Boolean,
                     own: List<BloodRequest> ->
            @Composable {
                HomeScreen(HomeUiState(p, eligibility(p), available = available, requests = requests, geo = geo, ownRequests = own), online, {}, {}, {}, {}, {},
                    unreadInbox = unread, notificationsEnabled = notifications)
            }
        }
        val notListed = me.copy(showInDonorSearch = false)
        // D27: the empty feed, with the "Change area" sheet as [sheet] says.
        val homeArea = { sheet: AreaSheetState ->
            @Composable {
                HomeScreen(
                    HomeUiState(me, eligibility(me), available = true, requests = emptyList(), geo = geo), true, {}, {}, {}, {}, {},
                    unreadInbox = false, notificationsEnabled = true, areaSheet = sheet,
                )
            }
        }
        val tab = TopLevelDestination.HOME
        // N6: my own open request, with one offer.
        val own = listOf(myRequests[0].copy(responders = mapOf("d1" to Responder("Rahim U.", BloodGroup.B_POS, ResponseStatus.OFFERED))))
        // D1: a requester from the short setup, and a donor who doesn't know the group yet (P1).
        val notDonor = requesterOnly(me)
        val didDonate = listOf(
            myRequests[0].copy(
                neededBy = now.minus(Duration.ofHours(2)),
                responders = mapOf("d1" to Responder("Rahim U.", BloodGroup.B_POS, ResponseStatus.CONFIRMED)),
            ),
        )
        val noGroup = me.copy(bloodGroup = null, showInDonorSearch = false)
        gallery(
            Shot("home-filled", tab, content = home(me, feed, true, false, true, true, emptyList())),
            // D27: "Change area" on the empty state opens a sheet with only the area: open, an
            // incomplete area after Save, saving, and a failed save.
            Shot("home-change-area", tab, overlay = true, prepare = { waitFor(R.string.home_change_area_line) }, content = homeArea(AreaSheetState(open = true))),
            Shot("home-change-area-incomplete", tab, overlay = true, prepare = {
                waitFor(R.string.home_change_area_line)
                pick(R.string.label_division, geo.divisions.first { d -> d.districts.any { it.code == "BD4041" } }.name.localized(locale))
                tap(R.string.action_save)
                waitFor(R.string.error_location_required)
            }, content = homeArea(AreaSheetState(open = true))),
            // A sheet is a window of its own, which a stopped clock doesn't draw: "Saving…" with a still spinner.
            Shot("home-change-area-saving", tab, overlay = true, prepare = { waitFor(R.string.action_saving) }, content = homeArea(AreaSheetState(open = true, saving = true))),
            Shot("home-change-area-error", tab, overlay = true, prepare = { waitFor(R.string.error_network) }, content = homeArea(AreaSheetState(open = true, error = R.string.error_network))),
            // D25: 0.12 s into the start logo's shrink into Home's top-bar logo.
            Shot("start-shrink-home", animated = true, advanceMs = 120) {
                StartOver(sinceTapMs = 1_000, landing = StartScreen.HOME) {
                    Scaffold(bottomBar = { AppNavigationBar(tab, onSelect = {}) }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding)) { home(me, feed, true, false, true, true, emptyList())() }
                    }
                }
            },
            Shot("home-loading", tab, animated = true, content = home(me, null, true, false, true, true, emptyList())),
            Shot("home-empty", tab, content = home(me, emptyList(), true, false, true, true, emptyList())),
            Shot("home-offline", tab, content = home(me, feed, false, false, true, true, emptyList())),
            Shot("home-notifications-off-unread", tab, content = home(me, feed, true, true, false, true, emptyList())),
            Shot("home-not-ready", tab, content = home(me, feed, true, false, true, false, emptyList())),
            Shot("home-resting", tab, content = home(donorMe, feed, true, false, true, true, emptyList())),
            Shot("home-not-listed", tab, content = home(notListed, feed, true, false, true, false, emptyList())),
            // D23 a: not listed, why, and (weight) the way back; no switch.
            Shot("home-weight-no", tab, content = home(notListed.copy(weightOk = false), feed, true, false, true, false, emptyList())),
            Shot("home-over-60", tab, content = home(notListed.copy(birthYear = 1960), feed, true, false, true, false, emptyList())),
            Shot("home-list-me-dialog", tab, overlay = true, prepare = { tap(R.string.home_list_me) }, content = home(notListed, feed, true, false, true, false, emptyList())),
            Shot("home-not-donor", tab, content = home(notDonor, feed, true, false, true, false, emptyList())),
            Shot("home-no-blood-group", tab, content = home(noGroup, feed, true, false, true, false, emptyList())),
            Shot("home-your-request", tab, content = home(me, feed, true, false, true, true, own)),
            Shot("home-your-request-notifications-off", tab, content = home(me, feed, true, false, false, true, own)),
            Shot("home-your-request-not-donor", tab, content = home(notDonor, feed, true, false, true, false, own)),
            // FLOW-10: past its needed-by time with a donor I confirmed: "Did Rahim U. donate?".
            Shot("home-did-donate", tab, content = home(me, feed, true, false, true, true, didDonate)),
        )
    }

    // ---- Requests ----

    @Test
    fun requests() {
        val list = { tab: RequestsTab, nearby: PagedList<BloodRequest>, mine: PagedList<BloodRequest>, offers: PagedList<OfferItem>, online: Boolean ->
            @Composable { RequestsScreen(RequestsUiState(tab = tab, nearby = nearby, mine = mine, offers = offers, geo = geo, myUid = me.uid), online, {}, {}, {}, {}, {}) }
        }
        // VT-22: my own open request is in Nearby too, tagged "Yours".
        val nearby = PagedList(listOf(myRequests[0]) + feed, loaded = true, endReached = true)
        val mine = PagedList(myRequests, loaded = true, endReached = true)
        val offers = PagedList(myOffers, loaded = true, endReached = true)
        val noRequests = PagedList<BloodRequest>(loaded = true, endReached = true)
        val noOffers = PagedList<OfferItem>(loaded = true, endReached = true)
        val tab = TopLevelDestination.REQUESTS
        gallery(
            Shot("requests-nearby", tab, content = list(RequestsTab.NEARBY, nearby, mine, offers, true)),
            Shot("requests-nearby-loading", tab, animated = true, content = list(RequestsTab.NEARBY, PagedList(loading = true), mine, offers, true)),
            Shot("requests-nearby-empty", tab, content = list(RequestsTab.NEARBY, noRequests, mine, offers, true)),
            Shot("requests-nearby-error", tab, content = list(RequestsTab.NEARBY, PagedList(error = R.string.error_network), mine, offers, true)),
            Shot("requests-nearby-offline", tab, content = list(RequestsTab.NEARBY, nearby, mine, offers, false)),
            Shot("requests-mine", tab, content = list(RequestsTab.MINE, nearby, mine, offers, true)),
            Shot("requests-mine-empty", tab, content = list(RequestsTab.MINE, nearby, noRequests, offers, true)),
            Shot("requests-offers", tab, content = list(RequestsTab.OFFERS, nearby, mine, offers, true)),
            Shot("requests-offers-empty", tab, content = list(RequestsTab.OFFERS, nearby, mine, noOffers, true)),
        )
    }

    // ---- Request blood (the form) ----

    @Test
    fun requestBlood() {
        val filled = RequestForm(
            bloodGroup = "B_POS", units = 2, condition = PatientCondition.THALASSEMIA.code,
            division = "BD30", district = "BD3026", upazila = "BD30262000", hospitalName = hospital, wardOrBed = "Ward 5, bed 12",
            urgency = Urgency.URGENT.code, contactName = "Karim Hossain", contactPhone = "01700000000", patientName = "Ayesha Begum",
            notes = "Please call before you come.", genuine = true,
        )
        val hospitals = listOf(
            Hospital("h1", PlaceName(hospital, "ঢাকা মেডিকেল কলেজ হাসপাতাল"), "Bakshibazar, Dhaka", "BD3026", null, null),
            Hospital("h2", PlaceName("Dhaka Shishu Hospital", "ঢাকা শিশু হাসপাতাল"), "Sher-e-Bangla Nagar, Dhaka", "BD3026", null, null),
            Hospital("h3", PlaceName("Dhaka Dental College Hospital", null), "Mirpur, Dhaka", "BD3026", null, null),
        )
        val form = { state: CreateRequestUiState, online: Boolean ->
            @Composable { CreateRequestScreen(state, online, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}) }
        }
        val at = { f: RequestForm -> CreateRequestUiState(f, loaded = true, geo = geo, myName = "Karim Hossain", myPhone = "+8801700000000") }
        gallery(
            Shot("request-blood-1-empty", content = form(at(RequestForm()), true)),
            Shot("request-blood-1-errors", content = form(at(RequestForm()).copy(errors = setOf(RequestField.BLOOD_GROUP, RequestField.UNITS)), true)),
            Shot("request-blood-1-filled", content = form(at(filled), true)),
            Shot("request-blood-2-empty", content = form(at(RequestForm(step = 1)), true)),
            Shot("request-blood-2-errors", content = form(at(RequestForm(step = 1)).copy(errors = setOf(RequestField.LOCATION, RequestField.HOSPITAL, RequestField.URGENCY)), true)),
            Shot("request-blood-2-filled", content = form(at(filled.copy(step = 1)), true)),
            Shot("request-blood-2-hospital-suggestions", content = form(at(filled.copy(step = 1, hospitalName = "Dhaka")).copy(hospitals = hospitals), true)),
            Shot("request-blood-2-planned", content = form(at(filled.copy(step = 1, urgency = Urgency.PLANNED.code, plannedAtMillis = now.plus(Duration.ofDays(2)).toEpochMilli())), true)),
            Shot("request-blood-2-planned-missing-date", content = form(at(filled.copy(step = 1, urgency = Urgency.PLANNED.code)).copy(errors = setOf(RequestField.PLANNED_AT)), true)),
            Shot("request-blood-3-empty", content = form(at(RequestForm(step = 2, bloodGroup = "B_POS", division = "BD30", district = "BD3026", upazila = "BD30262000", hospitalName = hospital, urgency = Urgency.URGENT.code)), true)),
            Shot("request-blood-3-errors", content = form(at(filled.copy(step = 2, contactIsMe = false, contactName = "", contactPhone = "017", genuine = false)).copy(errors = setOf(RequestField.CONTACT_NAME, RequestField.CONTACT_PHONE, RequestField.GENUINE)), true)),
            // PERF-01, N2: "Me" (nothing to type) or "Someone else" (their name and number).
            Shot("request-blood-3-filled", content = form(at(filled.copy(step = 2)), true)),
            Shot("request-blood-3-someone-else", content = form(at(filled.copy(step = 2, contactIsMe = false, contactName = "Rahima Khatun", contactPhone = "01811-234567")), true)),
            Shot("request-blood-3-posting", animated = true, content = form(at(filled.copy(step = 2)).copy(posting = true), true)),
            Shot("request-blood-offline", content = form(at(filled.copy(step = 2)), false)),
            Shot("request-blood-loading", animated = true, content = form(CreateRequestUiState(RequestForm()), true)),
            Shot("request-blood-limit", content = form(at(RequestForm()).copy(limit = OpenRequestLimit(max = 3, newAccount = false)), true)),
            Shot("request-blood-limit-new-account", content = form(at(RequestForm()).copy(limit = OpenRequestLimit(max = 1, newAccount = true)), true)),
            // VT-02: typing a hospital, the list shows above the keyboard (the field near the top).
            Shot(
                "request-blood-2-hospital-typing",
                prepare = { tapOn(hasSetTextAction() and hasText("Dhaka medical", substring = true)); Thread.sleep(KEYBOARD_MS) },
                content = form(at(filled.copy(step = 1, hospitalName = "Dhaka medical", wardOrBed = "", urgency = null)).copy(hospitals = hospitals.take(1)), true),
            ),
            // VT-13: Done on Ward or bed closes the keyboard and shows "How soon?".
            Shot(
                "request-blood-2-ward-done",
                prepare = {
                    val ward = hasSetTextAction() and hasText("Ward 5", substring = true)
                    tapOn(ward)
                    Thread.sleep(KEYBOARD_MS)
                    compose.onNode(ward).performImeAction()
                    Thread.sleep(KEYBOARD_MS)
                },
                content = form(at(filled.copy(step = 1, urgency = null)), true),
            ),
            // VT-13: the note's keyboard has Done (not a new line), and Done shows the confirm box.
            Shot(
                "request-blood-3-notes-typing",
                prepare = { tapOn(hasSetTextAction() and hasText("Please call", substring = true)); Thread.sleep(KEYBOARD_MS) },
                content = form(at(filled.copy(step = 2, genuine = false)), true),
            ),
            Shot(
                "request-blood-3-notes-done",
                prepare = {
                    val notes = hasSetTextAction() and hasText("Please call", substring = true)
                    tapOn(notes)
                    Thread.sleep(KEYBOARD_MS)
                    compose.onNode(notes).performImeAction()
                    Thread.sleep(KEYBOARD_MS)
                },
                content = form(at(filled.copy(step = 2, genuine = false)), true),
            ),
            // VT-10: a phone number in the public note asks first.
            Shot("request-blood-phone-warning", overlay = true, prepare = { waitFor(R.string.phone_note_title) }) {
                val vm = remember { createRequestViewModel() }
                LaunchedEffect(vm) {
                    vm.uiState.first { it.loaded }
                    vm.edit { filled.copy(step = 2, notes = "Rafiq is with the patient: 01711-234567") }
                    vm.next()
                }
                CreateRequestRoute(onClose = {}, onPosted = { _, _ -> }, onOpenMyRequests = {}, viewModel = vm)
            },
            Shot("request-blood-money-warning", overlay = true, prepare = { waitFor(R.string.money_warning_title) }) {
                val vm = remember { createRequestViewModel() }
                LaunchedEffect(vm) {
                    vm.uiState.first { it.loaded }
                    vm.edit { filled.copy(step = 2, notes = "I will pay 2000 taka by bKash for each bag.") }
                    vm.next()
                }
                CreateRequestRoute(onClose = {}, onPosted = { _, _ -> }, onOpenMyRequests = {}, viewModel = vm)
            },
        )
    }

    private fun createRequestViewModel() = CreateRequestViewModel(
        SavedStateHandle(), ready(me), FakeProfileRepository(now).apply { profiles[me.uid] = me }, FakeRequestRepository(now),
        FakeHospitalRepository(), geoRepository, FakeWorkerClient(), clock, CoroutineScope(SupervisorJob() + Dispatchers.Main),
        FakeNetworkMonitor(), FakeAlertStore(), FakeReminders(),
    )

    // ---- Request details (the real screen on fakes) ----

    private val donorViewer = me.copy(uid = "donor")
    private val detailRequest = feed[0].copy(
        notes = "Thalassemia patient, needs blood every month. Please bring your donor card.", unitsNeeded = 2, hospitalAddress = "Bakshibazar, Dhaka",
    )

    private fun detailViewModel(
        viewer: UserProfile,
        request: BloodRequest = detailRequest,
        offer: ResponseStatus? = null,
        missing: Boolean = false,
        failing: Boolean = false,
        alert: AlertStatus? = null,
    ): RequestDetailViewModel {
        val requests = FakeRequestRepository(now).apply {
            if (!missing) put(request)
            contacts[request.id] = RequestContact("requester-1", "Karim Hossain", "+8801700000000", "Ayesha Begum", "Ward 5, bed 12")
            if (failing) listenFailure.value = RuntimeException("offline")
        }
        val offers = FakeOfferRepository(requests)
        if (offer != null) {
            runBlocking {
                offers.offer(request, donorViewer, "I can come at 4 pm")
                if (offer != ResponseStatus.OFFERED) {
                    offers.decide(requests.requests.value.getValue(request.id), offers.responses.value.getValue(request.id to donorViewer.uid), offer)
                }
            }
        }
        return RequestDetailViewModel(
            SavedStateHandle(mapOf("id" to request.id)), ready(viewer), requests, offers, FakeProfileRepository(now), FakeSafetyRepository(),
            FakeWorkerClient(), geoRepository, EligibilityCalculator(), clock, FakeNetworkMonitor(), FakeReminders(),
            alertStatuses = AlertStatusStore().apply { if (alert != null) set(request.id, alert) },
        )
    }

    private fun detail(
        viewer: UserProfile,
        request: BloodRequest = detailRequest,
        offer: ResponseStatus? = null,
        missing: Boolean = false,
        failing: Boolean = false,
        alert: AlertStatus? = null,
    ) =
        @Composable {
            val vm = remember { detailViewModel(viewer, request, offer, missing, failing, alert) }
            RequestDetailRoute(onBack = {}, viewModel = vm)
        }

    /**
     * MOTION-01: [content] with the "it worked" snackbar held on top, where the app's own host shows it
     * (the bottom of the window, above the navigation bar; on a request page it covers the bar for a few seconds).
     */
    private fun withSuccess(@StringRes text: Int, content: @Composable () -> Unit) =
        @Composable {
            val host = remember { SnackbarHostState() }
            val message = stringResource(text)
            LaunchedEffect(Unit) { host.showSnackbar(SuccessVisuals(message, SnackbarDuration.Indefinite)) }
            Box(Modifier.fillMaxSize()) {
                content()
                AppSnackbarHost(host, Modifier.align(Alignment.BottomCenter)) // VT-16: above the screen's own bars
            }
        }

    @Test
    fun requestDetails() {
        val loaded: Steps.() -> Unit = { waitForText(hospital) }
        val donor = donorViewer
        val requester = requesterProfile
        // A request no alert has gone out for yet (the Worker writes tier 1 or 3 when it sends).
        val unalerted = detailRequest.copy(fanoutTier = 0, nextTierAt = detailRequest.createdAt)
        gallery(
            Shot("details-donor", prepare = loaded, content = detail(donor)),
            Shot("details-donor-offer-sheet", overlay = true, scrollIn = { sheetWith(R.string.detail_check_title) },
                prepare = { loaded(); tap(R.string.detail_i_can_donate); waitFor(R.string.detail_check_title) }, content = detail(donor)),
            Shot("details-donor-offered", prepare = { loaded(); waitForText("Karim Hossain") }, content = detail(donor, offer = ResponseStatus.OFFERED)),
            Shot("details-donor-confirmed", prepare = { loaded(); waitForText("Karim Hossain") }, content = detail(donor, offer = ResponseStatus.CONFIRMED)),
            Shot("details-donor-donated", prepare = loaded, content = detail(donor, offer = ResponseStatus.DONATED)),
            Shot("details-donor-withdraw-dialog", overlay = true, prepare = { loaded(); waitForText("Karim Hossain"); tap(R.string.detail_withdraw_cant_go) },
                content = detail(donor, offer = ResponseStatus.OFFERED)),
            Shot("details-donor-menu", overlay = true, prepare = { loaded(); tapDescribed(R.string.action_more) }, content = detail(donor)),
            Shot("details-donor-report", overlay = true, prepare = { loaded(); tapDescribed(R.string.action_more); tap(R.string.detail_report) }, content = detail(donor)),
            Shot("details-donor-block", overlay = true, prepare = { loaded(); tapDescribed(R.string.action_more); tap(R.string.action_block_person) }, content = detail(donor)),
            Shot("details-donor-wrong-group", prepare = loaded, content = detail(donor.copy(bloodGroup = BloodGroup.A_POS))),
            // D23 a: the bar keeps the reason and Share; the way back is a card under the details.
            Shot("details-donor-weight-no", prepare = loaded, content = detail(donor.copy(weightOk = false, showInDonorSearch = false))),
            // D1: "I can donate" opens Become a donor for someone who isn't one yet.
            Shot("details-not-a-donor", prepare = loaded, content = detail(requesterOnly(donor))),
            Shot("details-donor-closed", prepare = loaded, content = detail(donor, detailRequest.copy(status = RequestStatus.FULFILLED, unitsFulfilled = 2))),
            Shot("details-requester-offer", prepare = { loaded(); waitForText("Nusrat") }, content = detail(requester, offer = ResponseStatus.OFFERED)),
            Shot("details-requester-confirmed", prepare = { loaded(); waitForText("Nusrat") }, content = detail(requester, offer = ResponseStatus.CONFIRMED)),
            // Not alerted 12 minutes after posting (the app couldn't send it): the Worker's next round will.
            Shot("details-requester-no-offers", prepare = loaded, content = detail(requester, unalerted)),
            // PERF-01: the page opens right after posting and shows the alert's progress.
            Shot("details-requester-alerting", prepare = loaded, content = detail(requester, unalerted.copy(createdAt = now), alert = AlertStatus.Sending)),
            Shot("details-requester-alerted", prepare = loaded, content = detail(requester, unalerted, alert = AlertStatus.Sent(1))),
            // MOTION-01: "Request posted" with its check, on the page the new request opens on.
            Shot("success-snackbar-posted", prepare = loaded,
                content = withSuccess(R.string.success_request_posted, detail(requester, unalerted.copy(createdAt = now), alert = AlertStatus.Sending))),
            // STATE-03 from the request itself (D's re-audit): alerted district-wide, and held back by the daily limit.
            Shot("details-requester-alerted-district", prepare = loaded, content = detail(requester, detailRequest.copy(fanoutTier = 3))),
            Shot("details-requester-held-back", prepare = loaded, content = detail(requester, unalerted.copy(nextTierAt = BloodRequest.ALERT_NEVER_DUE))),
            // P2-6 (D13 = a): Edit and Close request in the top bar's ⋮.
            Shot("details-requester-menu", overlay = true, prepare = { loaded(); tapDescribed(R.string.action_more) }, content = detail(requester)),
            Shot("details-requester-edit", overlay = true, prepare = { loaded(); tapDescribed(R.string.action_more); tap(R.string.action_edit) }, content = detail(requester)),
            Shot("details-requester-close", overlay = true, prepare = { loaded(); tapDescribed(R.string.action_more); tap(R.string.detail_close_request) }, content = detail(requester)),
            // FLOW-07: Decline is in the offer's ⋮ menu, away from Confirm.
            Shot("details-requester-offer-menu", overlay = true, prepare = { loaded(); waitForText("Nusrat"); tapDescribedStart(R.string.detail_more_about) },
                content = detail(requester, offer = ResponseStatus.OFFERED)),
            Shot("details-requester-decline", overlay = true,
                prepare = { loaded(); waitForText("Nusrat"); tapDescribedStart(R.string.detail_more_about); tap(R.string.detail_decline) },
                content = detail(requester, offer = ResponseStatus.OFFERED)),
            Shot("details-requester-mark-donated", overlay = true, prepare = { loaded(); waitForText("Nusrat"); tap(R.string.detail_mark_donated) },
                content = detail(requester, offer = ResponseStatus.CONFIRMED)),
            Shot("details-missing", prepare = { waitFor(R.string.detail_missing_title) }, content = detail(donor, missing = true)),
            Shot("details-error", content = detail(donor, failing = true)),
        )
    }

    // ---- Find donors ----

    @Test
    fun findDonors() {
        val filters = DonorFilters(groups = setOf(BloodGroup.B_POS), division = me.area.division, district = me.area.district)
        val screen = { state: FindDonorsUiState -> @Composable { FindDonorsScreen(state, FindDonorsActions(), now = now) } }
        val ready = FindDonorsUiState(enabled = true, filters = filters, geo = geo, me = me)
        val tab = TopLevelDestination.DONORS
        val myOpen = myRequests[0]
        // A second request the B+ donor can really give to (D's part 2, Low 29: it was B−, which a B+ donor can't).
        val other = myRequests[0].copy(id = "m4", bloodGroup = BloodGroup.AB_POS, acceptCompatible = true, acceptedGroups = BloodGroup.entries.toList(),
            hospitalName = "Shaheed Suhrawardy Medical College Hospital")
        val incompatible = myRequests[0].copy(id = "m5", bloodGroup = BloodGroup.A_NEG, acceptedGroups = listOf(BloodGroup.A_NEG))
        gallery(
            Shot("find-donors-filters", tab, content = screen(ready)),
            Shot("find-donors-filters-empty", tab, content = screen(ready.copy(filters = DonorFilters()))),
            // N3 / FLOW-06: no open request, said up front.
            Shot("find-donors-no-request", tab, content = screen(ready.copy(hasOpenRequest = false))),
            Shot("find-donors-loading", tab, animated = true, content = screen(FindDonorsUiState())),
            Shot("find-donors-off", tab, content = screen(FindDonorsUiState(enabled = false))),
            Shot("find-donors-searching", tab, animated = true, content = screen(ready.copy(applied = filters, searching = true))),
            Shot("find-donors-results", tab, content = screen(ready.copy(applied = filters, results = donorCards, total = donorCards.size.toLong(), endReached = true))),
            // VT-07: Rahim U. was asked for my request: his row says "Asked", not "Ask".
            Shot("find-donors-results-asked", tab, content = screen(ready.copy(applied = filters, results = donorCards, total = donorCards.size.toLong(), endReached = true, asked = setOf("m1:a")))),
            // FLOW-06 part 1: opened from "Ask donors near this hospital": searched, filters folded.
            Shot("find-donors-from-request", tab, content = screen(
                ready.copy(
                    filters = filters.copy(upazila = ready.me?.area?.upazila), applied = filters.copy(upazila = ready.me?.area?.upazila),
                    results = donorCards, total = donorCards.size.toLong(), endReached = true, handedSearches = 1,
                ),
            )),
            Shot("find-donors-no-results", tab, content = screen(ready.copy(applied = filters, results = emptyList(), total = 0, endReached = true))),
            Shot("find-donors-error", tab, content = screen(ready.copy(applied = filters, error = AppError.Network))),
            Shot("find-donors-ask", tab, overlay = true, prepare = { waitForText("Rahim U."); waitFor(R.string.action_ask_to_donate) }, content = askSheet(listOf(myOpen))),
            Shot("find-donors-ask-choose", tab, overlay = true, prepare = { waitForText("Rahim U."); waitFor(R.string.donors_ask_pick) }, content = askSheet(listOf(myOpen, other))),
            Shot("find-donors-ask-none-compatible", tab, overlay = true, prepare = { waitForText("Rahim U."); waitForStart(R.string.donors_ask_none_compatible) }, content = askSheet(listOf(incompatible))),
            Shot("find-donors-ask-no-requests", tab, overlay = true, prepare = { waitForText("Rahim U."); waitFor(R.string.donors_ask_no_requests) }, content = askSheet(emptyList())),
            Shot("find-donors-ask-report", tab, overlay = true, prepare = { waitForText("Rahim U."); tap(R.string.action_report_person) }, content = askSheet(listOf(myOpen))),
            Shot("find-donors-ask-block", tab, overlay = true, prepare = { waitForText("Rahim U."); tap(R.string.action_block_person) }, content = askSheet(listOf(myOpen))),
        )
    }

    /** Find donors with a donor's "Ask to donate" sheet open, with [mine] as my open requests. */
    private fun askSheet(mine: List<BloodRequest>) = @Composable {
        val vm = remember {
            FindDonorsViewModel(
                ready(me), FakeDonorRepository().apply { cards.addAll(donorCards) }, FakeRequestRepository(now).apply { mine.forEach { put(it) } },
                FakeSafetyRepository(), FakeProfileRepository(now), FakeWorkerClient(), FakeFeatureFlags(), geoRepository, EligibilityCalculator(), clock,
            )
        }
        LaunchedEffect(vm) {
            vm.uiState.first { it.me != null }
            vm.openAsk(donorCards[0])
        }
        FindDonorsRoute(onCreateRequest = {}, viewModel = vm)
    }

    // ---- Profile ----

    @Test
    fun profile() {
        val screen = { state: ProfileUiState -> @Composable { ProfileScreen(state, {}, {}, {}, {}, {}, now = now) } }
        val newUser = ProfileUiState(profile = me, eligibility = eligibility(me), donations = emptyList(), geo = geo)
        val tab = TopLevelDestination.PROFILE
        gallery(
            Shot("profile-filled", tab, content = profileRoute(donorMe)),
            Shot("profile-new-user", tab, content = screen(newUser)),
            Shot("profile-not-donor", tab, content = screen(ProfileUiState(profile = requesterOnly(me), eligibility = null, donations = emptyList(), geo = geo))),
            Shot("profile-loading", tab, animated = true, content = screen(ProfileUiState())),
            Shot("profile-history-error", tab, content = screen(newUser.copy(donations = null, donationsFailed = true))),
            Shot("profile-not-listed", tab, content = screen(newUser.copy(profile = me.copy(showInDonorSearch = false)))),
            Shot("profile-no-blood-group", tab, content = screen(newUser.copy(profile = me.copy(bloodGroup = null, showInDonorSearch = false)))),
            Shot("profile-no-blood-group-help", tab, overlay = true, prepare = { tap(R.string.action_how_to_find_out) },
                content = screen(newUser.copy(profile = me.copy(bloodGroup = null, showInDonorSearch = false)))),
            Shot("profile-add-donation-sheet", tab, overlay = true, scrollIn = { sheetWith(R.string.action_save) },
                prepare = { waitForText("Nusrat Jahan"); tap(R.string.action_add_past_donation) }, content = profileRoute(donorMe)),
            Shot("profile-remove-donation", tab, overlay = true, prepare = { waitForText("Nusrat Jahan"); tapDescribed(R.string.action_remove_donation) },
                content = profileRoute(donorMe)),
            Shot("profile-update-eligibility", tab, overlay = true, prepare = { waitFor(R.string.add_donation_update_title) },
                content = profileRoute(me, add = DonationDraft(now.minus(Duration.ofDays(10)), BloodComponent.WHOLE_BLOOD, hospital))),
        )
    }

    /** The real Profile on fakes; [add] records a donation, which asks "Update your eligibility?". */
    private fun profileRoute(p: UserProfile, add: DonationDraft? = null) = @Composable {
        val vm = remember {
            val profiles = FakeProfileRepository(now).apply {
                profiles[p.uid] = p
                donationList[p.uid] = (if (p.donationCount > 0) history else emptyList()).toMutableList()
            }
            ProfileViewModel(ready(p), profiles, geoRepository, EligibilityCalculator(), clock)
        }
        if (add != null) {
            LaunchedEffect(vm) {
                vm.uiState.first { it.donations != null }
                vm.addDonation(add)
            }
        }
        ProfileRoute(onEditProfile = {}, onOpenSettings = {}, viewModel = vm)
    }

    // ---- Settings and everything under it ----

    @Test
    fun settings() {
        val state = SettingsUiState(hasPassword = true, listed = true, available = true, bloodGroup = BloodGroup.B_POS, email = "karim@example.com")
        val screen = { s: SettingsUiState ->
            @Composable {
                val language = if (currentAppLocale() == AppLocale.BN) AppLanguage.BANGLA else AppLanguage.ENGLISH
                SettingsContent(
                    uiState = s.copy(language = language), onBack = {}, onThemeModeChange = {}, onOpenComponentGallery = {}, onEditProfile = {},
                    onChangePassword = {}, onDeleteAccount = {}, onSignOut = {}, showDeveloperOptions = false,
                )
            }
        }
        val blocked = listOf(BlockedPerson("a", "Rahim U."), BlockedPerson("b", "Sadia A."), BlockedPerson("c", null))
        gallery(
            Shot("settings", content = screen(state)),
            Shot("settings-google-account", content = screen(state.copy(hasPassword = false))),
            Shot("settings-not-listed", content = screen(state.copy(listed = false, available = false))),
            Shot("settings-no-blood-group", content = screen(state.copy(listed = false, available = false, bloodGroup = null))),
            Shot("settings-admin", content = screen(state.copy(admin = true))),
            Shot("settings-not-donor", content = screen(state.copy(isDonor = false, listed = false, available = false, bloodGroup = null))),
            Shot("settings-loading", content = screen(state.copy(listed = null))),
            Shot("settings-list-me-dialog", overlay = true, prepare = { tap(R.string.settings_list_me) }, content = screen(state.copy(listed = false, available = false))),
            // N5: the check after tapping Sign out in the corner, and the resting line under Ready to donate.
            Shot("settings-sign-out-dialog", overlay = true, prepare = { tap(R.string.action_sign_out) }, content = screen(state)),
            // MOTION-01: the "it worked" snackbar after "List me" (held on screen for the picture).
            Shot("success-snackbar-listed", content = withSuccess(R.string.success_listed, screen(state))),
            Shot("settings-resting", content = screen(state.copy(alertsAgainOn = now.plus(Duration.ofDays(80))))),
            Shot("notification-settings") { NotificationPrefsScreen(NotificationPrefsUiState(loaded = true), systemEnabled = true, onChange = {}, onOpenSystemSettings = {}, onBack = {}) },
            Shot("notification-settings-phone-off") {
                NotificationPrefsScreen(NotificationPrefsUiState(loaded = true), systemEnabled = false, onChange = {}, onOpenSystemSettings = {}, onBack = {})
            },
            Shot("blocked-people") { BlockedPeopleScreen(BlockedPeopleUiState(people = blocked), onUnblock = {}, onBack = {}) },
            Shot("blocked-people-empty") { BlockedPeopleScreen(BlockedPeopleUiState(people = emptyList()), onUnblock = {}, onBack = {}) },
            Shot("blocked-people-loading", animated = true) { BlockedPeopleScreen(BlockedPeopleUiState(), onUnblock = {}, onBack = {}) },
            Shot("safety-tips") { SafetyTipsScreen(onBack = {}, onCallEmergency = {}) },
            Shot("about") {
                AboutScreen(BuildConfig.VERSION_NAME, onBack = {}, onOpenPrivacy = {}, onOpenTerms = {}, onOpenDeletionPage = {}, onOpenMedicalDisclaimer = {},
                    onOpenHowMatchingWorks = {}, onOpenLicences = {}, onOpenSourceCode = {}, onContact = {}, onSendFeedback = {})
            },
            Shot("medical-disclaimer") { MedicalDisclaimerScreen(onBack = {}) },
            Shot("how-matching-works") { HowMatchingWorksScreen(onBack = {}) },
            Shot("licences") { LicencesScreen(onBack = {}) },
        )
    }

    @Test
    fun account() {
        val signedIn = {
            FakeAuthRepository().apply {
                accounts["nusrat@example.test"] = FakeAuthRepository.Account("me", "secret123", "Nusrat Jahan", verified = true)
                runBlocking { signInEmail("nusrat@example.test", "secret123") }
            }
        }
        val editProfileOf = { p: UserProfile, change: (EditProfileViewModel) -> Unit ->
            @Composable {
                val vm = remember { EditProfileViewModel(ready(p), FakeProfileRepository(now).apply { profiles[p.uid] = p }, signedIn(), geoRepository, clock, EligibilityCalculator()) }
                LaunchedEffect(vm) { change(vm) }
                EditProfileRoute(onDone = {}, viewModel = vm)
            }
        }
        val editProfile = { change: (EditProfileViewModel) -> Unit -> editProfileOf(me, change) }
        // D23 a: a donor who answered under 45 kg (so not listed).
        val underWeight = me.copy(weightOk = false, showInDonorSearch = false)
        val deleting = { failWith: AppError? ->
            @Composable {
                val vm = remember {
                    val auth = signedIn()
                    DeletingAccountViewModel(auth, FakeAccountDeleter().apply { this.failWith = failWith }, FakeSessionManager(SessionState.DeletingAccount(auth.authState.value!!)))
                }
                DeletingAccountRoute(viewModel = vm)
            }
        }
        gallery(
            Shot("edit-profile", prepare = { waitForText("Nusrat Jahan") }, content = editProfile {}),
            // D23 a: the weight question, answered না; after হ্যাঁ and Save, "list me" again.
            Shot("edit-profile-weight-no", prepare = { waitForText("Nusrat Jahan") }, content = editProfileOf(underWeight) {}),
            Shot("edit-profile-list-me-again", overlay = true, prepare = { waitFor(R.string.consent_yes) }, content = editProfileOf(underWeight) { vm ->
                vm.edit { copy(weightOk = true) }
                vm.save()
            }),
            Shot("edit-profile-errors", prepare = { waitForText("1880") }, content = editProfile { vm ->
                vm.edit { copy(fullName = "", birthYear = "1880", phone = "0170") }
                vm.save()
            }),
            Shot("edit-profile-change-email", overlay = true, prepare = { waitForText("Nusrat Jahan"); tap(R.string.action_change_email) }, content = editProfile {}),
            Shot("edit-profile-confirm-blood-group", overlay = true, prepare = { waitFor(R.string.confirm_blood_group_title) }, content = editProfile { vm ->
                vm.edit { copy(bloodGroup = BloodGroup.A_POS) }
                vm.save()
            }),
            Shot("change-password") { ChangePasswordRoute(onDone = {}, viewModel = remember { ChangePasswordViewModel(signedIn()) }) },
            Shot("change-password-errors") {
                val vm = remember { ChangePasswordViewModel(signedIn()) }
                LaunchedEffect(vm) { vm.onNewChange("123"); vm.save() }
                ChangePasswordRoute(onDone = {}, viewModel = vm)
            },
            // D24: the view model's own steps to each state, as typing makes them.
            *passwordStates.map { (state, password) ->
                Shot("change-password-$state") {
                    val vm = remember { ChangePasswordViewModel(signedIn()) }
                    LaunchedEffect(vm) { vm.typeTo(password) }
                    ChangePasswordRoute(onDone = {}, viewModel = vm)
                }
            }.toTypedArray(),
            Shot("add-password") { AddPasswordScreen(AddPasswordUiState(email = "nusrat@gmail.example"), onPassword = {}, onConfirm = {}, onAdd = {}, onBack = {}) },
            Shot("add-password-errors") {
                AddPasswordScreen(
                    AddPasswordUiState(email = "nusrat@gmail.example", password = NewPassword("123", "1234").check()),
                    onPassword = {}, onConfirm = {}, onAdd = {}, onBack = {},
                )
            },
            *passwordStates.map { (state, password) ->
                Shot("add-password-$state") {
                    AddPasswordScreen(AddPasswordUiState(email = "nusrat@gmail.example", password = password), onPassword = {}, onConfirm = {}, onAdd = {}, onBack = {})
                }
            }.toTypedArray(),
            Shot("delete-account") {
                DeleteAccountRoute(onBack = {}, viewModel = remember { DeleteAccountViewModel(signedIn(), FakeAccountDeleter(), ready(me)) })
            },
            Shot("delete-account-errors") {
                val vm = remember { DeleteAccountViewModel(signedIn(), FakeAccountDeleter(), ready(me)) }
                LaunchedEffect(vm) { vm.onWordChange("delet"); vm.confirm() }
                DeleteAccountRoute(onBack = {}, viewModel = vm)
            },
            Shot("deleting-account", animated = true, content = deleting(null)),
            Shot("deleting-account-error", content = deleting(AppError.Network)),
            Shot("deleting-account-needs-password", content = deleting(AppError.RecentLoginRequired)),
        )
    }

    @Test
    fun inboxAndModeration() {
        val entries = listOf(
            InboxEntry.Update(InboxItem("i1", InboxType.NEW_OFFER, "d1", "m1", now.minusSeconds(60))),
            InboxEntry.Alert(LocalAlert("r2", stillNeeded = true, bloodGroup = "O_NEG", district = "BD3026", urgency = "CRITICAL", receivedAtMillis = now.minusSeconds(600).toEpochMilli())),
            InboxEntry.Update(InboxItem("i3", InboxType.DONATION_RECORDED, "r", "o3", now.minusSeconds(7200))),
            InboxEntry.Alert(LocalAlert("r9", stillNeeded = false, bloodGroup = "B_POS", district = "BD3026", urgency = "URGENT", receivedAtMillis = now.minusSeconds(90_000).toEpochMilli())),
        )
        val inbox = { s: InboxUiState -> @Composable { InboxScreen(s, onBack = {}, onOpenRequest = {}, onLoadMore = {}, onOpenSettings = {}) } }
        val report = { id: String, type: ReportTarget, target: String, reason: ReportReason, note: String? ->
            Report(id, "rep", type, target, reason, note, now.minus(Duration.ofHours(2)), ReportStatus.OPEN)
        }
        val items = listOf(
            ModerationItem(report("1", ReportTarget.REQUEST, "r1", ReportReason.ASKING_MONEY, "Asked for 2000 taka by bKash"),
                request = feed[0].copy(flagged = true, reportCount = 2)),
            ModerationItem(report("2", ReportTarget.USER, "pest", ReportReason.entries.last(), null), personName = "Pest P."),
        )
        val moderation = { s: ModerationUiState -> @Composable { ModerationScreen(s, onBack = {}, onRefresh = {}, onOpenRequest = {}, onDo = { _, _, _ -> }, now = now) } }
        gallery(
            Shot("inbox", content = inbox(InboxUiState(entries = entries, seenAt = now.minusSeconds(3600), geo = geo))),
            Shot("inbox-empty", content = inbox(InboxUiState(entries = emptyList(), seenAt = now, geo = geo))),
            Shot("inbox-loading", animated = true, content = inbox(InboxUiState(geo = geo))),
            Shot("inbox-error", content = inbox(InboxUiState(error = R.string.error_network, geo = geo))),
            Shot("moderation", content = moderation(ModerationUiState(admin = true, items = items))),
            Shot("moderation-empty", content = moderation(ModerationUiState(admin = true, items = emptyList()))),
            Shot("moderation-loading", animated = true, content = moderation(ModerationUiState(admin = true))),
            Shot("moderation-not-admin", content = moderation(ModerationUiState(admin = false))),
        )
    }

    private companion object {
        const val MAX_PAGES = 12
        const val SETTLE_MS = 250L

        /** The keyboard opening or closing, and what scrolls after it. */
        const val KEYBOARD_MS = 1_500L
        const val OVERLAY_SETTLE_MS = 600L
        const val SENDER = "noreply@liquidlyf-app.firebaseapp.com"
    }
}
