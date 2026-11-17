package io.github.liquidlyf.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The journeys whose code goes into the Baseline Profile (phase-09: startup and Home scroll):
 * a cold start, then, signed in, scrolling Home and the Requests feed. Run by
 * `:app:generateBaselineProfile` in CI only (README.md), on the demo build against
 * the Firebase emulators with the seed data, as the demo B+ donor in Dhaka (password from
 * firebase/seed/README.md; emulator-only, not a secret).
 *
 * The screens are found by their English text, so the emulator must be set to English (CI's is).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    private val app: String = InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId isn't set (baselineprofile/build.gradle.kts sets it)")

    @Test
    fun startupAndScrolling() = rule.collect(packageName = app, includeInStartupProfile = true, maxIterations = 8) {
        // The notification question would come between sign-in and Home: answer it up front.
        device.executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        pressHome()
        startActivityAndWait()
        signInIfNeeded()

        scrollFeed()
        tab("Requests")
        scrollFeed()
        tab("Home")
    }

    /** The first run signs in; later runs open straight on Home (the app keeps its session). */
    private fun MacrobenchmarkScope.signInIfNeeded() {
        val first = device.wait(Until.findObject(By.text(Pattern.compile("Requests|Find donors near you"))), LONG)
            ?: error("neither Home nor onboarding appeared")
        if (first.text == "Requests") return
        click("Skip")
        click("I understand")
        val fields = device.wait(Until.findObjects(By.clazz("android.widget.EditText")), LONG)
        check(fields != null && fields.size >= 2) { "the sign-in form didn't appear" }
        fields[0].text = "demo-donor-bpos@example.test"
        fields[1].text = "password123"
        hideKeyboard()
        // The screen title and the button both say "Sign in": the button is the lower one.
        device.findObjects(By.text("Sign in")).maxBy { it.visibleBounds.top }.click()
        check(device.wait(Until.hasObject(By.text("Requests")), LONG)) { "Home didn't open after signing in" }
    }

    private fun MacrobenchmarkScope.scrollFeed() {
        fling(Direction.DOWN)
        fling(Direction.DOWN)
        fling(Direction.UP)
    }

    /**
     * One fling on the feed: the tallest scrollable (Requests also has a tab row). The list is found
     * again for every fling, because it can be replaced while it loads or while the screen changes:
     * CI's first run (18 Nov 2026) flung a list that had just gone (StaleObjectException).
     */
    private fun MacrobenchmarkScope.fling(direction: Direction) {
        repeat(STALE_TRIES) {
            device.waitForIdle()
            try {
                val list = device.wait(Until.findObjects(By.scrollable(true)), LONG)
                    ?.maxByOrNull { it.visibleBounds.height() } ?: return
                list.setGestureMargin(device.displayWidth / 5)
                list.fling(direction)
                device.waitForIdle()
                return
            } catch (_: StaleObjectException) {
                // Replaced under us: look for it again.
            }
        }
        error("the feed kept changing under the fling")
    }

    /** The bottom bar's item: the lowest node with that text. */
    private fun MacrobenchmarkScope.tab(label: String) {
        device.wait(Until.findObjects(By.text(label)), LONG)?.maxBy { it.visibleBounds.top }?.click()
            ?: error("no $label tab")
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.click(text: String) {
        val node: UiObject2 = device.wait(Until.findObject(By.text(text)), LONG) ?: error("no \"$text\" on screen")
        node.click()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.hideKeyboard() {
        if ("mInputShown=true" in device.executeShellCommand("dumpsys input_method")) {
            device.pressBack()
            device.waitForIdle()
        }
    }

    private companion object {
        /** CI's emulator and the Firebase emulators share a 4-core runner: be patient. */
        const val LONG = 30_000L

        /** How often a fling looks for the feed again when it was replaced. */
        const val STALE_TRIES = 3
    }
}
