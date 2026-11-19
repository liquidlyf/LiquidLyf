package io.github.liquidlyf.start

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.liquidlyf.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * D25, A46: while a slow start builds its first screen, the main thread is busy (1.5 s on a debug
 * build), and the drop and the loading line must keep moving then. They're animated vector drawables,
 * which Android (7.1+) runs on its render thread. This blocks the main thread for 2.5 s and checks,
 * on screenshots, that both still change. Skipped where the phone has view animations off (the app
 * shows still words there).
 */
@RunWith(AndroidJUnit4::class)
class StartViewsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theDropAndTheLineKeepMovingWhileTheMainThreadIsBusy() {
        assumeTrue("view animations are off on this phone", ValueAnimator.areAnimatorsEnabled())
        val activity = compose.activity
        lateinit var views: StartViews
        // A slow start, 1.6 s after the tap: the drop and the line start at once.
        val clock = StartClock(SystemClock.uptimeMillis() - 1_600)
        compose.runOnUiThread { views = StartViews(activity, clock, phoneDark = false) }
        compose.waitForIdle()
        compose.runOnUiThread { views.show(dark = false) }
        Thread.sleep(600) // the line fades in
        // TalkBack: "Loading…", once, as the line appears (a polite live region).
        compose.runOnUiThread {
            assertEquals(activity.getString(R.string.state_loading), views.loadingLine.contentDescription)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, views.loadingLine.accessibilityLiveRegion)
        }

        val content = activity.findViewById<View>(android.R.id.content)
        val at = IntArray(2).also(content::getLocationOnScreen)
        val cx = at[0] + content.width / 2
        val cy = at[1] + content.height / 2
        val dp = activity.resources.displayMetrics.density

        // The app builds its first screen: nothing else runs on the main thread for 2.5 s.
        activity.runOnUiThread { Thread.sleep(BUSY_MS) }
        Thread.sleep(150)
        val drops = mutableListOf<Int>()
        val bars = mutableListOf<String>()
        repeat(SHOTS) {
            val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            // The drop's red, down its middle (the drop spans 33 dp above the centre to 1 dp below).
            drops += (cy - (35 * dp).roundToInt()..cy + (3 * dp).roundToInt()).count { y -> shot.isRed(cx, y) }
            // Where the line's red bar is (the line is 84 dp below the centre, 120 dp wide).
            val y = cy + (84 * dp).roundToInt()
            val red = (cx - (60 * dp).roundToInt()..cx + (60 * dp).roundToInt()).filter { x -> shot.isRed(x, y) }
            bars += if (red.isEmpty()) "none" else "${red.first() - cx}..${red.last() - cx}"
            shot.recycle()
            Thread.sleep(GAP_MS)
        }
        Log.i("StartViewsTest", "while the main thread was busy: drop $drops; bar $bars")
        compose.runOnUiThread { views.remove() }
        assertTrue("The drop stood still while the main thread was busy: $drops", drops.distinct().size >= 3)
        assertTrue("The line stood still while the main thread was busy: $bars", bars.distinct().size >= 3)
    }

    private fun Bitmap.isRed(x: Int, y: Int): Boolean {
        val c = getPixel(x, y)
        return Color.red(c) > 140 && Color.green(c) < 90 && Color.blue(c) < 90
    }

    private companion object {
        const val BUSY_MS = 2_500L
        const val SHOTS = 6
        const val GAP_MS = 250L
    }
}
