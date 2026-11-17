package io.github.liquidlyf.feature.profilesetup

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.auth.AuthUser
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Links
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The fields the screens check; they mark the ones in the state's errors. */
enum class SetupField { BLOOD_GROUP, NAME, GENDER, BIRTH_YEAR, WEIGHT, LOCATION, PHONE, LAST_DONATION, CONSENT, TERMS }

/** The short setup's fields, in the order they appear (the first error is brought into view). */
internal val SHORT_SETUP_ORDER = listOf(SetupField.NAME, SetupField.PHONE, SetupField.LOCATION, SetupField.TERMS)

data class ProfileSetupUiState(
    val draft: SetupDraft,
    val geo: BdGeo? = null,
    val loaded: Boolean = false,
    val errors: Set<SetupField> = emptySet(),
    /** Counts failed Continue taps: each one brings the first error into view (FLOW-04). */
    val errorAttempt: Int = 0,
    val saving: Boolean = false,
    @StringRes val formError: Int? = null,
    /** Google sign-ins never saw the sign-up form's 18+ and Terms box: this screen asks. */
    val needsTerms: Boolean = false,
)

/**
 * The short setup (FLOW-01, D1 = a, N3): name, mobile number and area on one screen, so someone who
 * needs blood can ask for it in a minute. Donor questions come later, only in "Become a donor". The
 * answers are saved locally as they're typed; Continue writes users and userStats in one batch (the
 * rules need the phone for that), then the session moves on to the app.
 */
@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val geoRepository: GeoRepository,
    private val store: SetupDraftStore,
) : ViewModel() {

    private val user: AuthUser? = (session.state.value as? SessionState.NeedsProfile)?.user

    private val state = MutableStateFlow(
        ProfileSetupUiState(SetupDraft(uid = user?.uid.orEmpty()), needsTerms = user?.hasPassword == false),
    )
    val uiState: StateFlow<ProfileSetupUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            val draft = user?.let { store.load(it.uid) }
                ?: SetupDraft(uid = user?.uid.orEmpty(), fullName = user?.displayName.orEmpty())
            state.update { it.copy(draft = draft, geo = geo, loaded = true) }
        }
    }

    /**
     * Any answer: clears the errors so the user sees fresh feedback on Continue, and is saved at once,
     * so a kill (or Android closing the app in the background) keeps it.
     */
    fun edit(change: SetupDraft.() -> SetupDraft) {
        state.update { it.copy(draft = it.draft.change(), errors = emptySet(), formError = null) }
        saveDraft()
    }

    fun onDivision(code: String) = edit { copy(division = code, district = null, upazila = null) }

    fun onDistrict(code: String) = edit { copy(district = code, upazila = null) }

    fun next(locale: AppLocale) {
        val s = state.value
        if (s.saving || !s.loaded) return
        val errors = validate(s.draft, s.geo)
        if (errors.isNotEmpty()) {
            state.update { it.copy(errors = errors, errorAttempt = it.errorAttempt + 1) }
            return
        }
        finish(s.draft, locale)
    }

    /** Not before the saved draft is loaded (it would be overwritten), nor once the profile is being written. */
    private fun saveDraft() {
        val s = state.value
        if (!s.loaded || s.saving) return
        viewModelScope.launch { store.save(s.draft) }
    }

    internal fun validate(d: SetupDraft, geo: BdGeo?): Set<SetupField> = buildSet {
        if (!Validators.isValidName(d.fullName)) add(SetupField.NAME)
        if (Validators.normalizePhone(d.phone) == null) add(SetupField.PHONE)
        val area = areaOf(d)
        if (area == null || geo?.isValid(area) != true) add(SetupField.LOCATION)
        if (state.value.needsTerms && !d.termsAccepted) add(SetupField.TERMS)
    }

    /** A requester's profile (D1): no blood group or donor details yet, so not listed and not available. */
    private fun finish(d: SetupDraft, locale: AppLocale) {
        val uid = user?.uid ?: return
        val draft = ProfileDraft(
            fullName = Validators.normalizeName(d.fullName),
            phone = Validators.normalizePhone(d.phone)!!,
            bloodGroup = null,
            gender = null,
            birthYear = null,
            weightOk = null,
            area = areaOf(d)!!,
            available = false,
            lastDonationAt = null,
            showInDonorSearch = false,
            locale = locale,
            termsVersion = Links.TERMS_VERSION,
        )
        state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            when (val result = profiles.create(uid, user?.email.orEmpty(), draft)) {
                is AppResult.Success -> {
                    store.clear()
                    // Stay "saving" while the session moves on to the app.
                    session.refresh()
                }
                is AppResult.Failure -> state.update { it.copy(saving = false, formError = result.error.messageRes) }
            }
        }
    }

    private fun areaOf(d: SetupDraft): Area? {
        val division = d.division ?: return null
        val district = d.district ?: return null
        val upazila = d.upazila ?: return null
        return Area(division, district, upazila)
    }
}
