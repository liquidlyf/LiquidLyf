package io.github.liquidlyf.core.firebase

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import io.github.liquidlyf.BuildConfig

/**
 * Installs App Check before any other Firebase call. Debug builds use the debug provider
 * (register its token from logcat in the Firebase console); release builds use Play
 * Integrity. The factory comes from src/debug or src/release (appCheckProviderFactory()).
 * The demo flavor skips App Check: the emulators don't check it.
 */
object AppCheckInstaller {
    fun install() {
        if (BuildConfig.USE_FIREBASE_EMULATOR) return
        Firebase.appCheck.installAppCheckProviderFactory(appCheckProviderFactory())
    }
}
