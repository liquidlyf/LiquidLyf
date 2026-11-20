package io.github.liquidlyf.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.account.DeletionStep
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.BloodGroupPicker
import io.github.liquidlyf.core.designsystem.components.ChoiceRow
import io.github.liquidlyf.core.designsystem.components.DialogBody
import io.github.liquidlyf.core.designsystem.components.DialogText
import io.github.liquidlyf.core.designsystem.components.EdgeTextButton
import io.github.liquidlyf.core.designsystem.components.FieldError
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.components.LLNoticeCard
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTextField
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.designsystem.components.NewPasswordField
import io.github.liquidlyf.core.designsystem.components.RetypePasswordField
import io.github.liquidlyf.core.designsystem.components.ShowFirstErrorOnAttempt
import io.github.liquidlyf.core.designsystem.components.errorAnchor
import io.github.liquidlyf.core.designsystem.components.rememberErrorAnchors
import io.github.liquidlyf.core.designsystem.labelRes
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.domain.FieldCheck
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.feature.auth.AuthLayout
import io.github.liquidlyf.feature.auth.FormError
import io.github.liquidlyf.core.designsystem.currentAppLocale
import io.github.liquidlyf.feature.profilesetup.AreaPickers
import io.github.liquidlyf.feature.settings.ListMeDialog

// ---- Change password ----

private enum class ChangePasswordField { CURRENT, NEW, NEW_AGAIN }

@Composable
fun ChangePasswordRoute(onDone: () -> Unit, viewModel: ChangePasswordViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // D24: a failed Save brings the first red box into view.
    val anchors = rememberErrorAnchors<ChangePasswordField>()
    val errors = buildSet {
        if (state.currentError != null) add(ChangePasswordField.CURRENT)
        if (state.newError != null || state.newPassword.valueCheck == FieldCheck.ERROR) add(ChangePasswordField.NEW)
        if (state.newPassword.againCheck == FieldCheck.ERROR) add(ChangePasswordField.NEW_AGAIN)
    }
    ShowFirstErrorOnAttempt(anchors, state.errorAttempt, errors, ChangePasswordField.entries)
    val snackbar = LocalSnackbarHostState.current
    val doneText = stringResource(R.string.password_changed)
    LaunchedEffect(state.done) {
        if (state.done) {
            snackbar.showSnackbar(doneText)
            onDone()
        }
    }
    AuthLayout(
        title = stringResource(R.string.title_change_password),
        onBack = onDone,
        bottomBar = {
            LLBottomBar(stringResource(R.string.action_save), viewModel::save, loading = state.saving, loadingText = stringResource(R.string.action_saving))
        },
    ) {
        LLTextField(
            value = state.current,
            onValueChange = viewModel::onCurrentChange,
            label = stringResource(R.string.label_current_password),
            errorText = state.currentError?.let { stringResource(it) },
            isPassword = true,
            contentType = ContentType.Password,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            fieldModifier = Modifier.errorAnchor(anchors, ChangePasswordField.CURRENT, textField = true),
        )
        // Low 25: forgot the current one? The reset link goes to this account's email.
        val sentTo = state.resetSentTo
        if (sentTo == null) {
            EdgeTextButton(stringResource(R.string.action_forgot_password), viewModel::sendReset, enabled = !state.saving)
        } else {
            LLNoticeCard(stringResource(R.string.reset_link_sent_to, sentTo), LLIcons.CheckCircle)
        }
        // D24: typed twice; Next goes to the second box.
        NewPasswordField(
            password = state.newPassword,
            onValueChange = viewModel::onNewChange,
            onFocusChange = viewModel::onNewFocus,
            label = stringResource(R.string.label_new_password),
            errorText = state.newError?.let { stringResource(it) },
            fieldModifier = Modifier.errorAnchor(anchors, ChangePasswordField.NEW, textField = true),
        )
        RetypePasswordField(
            password = state.newPassword,
            onValueChange = viewModel::onNewAgainChange,
            onFocusChange = viewModel::onNewAgainFocus,
            label = stringResource(R.string.label_confirm_new_password),
            fieldModifier = Modifier.errorAnchor(anchors, ChangePasswordField.NEW_AGAIN, textField = true),
        )
        FormError(state.formError)
    }
}

// ---- Delete account: confirm ----

