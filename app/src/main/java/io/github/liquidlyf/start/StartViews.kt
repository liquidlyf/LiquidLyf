package io.github.liquidlyf.start

import android.app.Activity
import android.graphics.drawable.Animatable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.EaseInOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.liquidlyf.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * D25, A46: the drop draining and the thin loading line while a slow start loads, as animated
 * vector drawables in plain Views over the app. Android (7.1+) runs those on its render thread, so
 * they keep moving while the app builds its first screen, which keeps the main thread busy for up to
 * 0.85 s just after installing and 1.5 s on a debug build (A's measure, 20 Nov 2026); [StartLogo]'s
 * own drop and line would stand still then. [StartLogo] leaves out the drop while these show, and
 * takes it over where it is at the move.
 *
 * Added hidden when the activity starts, so they're laid out by the app's first frame; [show] starts
 * them then. An animated vector drawable's delay is fixed, so the drawables come with a few delays
 * (app/scripts/generate_launcher_icons.py) and [show] takes the nearest. Not used with "Remove
 * animations", which shows still words instead ([animationsOff]).
 */
class StartViews(
    private val activity: Activity,
    private val clock: StartClock,
    phoneDark: Boolean,
    /** Still loading: once the start data is read, the first screen is coming, so TalkBack needn't hear "Loading…". */
    private val loading: () -> Boolean = { true },
) {

    /** Showing: [StartLogo] doesn't draw the drop then (this one does). */
    var active: Boolean by mutableStateOf(false)
        private set

    /** The clock time the drop starts to drain. */
    private var drainFrom = Long.MAX_VALUE

    private val drop = ImageView(activity).apply {
        setImageResource(dropDrawable(DROP_DELAYS.last()))
        scaleType = ImageView.ScaleType.FIT_XY
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val line = ImageView(activity).apply {
        setImageResource(lineDrawable(phoneDark, LINE_DELAYS.last()))
        scaleType = ImageView.ScaleType.FIT_XY
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val root = FrameLayout(activity).apply {
        visibility = View.INVISIBLE
        // The drop sits on the still picture's drop: 288 dp square, centred, as Android draws it.
        addView(drop, FrameLayout.LayoutParams(dp(SPLASH_DP), dp(SPLASH_DP), Gravity.CENTER))
        addView(
            line,
            FrameLayout.LayoutParams(dp(LINE_WIDTH_DP), dp(LINE_HEIGHT_DP), Gravity.CENTER).apply { topMargin = dp(LINE_BELOW_CENTRE_DP) },
        )
    }

    init {
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * The app's first frame, still loading: start both, timed from the tap (the drop at 1.3 s, the
     * line at 1.5 s), or at once if that's already past. [dark] is the app's theme by now.
     */
    fun show(dark: Boolean) {
        if (root.parent == null) return
        val now = clock.now()
        val dropDelay = nearest(StartClock.DROP_MS - now, DROP_DELAYS)
        val lineDelay = nearest(StartClock.LINE_MS - now, LINE_DELAYS)
        // Same sizes as the ones set at the start, so no new layout: they draw in this frame.
        drop.setImageResource(dropDrawable(dropDelay))
        line.setImageResource(lineDrawable(dark, lineDelay))
        drainFrom = now + dropDelay
        root.visibility = View.VISIBLE
        (drop.drawable as Animatable).start()
        (line.drawable as Animatable).start()
        active = true
        // TalkBack hears "Loading…" once, when the line appears, if the app is still loading then. (A
        // delay that ends while the first screen is built runs after it, as that screen appears.)
        line.postDelayed({
            if (!active || !loading()) return@postDelayed
            line.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            line.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            line.contentDescription = activity.getString(R.string.state_loading)
        }, lineDelay.toLong())
        clock.log("drop and line (render thread) from ${now + dropDelay} and ${now + lineDelay} ms")
    }

    /** The line's view, for StartViewsTest (what TalkBack reads). */
    internal val loadingLine: View get() = line

    /** How far the drop has drained at clock time [at], as the drawable draws it: 0 = full, 1 = empty. */
    fun drainedAt(at: Long): Float = drained(at - drainFrom)

    /** Ready: the drop goes at once ([StartLogo] draws it from here on), and the line fades out in 0.09 s. */
    fun hide() {
        if (!active) {
            remove()
            return
        }
        active = false
        drop.visibility = View.GONE
        line.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        line.contentDescription = null
        line.animate().alpha(0f).setDuration(LINE_OUT_MS).withEndAction(::remove).start()
    }

    /** Gone for good (also when the start never needed them). */
    fun remove() {
        active = false
        (drop.drawable as? Animatable)?.stop()
        (line.drawable as? Animatable)?.stop()
        (root.parent as? ViewGroup)?.removeView(root)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    internal companion object {
        /** The drop's drain and refill: the same numbers as the generated drawables. */
        const val DRAIN_CYCLE_MS = 1_600
        const val DRAINED_AT = 0.45f
        private val DROP_DELAYS = listOf(0, 200, 400)
        private val LINE_DELAYS = listOf(0, 300, 600)
        private const val SPLASH_DP = 288
        private const val LINE_WIDTH_DP = 120
        private const val LINE_HEIGHT_DP = 4
        private const val LINE_BELOW_CENTRE_DP = 84
        private const val LINE_OUT_MS = 90L

        /** The generated delay nearest to [wanted] ms (0 when that's already past). */
        fun nearest(wanted: Long, delays: List<Int>): Int = delays.minBy { abs(it - wanted.coerceAtLeast(0)) }

        /** The drop's level [t] ms after it started draining: down in 45% of the cycle, back up in the rest. */
        fun drained(t: Long): Float {
            if (t <= 0) return 0f
            val f = (t % DRAIN_CYCLE_MS).toFloat() / DRAIN_CYCLE_MS
            return if (f < DRAINED_AT) {
                EaseInOut.transform(f / DRAINED_AT)
            } else {
                1f - EaseInOut.transform((f - DRAINED_AT) / (1f - DRAINED_AT))
            }
        }

        @DrawableRes
        private fun dropDrawable(delay: Int): Int = when (delay) {
            0 -> R.drawable.avd_start_drop_0
            200 -> R.drawable.avd_start_drop_200
            else -> R.drawable.avd_start_drop_400
        }

        @DrawableRes
        private fun lineDrawable(dark: Boolean, delay: Int): Int = when (delay) {
            0 -> if (dark) R.drawable.avd_start_line_dark_0 else R.drawable.avd_start_line_light_0
            300 -> if (dark) R.drawable.avd_start_line_dark_300 else R.drawable.avd_start_line_light_300
            else -> if (dark) R.drawable.avd_start_line_dark_600 else R.drawable.avd_start_line_light_600
        }
    }
}
