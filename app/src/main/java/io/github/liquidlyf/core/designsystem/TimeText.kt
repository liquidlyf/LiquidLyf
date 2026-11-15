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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The current time, ticking every [periodMillis] so countdowns stay fresh while a screen is open. */
@Composable
fun rememberNow(periodMillis: Long = 60_000): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(periodMillis)
        value = Instant.now()
    }
}

/** "in 3 h", "2 days ago", "now". */
@Composable
fun relativeTimeText(target: Instant, now: Instant): String {
    val span = RelativeTime.between(now, target)
    val n = span.count.toInt()
    return when (span.unit) {
        RelativeSpan.Unit.NOW -> stringResource(R.string.time_now)
        RelativeSpan.Unit.MINUTES ->
            pluralStringResource(if (span.future) R.plurals.time_in_minutes else R.plurals.time_minutes_ago, n, n)
        RelativeSpan.Unit.HOURS ->
            pluralStringResource(if (span.future) R.plurals.time_in_hours else R.plurals.time_hours_ago, n, n)
        RelativeSpan.Unit.DAYS ->
            pluralStringResource(if (span.future) R.plurals.time_in_days else R.plurals.time_days_ago, n, n)
    }
}

/** A date and time in the phone's time zone and the app's language, e.g. "14 Nov 2026, 3:30 pm". */
@Composable
fun dateTimeText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(instant.atZone(ZoneId.systemDefault()))
}

/** A date in the phone's time zone and the app's language, e.g. "14 Nov 2026". */
@Composable
fun dateText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(instant.atZone(ZoneId.systemDefault()))
}
