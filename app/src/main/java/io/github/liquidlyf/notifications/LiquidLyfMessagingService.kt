package io.github.liquidlyf.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.liquidlyf.MainActivity
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.model.BloodGroup

/**
 * Receives the Worker's topic alerts (doc 10 §6.3): data-only, high priority, public fields only
 * `{type, requestId, bloodGroup, district, urgency, tier}`. The app renders the text itself.
 *
 * Minimal for the Phase 2 live dispatch test: it logs each alert (with send and receive times,
 * for the topic-latency spike) and shows a plain notification. Phase 5 adds the localized text
 * with the area name, the deep link, dropping your own requests and blocked requesters, the
 * "Still needed" repeat and the local inbox entry.
 */
class LiquidLyfMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.i(
            TAG,
            "received type=${data["type"]} requestId=${data["requestId"]} tier=${data["tier"]} " +
                "sentAt=${message.sentTime} receivedAt=${System.currentTimeMillis()}",
        )
        if (data["type"] == TYPE_NEW_REQUEST) showNewRequest(data)
    }

    /** Topics need no stored token (doc 10 §6): nothing to do. */
    override fun onNewToken(token: String) = Unit

    private fun showNewRequest(data: Map<String, String>) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return // everything is still visible in the app's feed
        val group = BloodGroup.fromCode(data["bloodGroup"])?.let { getString(it.labelRes) }
        val title = if (group != null) getString(R.string.notification_new_request_title, group) else getString(R.string.notification_new_request_title_any)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.URGENT_REQUESTS)
            .setSmallIcon(LLIcons.BloodDrop)
            .setContentTitle(title)
            .setContentText(getString(R.string.notification_new_request_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // One notification per request: a later tier for the same request replaces it.
        NotificationManagerCompat.from(this).notify(data["requestId"].hashCode(), notification)
    }

    private companion object {
        const val TAG = "LiquidLyfPush"
        const val TYPE_NEW_REQUEST = "NEW_REQUEST"
    }
}
