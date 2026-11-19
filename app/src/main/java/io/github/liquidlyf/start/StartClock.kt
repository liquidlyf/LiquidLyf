package io.github.liquidlyf.start

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.liquidlyf.BuildConfig

/**
 * D25: the start's times, in milliseconds since the tap on the app's icon. Android starts the
 * process for that tap, so the clock counts from the process's start, as the maintainer's 1.3 s and
 * 1.5 s do (A45 b). A process that was already running (a push woke it, or the app was closed with
 * Back) counts from this activity instead.
 */
class StartClock internal constructor(private val zero: Long, private val uptime: () -> Long = SystemClock::uptimeMillis) {

    fun now(): Long = uptime() - zero

    /** The still picture has had its time: the app's own copy takes over now, even while loading. */
    fun holdOver(): Boolean = now() >= HOLD_MS

    /** The times of the start, in the logs of a local build (A's measures for D25). */
    fun log(event: String) {
        if (BuildConfig.LOCAL_BUILD) Log.i("LLStart", "$event at ${now()} ms (uptime ${uptime()} ms)")
    }

    companion object {
        /**
         * The drop in the logo starts draining and refilling (D25, A45 b). A usual start is ready by
         * about 1.1 s, so it shows only the still logo, then the move.
         */
        const val DROP_MS = 1_300L

        /** The thin loading line starts to fade in (A45 b). */
        const val LINE_MS = 1_500L

        /**
         * The still picture stays until the start data is read, so a usual start's first screen comes
         * as soon as before (read by 0.6–0.75 s; A's measure, 20 Nov 2026), but no later than this,
         * so the app has drawn its copy by the time the drop starts.
         */
        const val HOLD_MS = DROP_MS - 100

        /** A process older than this wasn't started by this tap. */
        private const val FRESH_PROCESS_MS = 10_000L

        fun begin(
            processStart: Long = Process.getStartUptimeMillis(),
            now: Long = SystemClock.uptimeMillis(),
            uptime: () -> Long = SystemClock::uptimeMillis,
        ): StartClock = StartClock(if (now - processStart < FRESH_PROCESS_MS) processStart else now, uptime)
    }
}
