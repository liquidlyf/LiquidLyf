package io.github.liquidlyf.core.util

import java.time.Duration
import java.time.Instant

/** "in 3 h" or "2 days ago", as a unit and a count; the UI turns it into text with plurals. */
data class RelativeSpan(val unit: Unit, val count: Long, val future: Boolean) {
    enum class Unit { NOW, MINUTES, HOURS, DAYS }
}

object RelativeTime {
    /**
     * The span from [now] to [target], rounded down: under a minute is NOW, then minutes, then
     * hours up to 2 days (so "in 30 h" for a planned request tomorrow), then days.
     */
    fun between(now: Instant, target: Instant): RelativeSpan {
        val d = Duration.between(now, target)
        val future = !d.isNegative
        val a = d.abs()
        return when {
            a < Duration.ofMinutes(1) -> RelativeSpan(RelativeSpan.Unit.NOW, 0, future)
            a < Duration.ofHours(1) -> RelativeSpan(RelativeSpan.Unit.MINUTES, a.toMinutes(), future)
            a < Duration.ofHours(48) -> RelativeSpan(RelativeSpan.Unit.HOURS, a.toHours(), future)
            else -> RelativeSpan(RelativeSpan.Unit.DAYS, a.toDays(), future)
        }
    }
}
