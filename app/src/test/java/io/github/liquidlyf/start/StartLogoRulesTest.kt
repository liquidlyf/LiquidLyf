package io.github.liquidlyf.start

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D25: the start logo's times, the way it leaves for each first screen, and the move's geometry. */
class StartLogoRulesTest {

    @Test
    fun `the maintainer's times - the drop at 1,3 s, the line at 1,5 s (A45 b), the still picture held at most 0,9 s (A46)`() {
        assertEquals(1_300L, StartClock.DROP_MS)
        assertEquals(1_500L, StartClock.LINE_MS)
        assertEquals(900L, StartClock.HOLD_MS)
    }

    @Test
    fun `the render-thread drop and line take the generated delay nearest the one wanted`() {
        val drop = listOf(0, 200, 400)
        assertEquals(400, StartViews.nearest(1_300L - 900L, drop)) // drawn at the hold's end
        assertEquals(200, StartViews.nearest(1_300L - 1_150L, drop))
        assertEquals(0, StartViews.nearest(1_300L - 4_200L, drop)) // a late first frame: at once
        assertEquals(600, StartViews.nearest(1_500L - 900L, listOf(0, 300, 600)))
    }

    @Test
    fun `the drop's level, as the drawable draws it - empty at 45% of the cycle, full again at its end`() {
        assertEquals(0f, StartViews.drained(-50L), 0f)
        assertEquals(0f, StartViews.drained(0L), 0f)
        assertEquals(1f, StartViews.drained(720L), 1e-4f)
        assertEquals(0f, StartViews.drained(1_600L), 1e-4f)
        assertEquals(0.5f, StartViews.drained(360L), 1e-3f) // half way down, eased
        assertEquals(StartViews.drained(360L), StartViews.drained(1_600L + 360L), 1e-4f) // it repeats
    }

    @Test
    fun `the clock counts from the tap, the process's start, when the process is new`() {
        var uptime = 50_400L
        val clock = StartClock.begin(processStart = 50_000L, now = 50_300L, uptime = { uptime })
        assertEquals(400L, clock.now())
        assertFalse(clock.holdOver())
        uptime = 50_900L
        assertTrue(clock.holdOver())
    }

    @Test
    fun `a process already running (a push woke it, or Back closed the app) counts from now`() {
        val clock = StartClock.begin(processStart = 0L, now = 600_000L, uptime = { 600_250L })
        assertEquals(250L, clock.now())
    }

    @Test
    fun `glide into the welcome page and Sign in, shrink into Home, fade on any other screen`() {
        assertEquals(StartExit.GLIDE, StartScreen.WELCOME.exit())
        assertEquals(StartExit.GLIDE, StartScreen.SIGN_IN.exit())
        assertEquals(StartExit.SHRINK, StartScreen.HOME.exit())
        assertEquals(StartExit.FADE, StartScreen.OTHER.exit())
    }

    @Test
    fun `the move starts on the still picture and ends exactly on the screen's logo`() {
        val start = Placement(0.32f, Offset(-20f, 673f))
        val target = Placement.of(Rect(166f, 470f, 556f, 750.6f), width = 1087.41f)
        val centre = ART_BOX.center
        assertEquals(start, start.lerp(target, 0f, centre))
        val end = start.lerp(target, 1f, centre)
        assertEquals(target.scale, end.scale, 1e-5f)
        assertEquals(target.offset.x, end.offset.x, 1e-3f)
        assertEquals(target.offset.y, end.offset.y, 1e-3f)
        // Half way, the logo's centre is half way along a straight line.
        val half = start.lerp(target, 0.5f, centre)
        val mid = (start.offset + centre * start.scale + target.offset + centre * target.scale) / 2f
        val at = half.offset + centre * half.scale
        assertEquals(mid.x, at.x, 1e-3f)
        assertEquals(mid.y, at.y, 1e-3f)
    }

    @Test
    fun `a part placed in the top-bar logo, then the top bar on the screen`() {
        val markInBar = Placement(0.67596f, Offset(-192.45f, -27.02f))
        val bar = Placement.of(Rect(48f, 64f, 312.5f, 120f), width = 1417.6f)
        val both = bar.then(markInBar)
        val point = Offset(543.71f, 484f) // the heart's tip
        val direct = bar.offset + (markInBar.offset + point * markInBar.scale) * bar.scale
        val composed = both.offset + point * both.scale
        assertEquals(direct.x, composed.x, 1e-3f)
        assertEquals(direct.y, composed.y, 1e-3f)
    }
}
