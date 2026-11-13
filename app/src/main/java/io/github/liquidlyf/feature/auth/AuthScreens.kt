package io.github.liquidlyf.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.auth.GoogleSignInResult
import io.github.liquidlyf.core.data.auth.googleWebClientId
import io.github.liquidlyf.core.data.auth.requestGoogleIdToken
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import kotlinx.coroutines.launch

/** Shared frame: top bar, scrolling content that stays above the keyboard, at most 480 dp wide. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthLayout(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = { LLTopBar(title = title, onBack = onBack) }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter(), vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

/** An error that belongs to the whole form, read out by TalkBack as an error. */
@Composable
internal fun FormError(@StringRes error: Int?) {
    if (error != null) {
        val text = stringResource(error)
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { error(text) },
        )
    }
}

/** The app's current language, for picking the English or Bangla web page. */
@Composable
internal fun currentAppLocale(): AppLocale =
    if (LocalConfiguration.current.locales[0].language == "bn") AppLocale.BN else AppLocale.EN

// ---- Sign in ----

@Composable
fun SignInRoute(onCreateAccount: () -> Unit, onForgotPassword: () -> Unit, viewModel: SignInViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onGoogle = rememberContinueWithGoogle(viewModel::onGoogleStarted, viewModel::onGoogleResult)
    SignInScreen(state, viewModel::onEmailChange, viewModel::onPasswordChange, viewModel::signIn, onCreateAccount, onForgotPassword, onGoogle)
}

/**
 * The "Continue with Google" click, or null when this build can't offer it: only builds with a
 * real Firebase config have a web client ID (the demo flavor has none).
 */
@Composable
private fun rememberContinueWithGoogle(
    onStarted: () -> Boolean,
    onResult: (GoogleSignInResult) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val webClientId = remember(context) { googleWebClientId(context) }
    val scope = rememberCoroutineScope()
    if (activity == null || webClientId == null) return null
    return {
        if (onStarted()) scope.launch { onResult(requestGoogleIdToken(activity, webClientId)) }
    }
}

/** "Continue with Google", then "or" above the email form. */
@Composable
private fun ColumnScope.GoogleOption(onClick: () -> Unit, enabled: Boolean) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_continue_with_google))
    }
    Text(
        stringResource(R.string.auth_or),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SignInScreen(
    state: SignInUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    onContinueWithGoogle: (() -> Unit)? = null,
) {
    AuthLayout(title = stringResource(R.string.title_sign_in), onBack = null) {
        if (onContinueWithGoogle != null) GoogleOption(onContinueWithGoogle, enabled = !state.loading)
        LLTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = stringResource(R.string.label_email),
            errorText = state.emailError?.let { stringResource(it) },
            enabled = !state.loading,
            contentType = ContentType.EmailAddress + ContentType.Username,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        LLTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.label_password),
            errorText = state.passwordError?.let { stringResource(it) },
            enabled = !state.loading,
            isPassword = true,
            contentType = ContentType.Password,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSignIn() }),
        )
        TextButton(onClick = dropUnlessResumed(block = onForgotPassword), enabled = !state.loading) {
            Text(stringResource(R.string.action_forgot_password))
        }
        FormError(state.formError)
        LLPrimaryButton(
            text = stringResource(R.string.action_sign_in),
            onClick = onSignIn,
            loading = state.loading,
            loadingText = stringResource(R.string.action_signing_in),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sign_in_no_account), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = dropUnlessResumed(block = onCreateAccount), enabled = !state.loading) {
                Text(stringResource(R.string.action_create_account))
            }
        }
    }
}

// ---- Sign up ----

@Composable
fun SignUpRoute(onBack: () -> Unit, viewModel: SignUpViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SignUpScreen(
        state = state,
        onNameChange = viewModel::onNameChange,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConsentChange = viewModel::onConsentChange,
        onSignUp = viewModel::signUp,
        onBack = onBack,
        onContinueWithGoogle = rememberContinueWithGoogle(viewModel::onGoogleStarted, viewModel::onGoogleResult),
    )
}

