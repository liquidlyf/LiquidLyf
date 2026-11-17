package io.github.liquidlyf.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.liquidlyf.R
import io.github.liquidlyf.core.util.RelativeSpan
import io.github.liquidlyf.core.util.RelativeTime
import kotlinx.coroutines.delay
import android.icu.text.DateFormat
import java.time.Instant
import java.util.Date

/** The current time, ticking every [periodMillis] so countdowns stay fresh while a screen is open. */
@Composable
fun rememberNow(periodMillis: Long = 60_000): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(periodMillis)
        value = Instant.now()
    }
}

/** "in 3 h", "2 days ago", "now". [long]: "in 3 hours", for a page with room (COPY-02). */
@Composable
fun relativeTimeText(target: Instant, now: Instant, long: Boolean = false): String {
    val span = RelativeTime.between(now, target)
    val n = span.count.toInt()
    return when (span.unit) {
        RelativeSpan.Unit.NOW -> stringResource(R.string.time_now)
        RelativeSpan.Unit.MINUTES ->
            pluralStringResource(
                when {
                    !span.future -> R.plurals.time_minutes_ago
                    long -> R.plurals.time_in_minutes_long
                    else -> R.plurals.time_in_minutes
                },
                n, n,
            )
        RelativeSpan.Unit.HOURS ->
            pluralStringResource(
                when {
                    !span.future -> R.plurals.time_hours_ago
                    long -> R.plurals.time_in_hours_long
                    else -> R.plurals.time_in_hours
                },
                n, n,
            )
        RelativeSpan.Unit.DAYS ->
            pluralStringResource(if (span.future) R.plurals.time_in_days else R.plurals.time_days_ago, n, n)
    }
}

/**
 * A date and time in the phone's time zone and the app's language, e.g. "14 Nov 2026, 3:30 pm".
 * Android's ICU formatter, not java.time: java.time keeps ASCII digits, so Bangla came out as
 * "১৩ নভেম্বর, ২০২৬ 6:00 PM" (8.4: Bangla digits throughout).
 */
@Composable
fun dateTimeText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date.from(instant))
}

/** Today: only the time ("9:46 AM"); another day: the date and time (UI-03's "Needed by 9:46 AM"). */
@Composable
fun timeOrDateTimeText(instant: Instant, now: Instant): String {
    val zone = java.time.ZoneId.systemDefault()
    if (instant.atZone(zone).toLocalDate() != now.atZone(zone).toLocalDate()) return dateTimeText(instant)
    val locale = LocalConfiguration.current.locales[0]
    return DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date.from(instant))
}

/** A date in the phone's time zone and the app's language, e.g. "14 Nov 2026". */
@Composable
fun dateText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date.from(instant))
}

/** A calendar date (no time zone shift) in the app's language, e.g. a donation date picked in a date picker. */
@Composable
fun localDateText(date: java.time.LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = android.icu.util.TimeZone.GMT_ZONE }
    return format.format(Date.from(date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
}

/** A count in the app's language: "৩" in Bangla, "3" in English (8.4: no ASCII digits in Bangla). */
@Composable
fun numberText(value: Int): String =
    android.icu.text.NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(value.toLong())
