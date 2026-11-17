package io.github.liquidlyf.feature.account

import androidx.annotation.StringRes
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.auth.GoogleSignInResult
import io.github.liquidlyf.core.data.auth.googleWebClientId
import io.github.liquidlyf.core.data.auth.requestGoogleIdToken
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.feature.auth.AuthLayout
import io.github.liquidlyf.feature.auth.FormError
import io.github.liquidlyf.feature.auth.findActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddPasswordUiState(
    val email: String? = null,
    val password: String = "",
    val confirm: String = "",
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val confirmError: Int? = null,
    @param:StringRes val formError: Int? = null,
    /** From "Add" until the password is linked, including while the Google sheet is open. */
    val working: Boolean = false,
    val done: Boolean = false,
)

/**
 * P3 (decided 14 Nov 2026): a Google-only account adds a password, so it can also sign in with
 * its email and that password (say, on a shared phone without the Google account). Google
 * confirms who it is first; then the password is linked to the same account.
 */
@HiltViewModel
class AddPasswordViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val state = MutableStateFlow(AddPasswordUiState(email = auth.authState.value?.email))
    val uiState: StateFlow<AddPasswordUiState> = state.asStateFlow()

    fun onPassword(v: String) = state.update { it.copy(password = v, passwordError = null, formError = null) }

    fun onConfirm(v: String) = state.update { it.copy(confirm = v, confirmError = null, formError = null) }

    /** Checks both fields. True: the Google confirmation may start (the screen is busy until [onGoogleResult]). */
    fun readyToConfirm(): Boolean {
        val s = state.value
        if (s.working) return false
        val passwordError = if (s.password.length < Validators.PASSWORD_MIN) R.string.error_password_short else null
        val confirmError = if (passwordError == null && s.confirm != s.password) R.string.error_passwords_differ else null
        if (passwordError != null || confirmError != null) {
            state.update { it.copy(passwordError = passwordError, confirmError = confirmError) }
            return false
        }
        state.update { it.copy(working = true, formError = null) }
        return true
    }

    fun onGoogleResult(result: GoogleSignInResult) {
        when (result) {
            GoogleSignInResult.Cancelled -> state.update { it.copy(working = false) }
            GoogleSignInResult.NoAccount -> state.update { it.copy(working = false, formError = R.string.error_google_no_account) }
            GoogleSignInResult.Failed -> state.update { it.copy(working = false, formError = R.string.error_google_failed) }
            is GoogleSignInResult.Token -> viewModelScope.launch { link(result.idToken) }
        }
    }

    /** No Google sign-in in this build (the demo flavor): nothing to confirm with. */
    fun googleUnavailable() = state.update { it.copy(working = false, formError = R.string.add_password_no_google) }

    private suspend fun link(idToken: String) {
        val reauth = auth.reauthenticateWithGoogle(idToken)
        if (reauth is AppResult.Failure) {
            // A different Google account than the signed-in one comes back as InvalidCredentials.
            val text = if (reauth.error == AppError.InvalidCredentials) R.string.add_password_wrong_account else reauth.error.messageRes
            state.update { it.copy(working = false, formError = text) }
            return
        }
        when (val result = auth.addPassword(state.value.password)) {
            is AppResult.Success -> state.update { it.copy(working = false, done = true, password = "", confirm = "") }
            is AppResult.Failure -> state.update {
                if (result.error == AppError.WeakPassword) {
                    it.copy(working = false, passwordError = result.error.messageRes)
                } else {
                    it.copy(working = false, formError = result.error.messageRes)
                }
            }
        }
    }
}

@Composable
fun AddPasswordRoute(onDone: () -> Unit, viewModel: AddPasswordViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val doneText = stringResource(R.string.add_password_done, state.email.orEmpty())
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val webClientId = remember(context) { googleWebClientId(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.done) {
        if (state.done) {
            snackbar.showSnackbar(doneText)
            onDone()
        }
    }
    AddPasswordScreen(
        state = state,
        onPassword = viewModel::onPassword,
        onConfirm = viewModel::onConfirm,
        onAdd = {
            if (viewModel.readyToConfirm()) {
                if (activity == null || webClientId == null) {
                    viewModel.googleUnavailable()
                } else {
                    scope.launch { viewModel.onGoogleResult(requestGoogleIdToken(activity, webClientId)) }
                }
            }
        },
        onBack = onDone,
    )
}

@Composable
internal fun AddPasswordScreen(
    state: AddPasswordUiState,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    AuthLayout(
        title = stringResource(R.string.title_add_password),
        onBack = onBack,
        bottomBar = {
            LLBottomBar(stringResource(R.string.action_add_password), onAdd, loading = state.working, loadingText = stringResource(R.string.action_saving))
        },
    ) {
        Text(stringResource(R.string.add_password_body, state.email.orEmpty()), style = MaterialTheme.typography.bodyLarge)
        LLTextField(
            value = state.password,
            onValueChange = onPassword,
            label = stringResource(R.string.label_new_password),
            supportingText = stringResource(R.string.password_hint),
            errorText = state.passwordError?.let { stringResource(it) },
            isPassword = true,
            contentType = ContentType.NewPassword,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        LLTextField(
            value = state.confirm,
            onValueChange = onConfirm,
            label = stringResource(R.string.label_confirm_password),
            errorText = state.confirmError?.let { stringResource(it) },
            isPassword = true,
            contentType = ContentType.NewPassword,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        FormError(state.formError)
    }
}
