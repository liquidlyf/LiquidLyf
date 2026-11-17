package io.github.liquidlyf.core.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Hands a downloaded and checked APK ([ApkDownloader]) to Android's installer ("update a",
 * 17 Nov 2026). Android always asks the person first ("Do you want to update this app?"):
 * nothing installs by itself. Only the published (prod) build may do this: its manifest alone
 * has `REQUEST_INSTALL_PACKAGES` and [ApkInstallReceiver]. A Play build must leave both out.
 */
class ApkInstaller(private val context: Context) {
    private val installer get() = context.packageManager.packageInstaller

    /**
     * Android 8+ asks once whether LiquidLyf may install apps ("Install unknown apps"). Before
     * that, the installer asks about unknown sources itself.
     */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Android's switch for LiquidLyf. The flow carries on when the person comes back. */
    fun allowInstallsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /**
     * Copies [apk] into an install session and commits it. Android answers through
     * [ApkInstallReceiver]: first with its confirmation screen, then (if it fails) the reason.
     * On success Android replaces this app, which restarts as the new version.
     */
    fun install(apk: File) {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+ could update without asking, as the app updates itself: always ask.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val intent = Intent(context, ApkInstallReceiver::class.java).setPackage(context.packageName)
                // Mutable: Android adds the status and its confirmation screen to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    /** At every start: a session left from an update that was never finished. */
    fun cleanUp() {
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
    }
}

/** Android's answers about an [ApkInstaller.install]. Registered in the prod manifest only. */
class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android's own "Update?" screen. The app is on screen (the person just tapped).
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the new version is replacing this one
            else -> ApkInstallEvents.report(status)
        }
    }
}

/** For the update screen: Android refused or the person cancelled ([PackageInstaller] status). */
object ApkInstallEvents {
    private val failures = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val failed: SharedFlow<Int> = failures.asSharedFlow()

    fun report(status: Int) {
        failures.tryEmit(status)
    }
}