@Composable
fun DeleteAccountRoute(onBack: () -> Unit, viewModel: DeleteAccountViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val word = stringResource(R.string.delete_account_word)
    AuthLayout(title = stringResource(R.string.title_delete_account), onBack = onBack) {
        Text(stringResource(R.string.delete_account_body), style = MaterialTheme.typography.bodyLarge)
        LLTextField(
            value = state.word,
            onValueChange = viewModel::onWordChange,
            label = stringResource(R.string.delete_account_type, word),
            errorText = if (state.wordError) stringResource(R.string.error_confirm_word, word) else null,
            enabled = !state.working,
            keyboardOptions = KeyboardOptions(imeAction = if (state.needsPassword) ImeAction.Next else ImeAction.Done),
        )
        if (state.needsPassword) {
            LLTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = stringResource(R.string.label_password),
                supportingText = stringResource(R.string.delete_account_password_hint),
                errorText = state.passwordError?.let { stringResource(it) },
                enabled = !state.working,
                isPassword = true,
                contentType = ContentType.Password,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
        }
        FormError(state.formError)
        OutlinedButton(
            onClick = { viewModel.confirm() },
            enabled = !state.working,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_delete_account))
        }
    }
}

// ---- Delete account: running ----

@Composable
fun DeletingAccountRoute(viewModel: DeletingAccountViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = screenGutter(), vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(if (state.error != null) R.string.deleting_failed_title else R.string.deleting_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (state.running) {
                CircularProgressIndicator()
                Text(stringResource(state.step?.labelRes ?: R.string.deleting_step_offers), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.deleting_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
            } else {
                // P2-14: while the password is needed, one calm line (the red "sign in again" said the
                // opposite); a wrong password still shows its error.
                if (!(state.needsPassword && state.error == R.string.error_recent_login_required)) FormError(state.error)
                if (state.needsPassword) {
                    Text(stringResource(R.string.deleting_sign_in_again), textAlign = TextAlign.Center)
                    LLTextField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = stringResource(R.string.label_password),
                        isPassword = true,
                        contentType = ContentType.Password,
                        modifier = Modifier.widthIn(max = 480.dp),
                    )
                    LLPrimaryButton(text = stringResource(R.string.action_continue), onClick = viewModel::run, modifier = Modifier.widthIn(max = 480.dp))
                    // P2-14: a way out. Signing out leads to Sign in, where "Forgot password?" resets it;
                    // after signing in again the deletion carries on.
                    TextButton(onClick = viewModel::signOutToSignInAgain) { Text(stringResource(R.string.action_forgot_password)) }
                } else {
                    LLPrimaryButton(text = stringResource(R.string.action_retry), onClick = viewModel::run, modifier = Modifier.widthIn(max = 480.dp))
                    TextButton(onClick = viewModel::signOutToSignInAgain) { Text(stringResource(R.string.action_sign_out)) }
                }
            }
        }
    }
}

private val DeletionStep.labelRes: Int
    get() = when (this) {
        DeletionStep.OFFERS_WITHDRAWN -> R.string.deleting_step_requests
        DeletionStep.REQUESTS_ANONYMIZED -> R.string.deleting_step_offers_named
        DeletionStep.OFFERS_ANONYMIZED -> R.string.deleting_step_private
        DeletionStep.PRIVATE_DATA_DELETED, DeletionStep.PROFILE_DELETED -> R.string.deleting_step_profile
    }

// ---- Edit profile ----

/** Edit profile's fields in screen order, for FLOW-04. */
private enum class EditField { NAME, PHONE, BIRTH_YEAR, LOCATION }

