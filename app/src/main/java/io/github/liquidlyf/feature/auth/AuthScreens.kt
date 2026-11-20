package io.github.liquidlyf.feature.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.auth.GoogleSignInResult
import io.github.liquidlyf.core.data.auth.googleWebClientId
import io.github.liquidlyf.core.data.auth.requestGoogleIdToken
import io.github.liquidlyf.core.data.settings.AppLanguage
import io.github.liquidlyf.core.designsystem.components.PendingRequestLine
import io.github.liquidlyf.core.domain.FieldCheck
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.ButtonLabel
import io.github.liquidlyf.core.designsystem.components.EdgeTextButton
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.NewPasswordField
import io.github.liquidlyf.core.designsystem.components.RetypePasswordField
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.ShowFirstErrorOnAttempt
import io.github.liquidlyf.core.designsystem.components.StackedLogo
import io.github.liquidlyf.core.designsystem.components.errorAnchor
import io.github.liquidlyf.core.designsystem.components.keyboardDoneShows
import io.github.liquidlyf.core.designsystem.components.rememberErrorAnchors
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.core.util.Links
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher
import io.github.liquidlyf.feature.settings.LanguageToggle
import io.github.liquidlyf.feature.settings.LanguageViewModel
import kotlinx.coroutines.launch

/**
 * Shared frame: top bar, scrolling content that stays above the keyboard, at most 480 dp wide.
 * A form puts its main action in [bottomBar] (an [LLBottomBar]), pinned above the keyboard
 * (UI-05: on Create account the keyboard covered the button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthLayout(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = { LLTopBar(title = title, onBack = onBack, actions = actions) }, bottomBar = bottomBar) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding) // the bar already rides above the keyboard
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

/**
 * An error that belongs to the whole form. TalkBack reads it as an error the moment it appears (a live
 * region), and it scrolls itself into view once each time it appears, not again after a rotation (P2-13,
 * FLOW-04: after a failed Sign in at 200% text it was below the fold, and TalkBack heard nothing).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FormError(@StringRes error: Int?) {
    if (error != null) {
        val text = stringResource(error)
        val view = remember { BringIntoViewRequester() }
        var shown by rememberSaveable { mutableStateOf<Int?>(null) }
        LaunchedEffect(error) {
            if (shown != error) {
                shown = error
                withFrameNanos { } // laid out first
                runCatching { view.bringIntoView() }
            }
        }
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .bringIntoViewRequester(view)
                .semantics {
                    error(text)
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}

// ---- Sign in ----

@Composable
fun SignInRoute(
    /** With the email typed so far, for Create account (VT-11). */
    onCreateAccount: (email: String) -> Unit,
    onForgotPassword: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
    language: LanguageViewModel = hiltViewModel(),
    /** An email handed over from Sign up ("already has an account", FORM-04); [onPrefillUsed] clears it. */
    prefillEmail: String? = null,
    onPrefillUsed: () -> Unit = {},
    /** D31: a shared request waits on this phone; the screen says so. */
    waitingRequest: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(prefillEmail) {
        if (prefillEmail != null) {
            viewModel.onEmailChange(prefillEmail)
            onPrefillUsed()
        }
    }
    val onGoogle = rememberContinueWithGoogle(viewModel::onGoogleStarted, viewModel::onGoogleResult)
    SignInScreen(
        state, viewModel::onEmailChange, viewModel::onPasswordChange, viewModel::signIn, { onCreateAccount(state.email.trim()) }, onForgotPassword, onGoogle,
        onLanguageChange = language::set,
        waitingRequest = waitingRequest,
    )
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
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
        // Google's own colours, untinted (UI-07: the button had no Google mark).
        Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.action_continue_with_google))
    }
    Text(
        stringResource(R.string.auth_or),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignInScreen(
    state: SignInUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    onContinueWithGoogle: (() -> Unit)? = null,
    onLanguageChange: (AppLanguage) -> Unit = {},
    waitingRequest: Boolean = false,
) {
    // Before sign-in, Settings → Language can't be reached: the switch is in the top bar.
    // N4 (BRAND-01): the big stacked logo on top, and "Sign in" pinned above the keyboard (UI-05).
    AuthLayout(
        title = stringResource(R.string.title_sign_in),
        onBack = null,
        actions = { LanguageToggle(onLanguageChange) },
        bottomBar = {
            LLBottomBar(
                primaryText = stringResource(R.string.action_sign_in),
                onPrimary = onSignIn,
                loading = state.loading,
                loadingText = stringResource(R.string.action_signing_in),
            )
        },
    ) {
        StackedLogo(Modifier.align(Alignment.CenterHorizontally).padding(bottom = Spacing.xs))
        if (waitingRequest) PendingRequestLine()
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
        FormError(state.formError) // P2-13: right under the fields it's about
        // VT-11: a new user who typed into Sign in gets "Wrong email or password", which can't say
        // "no account" (Firebase hides that on purpose): offer Create account right there.
        if (state.formError == R.string.error_invalid_credentials) {
            EdgeTextButton(stringResource(R.string.sign_in_new_create_account), dropUnlessResumed(block = onCreateAccount), enabled = !state.loading)
        }
        EdgeTextButton(stringResource(R.string.action_forgot_password), dropUnlessResumed(block = onForgotPassword), enabled = !state.loading)
        // A flow row: with large text the button goes under the question instead of being squeezed.
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sign_in_no_account), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = dropUnlessResumed(block = onCreateAccount), enabled = !state.loading) {
                Text(stringResource(R.string.action_create_account))
            }
        }
    }
}

