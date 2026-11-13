package io.github.liquidlyf.core.data.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Credential Manager (Phase 3.4, 3.15): "Continue with Google", and offering to save the email
 * password so a new phone can sign in quickly. Both need an Activity, so they live here rather
 * than in a ViewModel.
 */

sealed interface GoogleSignInResult {
    data class Token(val idToken: String) : GoogleSignInResult

    /** The user closed the sheet: say nothing. */
    data object Cancelled : GoogleSignInResult

    /** No Google account on the phone. */
    data object NoAccount : GoogleSignInResult

    data object Failed : GoogleSignInResult
}

/**
 * The OAuth web client ID that the google-services plugin generates from google-services.json.
 * Null in the demo flavor (its dummy config has no OAuth client), which then hides the button.
 */
@SuppressLint("DiscouragedApi") // the resource exists only in builds with a real Firebase config
fun googleWebClientId(context: Context): String? {
    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
}

/** Shows Google's account sheet and returns an ID token for Firebase Auth. */
suspend fun requestGoogleIdToken(activity: Activity, webClientId: String): GoogleSignInResult {
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
        .build()
    return try {
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleSignInResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            GoogleSignInResult.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: GetCredentialCancellationException) {
        GoogleSignInResult.Cancelled
    } catch (_: NoCredentialException) {
        GoogleSignInResult.NoAccount
    } catch (_: GetCredentialException) {
        GoogleSignInResult.Failed
    } catch (_: GoogleIdTokenParsingException) {
        GoogleSignInResult.Failed
    }
}

/**
 * Offers to save an email and password after a successful sign-in, sign-up or password change.
 * ViewModels call [offer]; MainActivity collects [requests] and shows the system's save sheet,
 * so the offer survives the screen changing right after sign-in.
 */
@Singleton
class CredentialSaver @Inject constructor() {
    private val pending = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val requests: SharedFlow<Pair<String, String>> = pending

    fun offer(email: String, password: String) {
        pending.tryEmit(email.trim() to password)
    }

    companion object {
        /** Shows the save sheet. Saying no, or a phone without a password manager, is fine. */
        suspend fun save(activity: Activity, email: String, password: String) {
            try {
                CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(email, password))
            } catch (e: CancellationException) {
                throw e
            } catch (_: CreateCredentialException) {
                // Declined or unavailable: nothing to do.
            }
        }
    }
}
