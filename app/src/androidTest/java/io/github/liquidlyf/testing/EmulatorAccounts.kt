package io.github.liquidlyf.testing

import com.google.firebase.auth.FirebaseAuth
import io.github.liquidlyf.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Helpers for instrumented tests on the Firebase emulators (demo flavor, adb reverse 8085/9099). */
object EmulatorAccounts {
    const val PASSWORD = "password123"
    private const val PROJECT = "demo-liquidlyf"

    fun emulatorsUp(): Boolean = BuildConfig.USE_FIREBASE_EMULATOR && listOf(8085, 9099).all { port ->
        try {
            Socket().use { it.connect(InetSocketAddress(BuildConfig.EMULATOR_HOST, port), 1_000) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** A new account with a verified email, signed in. Returns its email. */
    suspend fun newVerifiedUser(auth: FirebaseAuth, tag: String): String {
        val email = "it-$tag-${System.nanoTime()}@example.test"
        auth.signOut()
        val uid = auth.createUserWithEmailAndPassword(email, PASSWORD).await().user!!.uid
        verifyEmail(uid)
        // A fresh sign-in, so Firestore gets a token with email_verified.
        auth.signOut()
        auth.signInWithEmailAndPassword(email, PASSWORD).await()
        return email
    }

    suspend fun signIn(auth: FirebaseAuth, email: String) {
        auth.signOut()
        auth.signInWithEmailAndPassword(email, PASSWORD).await()
    }

    /** The Auth emulator's admin API stands in for the user opening the email link. */
    suspend fun verifyEmail(uid: String) = withContext(Dispatchers.IO) {
        val url = URL("http://${BuildConfig.EMULATOR_HOST}:9099/identitytoolkit.googleapis.com/v1/projects/$PROJECT/accounts:update")
        val http = url.openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer owner")
            http.setRequestProperty("Content-Type", "application/json")
            http.outputStream.use { it.write("""{"localId":"$uid","emailVerified":true}""".toByteArray()) }
            check(http.responseCode == 200) { "Auth emulator refused to verify $uid: ${http.responseCode}" }
        } finally {
            http.disconnect()
        }
    }
}
