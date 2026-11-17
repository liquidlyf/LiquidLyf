package io.github.liquidlyf.feature.request.create

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeoParser
import io.github.liquidlyf.core.designsystem.LiquidLyfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FLOW-04 on a real screen: on step 2 of Request blood the "how soon" error is below the fold at
 * normal text size (D's gallery); a failed Next brings it into view.
 */
@RunWith(AndroidJUnit4::class)
class FormErrorScrollTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val geo = context.assets.open("bd-geo.json").bufferedReader().use { BdGeoParser.parse(it.readText()) }

    @Test
    fun a_failed_next_brings_the_first_error_into_view() {
        val errors = setOf(RequestField.URGENCY)
        var state by mutableStateOf(CreateRequestUiState(RequestForm(step = 1), loaded = true, geo = geo, errors = errors))
        compose.setContent {
            LiquidLyfTheme(darkTheme = false) {
                CreateRequestScreen(state, online = true, {}, {}, {}, {}, { _, _ -> }, {}, {}, {})
            }
        }
        val message = context.getString(R.string.create_error_urgency)
        // Shown as it was before the fix: the error exists but is off-screen.
        compose.onNodeWithText(message).assertIsNotDisplayed()

        state = state.copy(errorAttempt = 1)
        compose.waitForIdle()
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    @Test
    fun a_failed_post_brings_the_genuine_tick_into_view() {
        // Step 3: "Post request" is pinned, but the tick and its error were below the fold.
        var state by mutableStateOf(CreateRequestUiState(RequestForm(step = 2), loaded = true, geo = geo, errors = setOf(RequestField.GENUINE)))
        compose.setContent {
            LiquidLyfTheme(darkTheme = false) {
                CreateRequestScreen(state, online = true, {}, {}, {}, {}, { _, _ -> }, {}, {}, {})
            }
        }
        val message = context.getString(R.string.create_error_genuine)
        compose.onNodeWithText(message).assertIsNotDisplayed()

        state = state.copy(errorAttempt = 1)
        compose.waitForIdle()
        compose.onNodeWithText(message).assertIsDisplayed()
    }
}
