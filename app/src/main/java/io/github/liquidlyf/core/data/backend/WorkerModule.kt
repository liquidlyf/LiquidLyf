package io.github.liquidlyf.core.data.backend

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.remoteconfig.remoteConfig
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiquidLyfWorker"

class FirebaseWorkerTokens @Inject constructor(private val auth: FirebaseAuth) : WorkerTokens {
    override suspend fun idToken(forceRefresh: Boolean): String? =
        auth.currentUser?.getIdToken(forceRefresh)?.await()?.token

    override suspend fun appCheckToken(): String? {
        if (BuildConfig.USE_FIREBASE_EMULATOR) return null
        return try {
            Firebase.appCheck.getAppCheckToken(false).await().token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Monitor mode: the Worker logs a missing token, it doesn't refuse it yet. Say why here.
            Log.w(TAG, "no App Check token: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}

/**
 * Remote Config `worker_base_url` (set by session B after the first deploy). If it's still empty,
 * one fetch is tried before giving up; the Worker's sweep covers a missed dispatch.
 */
class RemoteConfigWorkerEndpoint @Inject constructor() : WorkerEndpoint {
    override suspend fun baseUrl(): String? {
        val config = Firebase.remoteConfig
        config.getString(KEY).takeIf { it.isNotBlank() }?.let { return it }
        return try {
            config.fetchAndActivate().await()
            config.getString(KEY).takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val KEY = "worker_base_url"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkerModule {
    @Binds abstract fun client(impl: HttpWorkerClient): WorkerClient

    @Binds abstract fun tokens(impl: FirebaseWorkerTokens): WorkerTokens

    companion object {
        /** `npm run dev` in backend/worker serves the demo Worker here (reach it with adb reverse tcp:8787). */
        private const val DEMO_WORKER_PORT = 8787

        @Provides
        fun endpoint(remote: dagger.Lazy<RemoteConfigWorkerEndpoint>): WorkerEndpoint =
            if (BuildConfig.USE_FIREBASE_EMULATOR) {
                WorkerEndpoint { "http://${BuildConfig.EMULATOR_HOST}:$DEMO_WORKER_PORT" }
            } else {
                remote.get()
            }

        @Provides
        @Singleton
        fun okHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
