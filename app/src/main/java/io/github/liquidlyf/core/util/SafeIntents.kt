package io.github.liquidlyf.core.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.Locale

/**
 * Opens other apps without ever crashing (BUG-029, BUG-030). Each function returns false
 * when no app can handle the action, e.g. a tablet without a dialer. We never pre-check
 * with resolveActivity(): on Android 11+ it returns null unless the manifest declares
 * <queries>, which is what broke "Open email app" in v0.
 *
 * In Compose, use [rememberSafeIntentLauncher], which also shows a snackbar on failure.
 */
object SafeIntents {

    fun dial(context: Context, phone: String): Boolean =
        context.tryStart(Intent(Intent.ACTION_DIAL, telUri(phone).toUri()))

    /** Opens a maps app at the point, or the Google Maps website if no maps app is installed. */
    fun openMaps(context: Context, lat: Double, lng: Double, label: String? = null): Boolean =
        context.tryStart(Intent(Intent.ACTION_VIEW, geoUri(lat, lng, label).toUri())) ||
            context.tryStart(Intent(Intent.ACTION_VIEW, mapsWebUrl(lat, lng).toUri()))

    /** Opens an http(s) link. Any other scheme is refused, so a stored link can't launch an arbitrary app. */
    fun openUrl(context: Context, url: String): Boolean =
        isWebUrl(url) && context.tryStart(Intent(Intent.ACTION_VIEW, url.trim().toUri()))

    fun openEmailApp(context: Context): Boolean =
        context.tryStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL))

    /** A new email to [to] in the phone's email app (mailto:, so only email apps answer). */
    fun email(context: Context, to: String, subject: String): Boolean =
        context.tryStart(Intent(Intent.ACTION_SENDTO, mailtoUri(to, subject).toUri()))

    /** The phone's notification settings for this app (Android 8+), or the app's info page. */
    fun openNotificationSettings(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val notifications = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (context.tryStart(notifications)) return true
        }
        return context.tryStart(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }

    fun shareText(context: Context, text: String, chooserTitle: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return context.tryStart(Intent.createChooser(send, chooserTitle))
    }

    // Pure helpers, unit-tested. Numbers use Locale.ROOT so Bangla digits never reach a URI.

    fun telUri(phone: String): String = "tel:" + phone.filter { it.isDigit() || it == '+' }

    fun mailtoUri(to: String, subject: String): String = "mailto:${to.trim()}?subject=${encode(subject)}"

    fun geoUri(lat: Double, lng: Double, label: String? = null): String {
        val point = coordinates(lat, lng)
        val query = if (label.isNullOrBlank()) point else "$point(${encode(label)})"
        return "geo:$point?q=$query"
    }

    fun mapsWebUrl(lat: Double, lng: Double): String =
        "https://www.google.com/maps/search/?api=1&query=${coordinates(lat, lng)}"

    fun isWebUrl(url: String): Boolean {
        val trimmed = url.trim().lowercase(Locale.ROOT)
        return (trimmed.startsWith("https://") || trimmed.startsWith("http://")) &&
            trimmed.substringAfter("://").isNotEmpty()
    }

    private fun coordinates(lat: Double, lng: Double): String =
        String.format(Locale.ROOT, "%.6f,%.6f", lat, lng)

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun Context.tryStart(intent: Intent): Boolean {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

/** [SafeIntents] for composables: shows a snackbar when no app can handle the action. */
@Stable
class SafeIntentLauncher internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState,
) {
    fun dial(phone: String) = run(R.string.intent_no_dialer) { SafeIntents.dial(context, phone) }

    fun openMaps(lat: Double, lng: Double, label: String? = null) =
        run(R.string.intent_no_maps) { SafeIntents.openMaps(context, lat, lng, label) }

    fun openUrl(url: String) = run(R.string.intent_no_browser) { SafeIntents.openUrl(context, url) }

    fun openEmailApp() = run(R.string.intent_no_email_app) { SafeIntents.openEmailApp(context) }

    fun email(to: String, subject: String) = run(R.string.intent_no_email_app) { SafeIntents.email(context, to, subject) }

    fun openNotificationSettings() = run(R.string.intent_no_settings) { SafeIntents.openNotificationSettings(context) }

    fun shareText(text: String) = run(R.string.intent_no_share_target) {
        SafeIntents.shareText(context, text, context.getString(R.string.intent_share_chooser_title))
    }

    private inline fun run(@StringRes failureMessage: Int, launch: () -> Boolean) {
        if (!launch()) {
            val message = context.getString(failureMessage)
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }
}

@Composable
fun rememberSafeIntentLauncher(): SafeIntentLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    return remember(context, scope, snackbarHostState) { SafeIntentLauncher(context, scope, snackbarHostState) }
}