@Composable
fun SignUpScreen(
    state: SignUpUiState,
    onNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    onSignUp: () -> Unit,
    onBack: () -> Unit,
    onContinueWithGoogle: (() -> Unit)? = null,
) {
    val links = rememberSafeIntentLauncher()
    val locale = currentAppLocale()
    AuthLayout(title = stringResource(R.string.title_sign_up), onBack = onBack) {
        // P2: a new Google account skips this form and confirms 18+ and the Terms in setup.
        if (onContinueWithGoogle != null) GoogleOption(onContinueWithGoogle, enabled = !state.loading)
        LLTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = stringResource(R.string.label_full_name),
            errorText = state.nameError?.let { stringResource(it) },
            enabled = !state.loading,
            contentType = ContentType.PersonFullName,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        LLTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = stringResource(R.string.label_email),
            errorText = state.emailError?.let { stringResource(it) },
            enabled = !state.loading,
            contentType = ContentType.EmailAddress,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        LLTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.label_password),
            supportingText = stringResource(R.string.password_hint),
            errorText = state.passwordError?.let { stringResource(it) },
            enabled = !state.loading,
            isPassword = true,
            contentType = ContentType.NewPassword,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = state.consent, enabled = !state.loading, role = Role.Checkbox, onValueChange = onConsentChange),
        ) {
            Checkbox(checked = state.consent, onCheckedChange = null)
            Text(stringResource(R.string.sign_up_consent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.xs))
        }
        state.consentError?.let { FormError(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = { links.openUrl(Links.terms(locale)) }) { Text(stringResource(R.string.action_read_terms)) }
            TextButton(onClick = { links.openUrl(Links.privacy(locale)) }) { Text(stringResource(R.string.action_read_privacy)) }
        }
        FormError(state.formError)
        LLPrimaryButton(
            text = stringResource(R.string.action_create_account),
            onClick = onSignUp,
            loading = state.loading,
            loadingText = stringResource(R.string.action_creating_account),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sign_up_have_account), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = dropUnlessResumed(block = onBack), enabled = !state.loading) {
                Text(stringResource(R.string.action_sign_in))
            }
        }
    }
}

// ---- Verify email ----

@Composable
fun VerifyEmailRoute(viewModel: VerifyEmailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val message = state.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    VerifyEmailScreen(state, viewModel::checkVerified, viewModel::resend, viewModel::useDifferentEmail)
}

@Composable
fun VerifyEmailScreen(
    state: VerifyEmailUiState,
    onCheckVerified: () -> Unit,
    onResend: () -> Unit,
    onUseDifferentEmail: () -> Unit,
) {
    val intents = rememberSafeIntentLauncher()
    AuthLayout(title = stringResource(R.string.title_verify_email), onBack = null) {
        Text(stringResource(R.string.verify_email_body, state.email), style = MaterialTheme.typography.bodyLarge)
        LLPrimaryButton(
            text = stringResource(R.string.action_i_have_verified),
            onClick = onCheckVerified,
            loading = state.checking,
            loadingText = stringResource(R.string.action_checking),
        )
        OutlinedButton(onClick = { intents.openEmailApp() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_open_email_app))
        }
        TextButton(onClick = onResend, enabled = state.resendIn == 0, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.resendIn > 0) {
                    stringResource(R.string.action_resend_in, state.resendIn)
                } else {
                    stringResource(R.string.action_resend_email)
                },
            )
        }
        TextButton(onClick = onUseDifferentEmail, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_use_different_email))
        }
    }
}

// ---- Forgot password ----

@Composable
fun ForgotPasswordRoute(onBack: () -> Unit, viewModel: ForgotPasswordViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForgotPasswordScreen(state, viewModel::onEmailChange, viewModel::send, onBack)
}

@Composable
fun ForgotPasswordScreen(
    state: ForgotPasswordUiState,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    AuthLayout(title = stringResource(R.string.title_forgot_password), onBack = onBack) {
        val sentTo = state.sentTo
        if (sentTo != null) {
            // Same words whether or not the account exists (no email probing).
            EmptyState(
                icon = LLIcons.CheckCircle,
                title = stringResource(R.string.forgot_password_sent_title),
                body = stringResource(R.string.forgot_password_sent_body, sentTo),
                actionLabel = stringResource(R.string.action_back_to_sign_in),
                onAction = onBack,
            )
            return@AuthLayout
        }
        Text(stringResource(R.string.forgot_password_body), style = MaterialTheme.typography.bodyLarge)
        LLTextField(
            value = state.email,
            onValueChange = onEmailChange,
            label = stringResource(R.string.label_email),
            errorText = state.emailError?.let { stringResource(it) },
            enabled = !state.loading,
            contentType = ContentType.EmailAddress,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSend() }),
        )
        FormError(state.formError)
        LLPrimaryButton(
            text = stringResource(R.string.action_send_link),
            onClick = onSend,
            loading = state.loading,
            loadingText = stringResource(R.string.action_sending),
        )
        TextButton(onClick = dropUnlessResumed(block = onBack), enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back_to_sign_in))
        }
    }
}
