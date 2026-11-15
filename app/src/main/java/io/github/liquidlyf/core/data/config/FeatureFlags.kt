package io.github.liquidlyf.core.data.config

import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.liquidlyf.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** Switches the maintainer can flip in Remote Config without a release (doc 10, saver mode). */
interface FeatureFlags {
    /** Find donors (6.1). Off in saver mode, when the free read budget runs low. */
    suspend fun donorSearchEnabled(): Boolean
}

/** Dev and prod. A key that isn't set in the console counts as "on". Any error too: fail open. */
class RemoteConfigFeatureFlags @Inject constructor() : FeatureFlags {
    override suspend fun donorSearchEnabled(): Boolean = flag(DONOR_SEARCH_ENABLED)

    private suspend fun flag(key: String): Boolean = try {
        val config = Firebase.remoteConfig
        runCatching { config.fetchAndActivate().await() } // throttled by the SDK (12 h by default)
        val value = config.getValue(key)
        value.source == FirebaseRemoteConfig.VALUE_SOURCE_STATIC || value.asBoolean()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }

    private companion object {
        const val DONOR_SEARCH_ENABLED = "donor_search_enabled"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object FeatureFlagsModule {
    /** The demo flavor has no Remote Config: everything is on. */
    @Provides
    fun flags(remote: dagger.Lazy<RemoteConfigFeatureFlags>): FeatureFlags =
        if (BuildConfig.USE_FIREBASE_EMULATOR) {
            object : FeatureFlags {
                override suspend fun donorSearchEnabled() = true
            }
        } else {
            remote.get()
        }
}