// ---- Sign up ----

/** Sign up's fields in screen order, for FLOW-04. */
private enum class SignUpField { NAME, EMAIL, PASSWORD, PASSWORD_AGAIN, CONSENT }

@Composable
fun SignUpRoute(
    onBack: () -> Unit,
    onSignInWithEmail: (String) -> Unit = { onBack() },
    /** The email typed on Sign in (VT-11: P2 had to type it again). */
    prefillEmail: String = "",
    viewModel: SignUpViewModel = hiltViewModel(),
    language: LanguageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (prefillEmail.isNotBlank() && state.email.isEmpty()) viewModel.onEmailChange(prefillEmail)
    }
    SignUpScreen(
        state = state,
        onNameChange = viewModel::onNameChange,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConsentChange = viewModel::onConsentChange,
        onSignUp = viewModel::signUp,
        onBack = onBack,
        onContinueWithGoogle = rememberContinueWithGoogle(viewModel::onGoogleStarted, viewModel::onGoogleResult),
        onLanguageChange = language::set,
        onSignInWithEmail = { onSignInWithEmail(state.email.trim()) },
        onPasswordAgainChange = viewModel::onPasswordAgainChange,
        onPasswordFocus = viewModel::onPasswordFocus,
        onPasswordAgainFocus = viewModel::onPasswordAgainFocus,
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    onLanguageChange: (AppLanguage) -> Unit = {},
    onSignInWithEmail: () -> Unit = onBack,
    onPasswordAgainChange: (String) -> Unit = {},
    onPasswordFocus: (Boolean) -> Unit = {},
    onPasswordAgainFocus: (Boolean) -> Unit = {},
) {
    val links = rememberSafeIntentLauncher()
    val locale = currentAppLocale()
    // FLOW-04: a failed Create account brings the first error into view.
    val anchors = rememberErrorAnchors<SignUpField>()
    val errors = buildSet {
        if (state.nameError != null) add(SignUpField.NAME)
        if (state.emailError != null) add(SignUpField.EMAIL)
        if (state.passwordError != null || state.password.valueCheck == FieldCheck.ERROR) add(SignUpField.PASSWORD)
        if (state.password.againCheck == FieldCheck.ERROR) add(SignUpField.PASSWORD_AGAIN)
        if (state.consentError != null) add(SignUpField.CONSENT)
    }
    ShowFirstErrorOnAttempt(anchors, state.errorAttempt, errors, SignUpField.entries)
    AuthLayout(
        title = stringResource(R.string.title_sign_up),
        onBack = onBack,
        actions = { LanguageToggle(onLanguageChange) },
        bottomBar = {
            // Low 6: "already has an account" makes Sign in (with this email) the main button;
            // Create account would only fail again. Editing the email brings it back.
            val inUse = state.emailError == R.string.error_email_in_use
            LLBottomBar(
                primaryText = stringResource(if (inUse) R.string.action_sign_in else R.string.action_create_account),
                onPrimary = if (inUse) dropUnlessResumed(block = onSignInWithEmail) else onSignUp,
                loading = state.loading,
                loadingText = stringResource(R.string.action_creating_account),
            )
        },
    ) {
        // P2: a new Google account skips this form and confirms 18+ and the Terms in setup.
        if (onContinueWithGoogle != null) GoogleOption(onContinueWithGoogle, enabled = !state.loading)
        LLTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = stringResource(R.string.label_full_name),
            errorText = state.nameError?.let { stringResource(it) },
            enabled = !state.loading,
            contentType = ContentType.PersonFullName,
            fieldModifier = Modifier.errorAnchor(anchors, SignUpField.NAME, textField = true),
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
            fieldModifier = Modifier.errorAnchor(anchors, SignUpField.EMAIL, textField = true),
        )
        // FORM-04 and Low 6: "Sign in instead" is the bar's main button while the email is in use.
        // D24: typed twice; Next goes to the second box.
        NewPasswordField(
            password = state.password,
            onValueChange = onPasswordChange,
            onFocusChange = onPasswordFocus,
            label = stringResource(R.string.label_password),
            errorText = state.passwordError?.let { stringResource(it) },
            enabled = !state.loading,
            fieldModifier = Modifier.errorAnchor(anchors, SignUpField.PASSWORD, textField = true),
        )
        RetypePasswordField(
            password = state.password,
            onValueChange = onPasswordAgainChange,
            onFocusChange = onPasswordAgainFocus,
            label = stringResource(R.string.label_confirm_password),
            enabled = !state.loading,
            keyboardActions = keyboardDoneShows(anchors, SignUpField.CONSENT), // VT-13: then the 18+ box
            fieldModifier = Modifier.errorAnchor(anchors, SignUpField.PASSWORD_AGAIN, textField = true),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .errorAnchor(anchors, SignUpField.CONSENT)
                .fillMaxWidth()
                .toggleable(value = state.consent, enabled = !state.loading, role = Role.Checkbox, onValueChange = onConsentChange),
        ) {
            Checkbox(checked = state.consent, onCheckedChange = null)
            Text(stringResource(R.string.sign_up_consent), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.xs))
        }
        state.consentError?.let { FormError(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            EdgeTextButton(stringResource(R.string.action_read_terms), { links.openUrl(Links.terms(locale)) })
            TextButton(onClick = { links.openUrl(Links.privacy(locale)) }) { Text(stringResource(R.string.action_read_privacy)) }
        }
        FormError(state.formError)
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
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
    // FLOW-09: each time the person comes back (from the email app), check by itself.
    LifecycleResumeEffect(viewModel) {
        viewModel.checkQuietly()
        onPauseOrDispose { }
    }
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
        // Gmail often files Firebase's first mail from a new project under Spam or Promotions (A15).
        Text(
            listOf(emailSpamHint(state.sender), stringResource(R.string.verify_email_resend_hint)).joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // VT-12: opening the email is the next step, so it's the main button; coming back checks by
        // itself (FLOW-09). "I've verified" stays as the second way.
        LLPrimaryButton(
            text = stringResource(R.string.action_open_email_app),
            onClick = { intents.openEmailApp() },
        )
        OutlinedButton(onClick = onCheckVerified, enabled = !state.checking, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
            ButtonLabel(stringResource(if (state.checking) R.string.action_checking else R.string.action_i_have_verified))
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

/** "Can't find it? Check Spam and Promotions. It comes from …" (the sender only when known). */
@Composable
private fun emailSpamHint(sender: String): String =
    stringResource(R.string.email_spam_hint) + if (sender.isNotBlank()) " " + stringResource(R.string.email_sender, sender) else ""

// ---- Forgot password ----

@Composable
fun ForgotPasswordRoute(onBack: () -> Unit, viewModel: ForgotPasswordViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForgotPasswordScreen(state, viewModel::onEmailChange, viewModel::send, onBack, onUseDifferentEmail = viewModel::useDifferentEmail)
}

@Composable
fun ForgotPasswordScreen(
    state: ForgotPasswordUiState,
    onEmailChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onUseDifferentEmail: () -> Unit = {},
) {
    // UI-05 and UI-07: the main action is pinned; "Back to sign in" lives in the same bar before and
    // after sending (it was a text button on one screen and a tonal button on the next).
    val back = dropUnlessResumed(block = onBack)
    AuthLayout(
        title = stringResource(R.string.title_forgot_password),
        onBack = onBack,
        bottomBar = {
            if (state.sentTo == null) {
                LLBottomBar(
                    primaryText = stringResource(R.string.action_send_link),
                    onPrimary = onSend,
                    loading = state.loading,
                    loadingText = stringResource(R.string.action_sending),
                    secondaryText = stringResource(R.string.action_back_to_sign_in),
                    onSecondary = back,
                )
            } else {
                LLBottomBar(primaryText = stringResource(R.string.action_back_to_sign_in), onPrimary = back)
            }
        },
    ) {
        val sentTo = state.sentTo
        if (sentTo != null) {
            // Same words whether or not the account exists (no email probing).
            EmptyState(
                icon = LLIcons.CheckCircle,
                title = stringResource(R.string.forgot_password_sent_title),
                body = stringResource(R.string.forgot_password_sent_body, sentTo) + " " + emailSpamHint(state.sender),
            )
            // Low 25: not there? Send it again, or fix the address.
            TextButton(onClick = onSend, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
                ButtonLabel(stringResource(R.string.action_resend_email))
            }
            TextButton(onClick = onUseDifferentEmail, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.minTouchTarget)) {
                ButtonLabel(stringResource(R.string.action_use_different_email))
            }
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
    }
}
