package io.github.liquidlyf.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.liquidlyf.MainActivity
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.push.SystemNotifications
import io.github.liquidlyf.core.data.settings.OffScreenLanguage
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.navigation.MODERATION_DEEP_LINK
import io.github.liquidlyf.navigation.REQUEST_DEEP_LINK
import javax.inject.Inject
import javax.inject.Singleton

/** The one "You can donate again" notification (request notifications use ids from their request). */
internal const val ELIGIBLE_AGAIN_NOTIFICATION_ID = 1

/** One for all new-report alerts: a newer count replaces an unread older one. */
internal const val ADMIN_REPORTS_NOTIFICATION_ID = 2

/**
 * Posts the app's notifications (phase-05 5.2). The push carries codes only; the text is
 * rendered here in the app's own language (Phase 8.1; [OffScreenLanguage] on Android 12 and older). Every tap opens the
 * request through the `liquidlyf://request/{id}` deep link, also from a killed app.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val geoRepository: GeoRepository,
) : SystemNotifications {

    /** False when the person turned notifications off (or, on Android 13+, never allowed them). */
    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    suspend fun showRequestAlert(alert: PushMessage.RequestAlert, stillNeeded: Boolean) {
        val group = alert.bloodGroup?.let { words().getString(it.labelRes) }
        val title = when {
            stillNeeded && group != null -> words().getString(R.string.notification_still_needed_title, group)
            stillNeeded -> words().getString(R.string.notification_still_needed_title_any)
            group != null -> words().getString(R.string.notification_new_request_title, group)
            else -> words().getString(R.string.notification_new_request_title_any)
        }
        val place = alert.district?.let { code -> geoRepository.geo().district(code)?.name?.localized(locale()) }
        val urgency = alert.urgency?.let { words().getString(it.labelRes) }
        val body = if (place != null && urgency != null) {
            words().getString(R.string.notification_request_body, urgency, place)
        } else {
            words().getString(R.string.notification_new_request_body)
        }
        post(
            id = notificationId(alert.requestId, "alert"),
            channel = NotificationChannels.URGENT_REQUESTS,
            title = title,
            body = body,
            requestId = alert.requestId,
            priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    fun showPersonal(type: PersonalPush, requestId: String) {
        val (title, body) = PERSONAL.getValue(type)
        // A18 (15 Nov 2026): a requester picked this donor, so an ask pops up like an area alert.
        val urgent = type == PersonalPush.ASKED_TO_DONATE
        post(
            id = notificationId(requestId, type.code),
            channel = if (urgent) NotificationChannels.URGENT_REQUESTS else NotificationChannels.REQUEST_UPDATES,
            title = words().getString(title),
            body = words().getString(body),
            requestId = requestId,
            priority = if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    /** Phase 7.3: new reports for the admins; a tap opens Moderation. A newer count replaces the older one. */
    fun showAdminReports(count: String) = post(
        id = ADMIN_REPORTS_NOTIFICATION_ID,
        channel = NotificationChannels.REQUEST_UPDATES,
        title = words().getString(R.string.notification_reports_title),
        body = words().getString(R.string.notification_reports_body, count),
        requestId = null,
        priority = NotificationCompat.PRIORITY_DEFAULT,
        open = openUri(MODERATION_DEEP_LINK),
    )

    /** The rest period is over (phase-05 5.4); a tap opens the app at Home. */
    fun showEligibleAgain() = post(
        id = ELIGIBLE_AGAIN_NOTIFICATION_ID,
        channel = NotificationChannels.REMINDERS,
        title = words().getString(R.string.notification_eligible_again_title),
        body = words().getString(R.string.notification_eligible_again_body),
        requestId = null,
        priority = NotificationCompat.PRIORITY_DEFAULT,
    )

    /** 2 hours before my open request's needed-by time. */
    fun showRequestEnding(requestId: String) = post(
        id = notificationId(requestId, "ending"),
        channel = NotificationChannels.REMINDERS,
        title = words().getString(R.string.notification_request_ending_title),
        body = words().getString(R.string.notification_request_ending_body),
        requestId = requestId,
        priority = NotificationCompat.PRIORITY_DEFAULT,
    )

    /** FLOW-10: "Did <donor> donate?"; a tap opens the request, where "Mark donated" is. */
    fun showDidDonate(requestId: String, donorName: String) = post(
        id = notificationId(requestId, "did_donate"),
        channel = NotificationChannels.REMINDERS,
        title = words().getString(R.string.notification_did_donate_title, donorName),
        body = words().getString(R.string.notification_did_donate_body),
        requestId = requestId,
        priority = NotificationCompat.PRIORITY_DEFAULT,
    )

    override fun cancelAll() = NotificationManagerCompat.from(context).cancelAll()

    /** Opens the request in a fresh task: the deep link builds Home → request under it. */
    fun openRequest(requestId: String): PendingIntent = PendingIntent.getActivity(
        context,
        requestId.hashCode(),
        Intent(Intent.ACTION_VIEW, "$REQUEST_DEEP_LINK/$requestId".toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The app creates the channels at start; make sure of them here too (a push can start the process). */
    private val channelsReady by lazy { NotificationChannels.createAll(words()) }

    /** The app's own language, also where Android 12 and older don't apply it (no screen). */
    private fun words(): Context = OffScreenLanguage.wrap(context)

    /** Opens a deep link (e.g. Moderation) in a fresh task, like [openRequest]. */
    private fun openUri(uri: String): PendingIntent = PendingIntent.getActivity(
        context,
        uri.hashCode(),
        Intent(Intent.ACTION_VIEW, uri.toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(id: Int, channel: String, title: String, body: String, requestId: String?, priority: Int, open: PendingIntent? = null) {
        if (!canPost()) return // the app's own screens still show everything
        channelsReady
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(LLIcons.BloodDrop)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open ?: requestId?.let { openRequest(it) } ?: openApp())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post.
        }
    }

    private fun locale(): AppLocale =
        if (words().resources.configuration.locales[0].language == "bn") AppLocale.BN else AppLocale.EN

    private companion object {
        /** One notification per request and kind: a later tier replaces the first alert. */
        fun notificationId(requestId: String, kind: String) = "$requestId:$kind".hashCode()

        val PERSONAL: Map<PersonalPush, Pair<Int, Int>> = mapOf(
            PersonalPush.NEW_OFFER to (R.string.notification_new_offer_title to R.string.notification_new_offer_body),
            PersonalPush.OFFER_CONFIRMED to (R.string.notification_offer_confirmed_title to R.string.notification_offer_confirmed_body),
            PersonalPush.OFFER_DECLINED to (R.string.notification_offer_declined_title to R.string.notification_offer_declined_body),
            PersonalPush.DONATION_RECORDED to (R.string.notification_donation_recorded_title to R.string.notification_donation_recorded_body),
            PersonalPush.REQUEST_FULFILLED to (R.string.notification_request_fulfilled_title to R.string.notification_request_fulfilled_body),
            PersonalPush.REQUEST_CANCELLED to (R.string.notification_request_cancelled_title to R.string.notification_request_cancelled_body),
            PersonalPush.ASKED_TO_DONATE to (R.string.notification_asked_title to R.string.notification_asked_body),
        )
    }
}