@Composable
fun EditProfileRoute(onDone: () -> Unit, viewModel: EditProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentAppLocale()
    val snackbar = LocalSnackbarHostState.current
    val savedText = stringResource(R.string.profile_saved)
    val message = state.message?.let { res -> state.messageArg?.let { stringResource(res, it) } ?: stringResource(res) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            snackbar.showSnackbar(savedText)
            onDone()
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    var changingEmail by rememberSaveable { mutableStateOf(false) }
    var newEmail by rememberSaveable { mutableStateOf("") }
    // FLOW-04: a failed Save brings the first error into view.
    val anchors = rememberErrorAnchors<EditField>()
    val errors = buildSet {
        if (state.nameError != null) add(EditField.NAME)
        if (state.phoneError != null) add(EditField.PHONE)
        if (state.birthYearError != null) add(EditField.BIRTH_YEAR)
        if (state.locationError) add(EditField.LOCATION)
    }
    ShowFirstErrorOnAttempt(anchors, state.errorAttempt, errors, EditField.entries)

    // UI-05: Save is pinned at the bottom, as on profile setup (it was at the end of a long form).
    AuthLayout(
        title = stringResource(R.string.title_edit_profile),
        onBack = onDone,
        bottomBar = {
            LLBottomBar(stringResource(R.string.action_save), viewModel::save, loading = state.saving, loadingText = stringResource(R.string.action_saving))
        },
    ) {
        LLTextField(
            value = state.fullName,
            onValueChange = { v -> viewModel.edit { copy(fullName = v) } },
            label = stringResource(R.string.label_full_name),
            errorText = state.nameError?.let { stringResource(it) },
            contentType = ContentType.PersonFullName,
            fieldModifier = Modifier.errorAnchor(anchors, EditField.NAME, textField = true),
        )
        if (state.googleOnly) {
            // D30: changing it would leave Google on the old address and no way to use the new one.
            Text(stringResource(R.string.label_email_google, state.email), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.label_email_readonly, state.email), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { changingEmail = true }) { Text(stringResource(R.string.action_change_email)) }
        }
        LLTextField(
            value = state.phone,
            onValueChange = { v -> viewModel.edit { copy(phone = v.take(20)) } },
            label = stringResource(R.string.label_phone),
            supportingText = stringResource(R.string.phone_hint),
            errorText = state.phoneError?.let { stringResource(it) },
            contentType = ContentType.PhoneNumber,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            fieldModifier = Modifier.errorAnchor(anchors, EditField.PHONE, textField = true),
        )
        // D1: gender and birth year are donor questions; someone who isn't a donor yet answers
        // them in "Become a donor", not here.
        if (state.isDonor) {
            Text(stringResource(R.string.label_gender), style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                options = Gender.entries.map { it.code to stringResource(it.labelRes) },
                selected = state.gender?.code,
                onSelect = { code -> viewModel.edit { copy(gender = Gender.fromCode(code)) } },
            )
            LLTextField(
                value = state.birthYear,
                onValueChange = { v -> viewModel.edit { copy(birthYear = v.filter(Char::isDigit).take(4)) } },
                label = stringResource(R.string.label_birth_year),
                errorText = state.birthYearError?.let { stringResource(it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                fieldModifier = Modifier.errorAnchor(anchors, EditField.BIRTH_YEAR, textField = true),
            )
            // D23 a: the weight answer can change here (after জন্মসাল, as in Become a donor).
            Text(stringResource(R.string.setup_weight_question), style = MaterialTheme.typography.labelLarge)
            ChoiceRow(
                options = listOf("yes" to stringResource(R.string.answer_yes), "no" to stringResource(R.string.answer_no)),
                selected = state.weightOk?.let { if (it) "yes" else "no" },
                onSelect = { v -> viewModel.edit { copy(weightOk = v == "yes") } },
            )
        }
        BloodGroupPicker(selected = state.bloodGroup, onSelect = { g -> viewModel.edit { copy(bloodGroup = g) } })
        Column(Modifier.errorAnchor(anchors, EditField.LOCATION)) {
            state.geo?.let { geo ->
                AreaPickers(
                    geo = geo,
                    locale = locale,
                    division = state.division,
                    district = state.district,
                    upazila = state.upazila,
                    onDivision = viewModel::onDivision,
                    onDistrict = viewModel::onDistrict,
                    onUpazila = { code -> viewModel.edit { copy(upazila = code) } },
                )
            }
            FieldError(state.locationError, stringResource(R.string.error_location_required))
        }
        FormError(state.formError)
    }

    // D23 a: the weight answer is back to হ্যাঁ: the same "list me" question as Become a donor.
    val listMeGroup = state.bloodGroup
    if (state.askListMe && listMeGroup != null) {
        ListMeDialog(
            bloodGroup = listMeGroup,
            onConfirm = { viewModel.onListMeAnswered(true) },
            onDismiss = { viewModel.onListMeAnswered(false) },
            title = stringResource(R.string.home_eligible_now),
        )
    }
    if (state.confirmBloodGroup && !state.saving) {
        AlertDialog(
            onDismissRequest = { viewModel.onBloodGroupConfirmed(false) },
            title = { Text(stringResource(R.string.confirm_blood_group_title)) },
            text = {
                DialogBody {
                    val from = state.previousBloodGroup
                    val to = state.bloodGroup
                    if (from != null && to != null) {
                        Text(stringResource(R.string.confirm_blood_group_from_to, stringResource(from.labelRes), stringResource(to.labelRes)), fontWeight = FontWeight.Medium)
                    }
                    Text(stringResource(R.string.confirm_blood_group_body))
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.onBloodGroupConfirmed(true) }) { Text(stringResource(R.string.action_change)) } },
            dismissButton = { TextButton(onClick = { viewModel.onBloodGroupConfirmed(false) }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (changingEmail) {
        AlertDialog(
            onDismissRequest = { changingEmail = false },
            title = { Text(stringResource(R.string.action_change_email)) },
            text = {
                LLTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    label = stringResource(R.string.label_new_email),
                    contentType = ContentType.EmailAddress,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.changeEmail(newEmail)
                    changingEmail = false
                }) { Text(stringResource(R.string.action_send_link)) }
            },
            dismissButton = { TextButton(onClick = { changingEmail = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
