package io.github.liquidlyf.testing

import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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

    /**
     * Custom claims through the Auth emulator's admin API, as the maintainer's setAdmin.mjs does
     * in production. [claimsJson] is the claims object, e.g. `{"admin":true}`. The user must then
     * refresh their ID token (`getIdToken(true)`) to carry them.
     */
    suspend fun setClaims(uid: String, claimsJson: String) = withContext(Dispatchers.IO) {
        val url = URL("http://${BuildConfig.EMULATOR_HOST}:9099/identitytoolkit.googleapis.com/v1/projects/$PROJECT/accounts:update")
        val http = url.openConnection() as HttpURLConnection
        try {
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer owner")
            http.setRequestProperty("Content-Type", "application/json")
            val attributes = claimsJson.replace("\"", "\\\"")
            http.outputStream.use { it.write("""{"localId":"$uid","customAttributes":"$attributes"}""".toByteArray()) }
            check(http.responseCode == 200) { "Auth emulator refused claims for $uid: ${http.responseCode}" }
        } finally {
            http.disconnect()
        }
    }

    /**
     * A second, independent Firebase client on the emulators, with its own signed-in user: the
     * other person in a two-person test, while the app's own session stays signed in.
     */
    fun secondFirebase(name: String): Pair<FirebaseAuth, FirebaseFirestore> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == name }
        val app = existing ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, name)
        val auth = FirebaseAuth.getInstance(app)
        val db = FirebaseFirestore.getInstance(app)
        if (existing == null) {
            auth.useEmulator(BuildConfig.EMULATOR_HOST, 9099)
            db.useEmulator(BuildConfig.EMULATOR_HOST, 8085)
        }
        return auth to db
    }
}
