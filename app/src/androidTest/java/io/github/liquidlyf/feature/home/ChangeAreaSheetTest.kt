package io.github.liquidlyf.feature.home

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarClearance
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.SnackbarClearance
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D27 on the phone: Home's empty state says which area "near you" is and opens the small "Change
 * area" sheet; the sheet starts on the profile's area, says what's missing when the area is
 * incomplete, saves the area picked, and Cancel closes it. (Bangla and large text: the gallery.)
 */
@RunWith(AndroidJUnit4::class)
class ChangeAreaSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val geo: BdGeo = context.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) }
    private val me = TestData.profile(uid = "me") // B+, Dhaka South City Corporation

    private fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun nameOf(code: String): String =
        geo.divisions.firstOrNull { it.code == code }?.name?.localized(AppLocale.EN)
            ?: geo.divisions.flatMap { it.districts }.firstOrNull { it.code == code }?.name?.localized(AppLocale.EN)
            ?: geo.divisions.flatMap { it.districts }.flatMap { it.areas }.first { it.code == code }.name.localized(AppLocale.EN)

    /** Jashore and one of its upazilas: another division and district than mine. */
    private val jashore: Area
        get() {
            val division = geo.divisions.first { d -> d.districts.any { it.code == "BD4041" } }
            return Area(division.code, "BD4041", division.districts.first { it.code == "BD4041" }.areas.first().code)
        }

    /** Opens a box (its label sits above it, so it's found by its description) and picks [item]. */
    private fun pick(@StringRes field: Int, item: String) {
        val box = compose.onNode((hasText(str(field)) or hasContentDescription(str(field), substring = true)) and hasClickAction())
        runCatching { box.performScrollTo() }
        box.performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(item).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(item).onLast().performClick()
        compose.waitForIdle()
    }

    private fun tap(@StringRes label: Int) {
        compose.onNode(hasText(str(label)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun homesEmptyStateSaysTheAreaAndOpensTheSheet() {
        var opened = 0
        compose.setContent {
            // As AppNavHost: Home's messages need the app's snackbar host.
            CompositionLocalProvider(
                LocalSnackbarHostState provides remember { SnackbarHostState() },
                LocalSnackbarClearance provides remember { SnackbarClearance() },
            ) {
                LiquidLyfTheme(darkTheme = false) {
                    HomeScreen(
                        HomeUiState(profile = me, eligibility = EligibilityCalculator().evaluate(me, TestData.NOW), available = true, requests = emptyList(), geo = geo),
                        online = true, onAvailableToggled = {}, onOpenRequest = {}, onCreateRequest = {}, onOpenNotifications = {}, onEditProfile = {},
                        notificationsEnabled = true, onChangeArea = { opened++ },
                    )
                }
            }
        }
        val place = geo.placeLabel(me.area.district, me.area.upazila, AppLocale.EN)
        compose.onNode(hasText(str(R.string.become_donor_area, place))).performScrollTo().assertIsDisplayed()
        tap(R.string.home_change_area)
        assertEquals(1, opened)
    }

    @Test
    fun theSheetStartsOnMyAreaSaysWhatsMissingAndSavesTheAreaPicked() {
        val saved = mutableListOf<Area>()
        var dismissed = 0
        compose.setContent {
            LiquidLyfTheme(darkTheme = false) {
                ChangeAreaSheet(geo, AppLocale.EN, me.area, AreaSheetState(open = true), onSave = { saved += it }, onDismiss = { dismissed++ })
            }
        }
        // My area, already chosen, and what the sheet changes.
        compose.onNode(hasText(str(R.string.home_change_area_line))).assertIsDisplayed()
        compose.onNode(hasText(nameOf(me.area.upazila), substring = true)).assertExists()

        // Another division empties the boxes below it: Save says what's missing and saves nothing.
        val target = jashore
        pick(R.string.label_division, nameOf(target.division))
        tap(R.string.action_save)
        compose.onNode(hasText(str(R.string.error_location_required))).assertIsDisplayed()
        assertTrue(saved.isEmpty())

        pick(R.string.label_district, nameOf(target.district))
        pick(R.string.label_upazila, nameOf(target.upazila))
        tap(R.string.action_save)
        assertEquals(listOf(target), saved)

        tap(R.string.action_cancel)
        assertEquals(1, dismissed)
    }

    @Test
    fun aFailedSaveShowsInsideTheSheet() {
        compose.setContent {
            LiquidLyfTheme(darkTheme = false) {
                ChangeAreaSheet(geo, AppLocale.EN, me.area, AreaSheetState(open = true, error = R.string.error_network), onSave = {}, onDismiss = {})
            }
        }
        compose.onNode(hasText(str(R.string.error_network))).assertIsDisplayed()
    }
}
