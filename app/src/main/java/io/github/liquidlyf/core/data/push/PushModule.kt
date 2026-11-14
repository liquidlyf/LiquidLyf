package io.github.liquidlyf.core.data.push

import io.github.liquidlyf.core.data.ProcessDataStores
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PushTopics"

/** Real FCM topic calls (dev and prod). */
class FcmTopicMessaging @Inject constructor() : TopicMessaging {
    override suspend fun subscribe(topic: String) =
        logged("subscribed to", topic) { Firebase.messaging.subscribeToTopic(topic).await() }

    override suspend fun unsubscribe(topic: String) =
        logged("unsubscribed from", topic) { Firebase.messaging.unsubscribeFromTopic(topic).await() }

    /**
     * One line per topic call, so a phone check can see that alerts are set up. Topic names hold
     * the uid, area and blood group: the published app logs only the kind (`up_…`), local test
     * builds the whole name.
     */
    private suspend fun logged(done: String, topic: String, call: suspend () -> Unit) {
        val shown = if (BuildConfig.LOCAL_BUILD) topic else topic.substringBefore('_') + "_…"
        try {
            call()
            Log.i(TAG, "$done $shown")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "not $done $shown: ${e.javaClass.simpleName}")
            throw e
        }
    }
}

/** The demo flavor runs on the emulators, which have no FCM: topics are only logged. */
class LoggingTopicMessaging @Inject constructor() : TopicMessaging {
    override suspend fun subscribe(topic: String) {
        Log.d(TAG, "demo: would subscribe to $topic")
    }

    override suspend fun unsubscribe(topic: String) {
        Log.d(TAG, "demo: would unsubscribe from $topic")
    }
}

/** Its own small DataStore file, so it never touches the settings. */
@Singleton
class DataStoreSubscribedTopicsStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SubscribedTopicsStore {
    private val dataStore: DataStore<Preferences> by lazy {
        ProcessDataStores.get(context, "push_topics")
    }

    override suspend fun get(): Set<String> = dataStore.data.first()[TOPICS].orEmpty()

    override suspend fun set(topics: Set<String>) {
        dataStore.edit { it[TOPICS] = topics }
    }

    private companion object {
        val TOPICS = stringSetPreferencesKey("subscribed_topics")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds abstract fun store(impl: DataStoreSubscribedTopicsStore): SubscribedTopicsStore

    @Binds abstract fun alerts(impl: DataStoreAlertStore): AlertStore

    @Binds abstract fun notifications(impl: io.github.liquidlyf.notifications.AlertNotifier): SystemNotifications

    companion object {
        @Provides
        fun topicMessaging(fcm: dagger.Lazy<FcmTopicMessaging>, demo: dagger.Lazy<LoggingTopicMessaging>): TopicMessaging =
            if (BuildConfig.USE_FIREBASE_EMULATOR) demo.get() else fcm.get()
    }
}
