package io.github.liquidlyf

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.liquidlyf.core.firebase.AppCheckInstaller
import io.github.liquidlyf.notifications.NotificationChannels

@HiltAndroidApp
class LiquidLyfApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // App Check first, before anything talks to Firebase.
        AppCheckInstaller.install()
        NotificationChannels.createAll(this)
    }
}
