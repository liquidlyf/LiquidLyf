package io.github.liquidlyf.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Receives the Worker's pushes: data-only, high priority, codes only (doc 10 §6). It logs the
 * send and receive times (topic-latency spike) and hands the data to [PushHandler].
 */
@AndroidEntryPoint
class LiquidLyfMessagingService : FirebaseMessagingService() {

    @Inject lateinit var handler: PushHandler

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.i(
            TAG,
            "received type=${data["type"]} requestId=${data["requestId"]} tier=${data["tier"]} " +
                "sentAt=${message.sentTime} receivedAt=${System.currentTimeMillis()}",
        )
        // A background thread with ~20 s to finish; every wait in the handler is bounded.
        runBlocking { handler.handle(data) }
    }

    /**
     * A new FCM registration (firebase-messaging 26 replaces onNewToken with this): its topics
     * may be gone, so subscribe again.
     */
    override fun onRegistered(installationId: String) = handler.resubscribe()

    /** The older callback, still delivered by some Play services versions (and lint expects it). */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) = handler.resubscribe()

    private companion object {
        const val TAG = "LiquidLyfPush"
    }
}
