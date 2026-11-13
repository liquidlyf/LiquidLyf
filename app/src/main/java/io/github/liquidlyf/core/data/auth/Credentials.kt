package io.github.liquidlyf.core.data.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException

/*
 * Credential Manager for "Continue with Google" (Phase 3.4). It needs an Activity, so it lives
 * here rather than in a ViewModel. Email passwords are saved and filled by Android Autofill
 * (the fields carry content types), which already asks "Save password?": a second offer from
 * Credential Manager would ask twice (Phase 3.15).
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
