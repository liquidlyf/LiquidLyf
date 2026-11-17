package io.github.liquidlyf

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import io.github.liquidlyf.core.data.update.ApkDownloader
import io.github.liquidlyf.core.data.update.ApkInstaller
import io.github.liquidlyf.core.data.update.DailyChecks
import io.github.liquidlyf.core.firebase.AppCheckInstaller
import io.github.liquidlyf.core.firebase.CrashReports
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.notifications.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LiquidLyfApp : Application() {
    @Inject lateinit var apkDownloader: ApkDownloader
    @Inject lateinit var apkInstaller: ApkInstaller
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // App Check first, before anything talks to Firebase.
        AppCheckInstaller.install()
        installCrashReports()
        NotificationChannels.createAll(this)
        // The silent daily check (hospital list, app updates), plus one now if the last is over a day old.
        // Instrumented tests use HiltTestApplication, so they never reach the website.
        DailyChecks.schedule(this)
        // The APK and install session from the last in-app update (installed or not) go.
        scope.launch(Dispatchers.IO) {
            apkDownloader.cleanUp()
            runCatching { apkInstaller.cleanUp() }
        }
    }

    /** Only where Crashlytics sends reports (prod; the manifest turns it off in demo and dev). */
    private fun installCrashReports() {
        val crashlytics = FirebaseCrashlytics.getInstance()
        if (!crashlytics.isCrashlyticsCollectionEnabled) return
        crashlytics.setCustomKey(CrashReports.KEY_APP_VERSION, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        CrashReports.install(
            object : CrashReports.Sink {
                override fun setKey(key: String, value: String) = crashlytics.setCustomKey(key, value)

                override fun record(error: Throwable) = crashlytics.recordException(error)
            },
        )
    }
}
