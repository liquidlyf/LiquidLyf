package io.github.liquidlyf.notifications

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import io.github.liquidlyf.R

/**
 * The app's notification channels (doc 06 §4.5). Created at every start so their names
 * follow the app language. Phase 5 posts to them. The IDs are permanent: renaming one
 * loses the person's settings for it.
 */
object NotificationChannels {
    const val URGENT_REQUESTS = "urgent_requests"
    const val REQUEST_UPDATES = "request_updates"
    const val REMINDERS = "reminders"
    const val GENERAL = "general"

    fun createAll(context: Context) {
        val channels = listOf(
            channel(context, URGENT_REQUESTS, NotificationManagerCompat.IMPORTANCE_HIGH,
                R.string.channel_urgent_requests_name, R.string.channel_urgent_requests_description),
            channel(context, REQUEST_UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT,
                R.string.channel_request_updates_name, R.string.channel_request_updates_description),
            channel(context, REMINDERS, NotificationManagerCompat.IMPORTANCE_DEFAULT,
                R.string.channel_reminders_name, R.string.channel_reminders_description),
            channel(context, GENERAL, NotificationManagerCompat.IMPORTANCE_LOW,
                R.string.channel_general_name, R.string.channel_general_description),
        )
        // A no-op below Android 8, which has no channels.
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }

    private fun channel(
        context: Context,
        id: String,
        importance: Int,
        @StringRes name: Int,
        @StringRes description: Int,
    ) = NotificationChannelCompat.Builder(id, importance)
        .setName(context.getString(name))
        .setDescription(context.getString(description))
        .build()
}
