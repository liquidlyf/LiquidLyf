package io.github.liquidlyf

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.liquidlyf.core.data.update.DailyChecks
import io.github.liquidlyf.core.firebase.AppCheckInstaller
import io.github.liquidlyf.notifications.NotificationChannels

@HiltAndroidApp
class LiquidLyfApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // App Check first, before anything talks to Firebase.
        AppCheckInstaller.install()
        NotificationChannels.createAll(this)
        // The silent daily check (hospital list), plus one now if the last is over a day old.
        // Instrumented tests use HiltTestApplication, so they never reach the website.
        DailyChecks.schedule(this)
    }
}
