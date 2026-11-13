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
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.core.util.Links
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/** The fields a step checks; the screen marks the ones in [ProfileSetupUiState.errors]. */
enum class SetupField { BLOOD_GROUP, NAME, GENDER, BIRTH_YEAR, WEIGHT, LOCATION, PHONE, LAST_DONATION, CONSENT }

data class ProfileSetupUiState(
    val draft: SetupDraft,
    val geo: BdGeo? = null,
    val loaded: Boolean = false,
    val errors: Set<SetupField> = emptySet(),
    val saving: Boolean = false,
    @StringRes val formError: Int? = null,
) {
    val step: Int get() = draft.step
    val isLastStep: Boolean get() = draft.step == ProfileSetupViewModel.STEPS - 1
}

/**
 * The 4-step profile setup (Phase 3.8): blood group · about you · area · phone, last donation and
 * the donor-listing consent. The answers are saved locally after every step. The last step writes
 * users, userStats and (if listed) the donor card in one batch; the rules need the phone for that,
 * so nothing reaches Firestore before it. Then the session moves on to the app.
 */
@HiltViewModel
class ProfileSetupViewModel @Inject constructor(
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val geoRepository: GeoRepository,
    private val store: SetupDraftStore,
    private val clock: Clock,
) : ViewModel() {

    private val user: AuthUser? = (session.state.value as? SessionState.NeedsProfile)?.user

    private val state = MutableStateFlow(ProfileSetupUiState(SetupDraft(uid = user?.uid.orEmpty())))
    val uiState: StateFlow<ProfileSetupUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            val draft = user?.let { store.load(it.uid) }
                ?: SetupDraft(uid = user?.uid.orEmpty(), fullName = user?.displayName.orEmpty())
            state.update { it.copy(draft = draft, geo = geo, loaded = true) }
        }
    }

    /** Any answer: clears the errors so the user sees fresh feedback on Next. */
    fun edit(change: SetupDraft.() -> SetupDraft) =
        state.update { it.copy(draft = it.draft.change(), errors = emptySet(), formError = null) }

    fun onDivision(code: String) = edit { copy(division = code, district = null, upazila = null) }

    fun onDistrict(code: String) = edit { copy(district = code, upazila = null) }

    fun next(locale: AppLocale) {
        val s = state.value
        if (s.saving || !s.loaded) return
        val errors = validate(s.draft, s.geo)
        if (errors.isNotEmpty()) {
            state.update { it.copy(errors = errors) }
            return
        }
        if (s.isLastStep) {
            finish(s.draft, locale)
        } else {
            moveTo(s.step + 1)
        }
    }

    /** Back inside the stepper; false at the first step (the system then closes the app). */
    fun back(): Boolean {
        val step = state.value.step
        if (step == 0 || state.value.saving) return false
        moveTo(step - 1)
        return true
    }

    private fun moveTo(step: Int) {
        state.update { it.copy(draft = it.draft.copy(step = step), errors = emptySet()) }
        viewModelScope.launch { store.save(state.value.draft) }
    }

    internal fun validate(d: SetupDraft, geo: BdGeo?): Set<SetupField> = buildSet {
        when (d.step) {
            0 -> if (BloodGroup.fromCode(d.bloodGroup) == null) add(SetupField.BLOOD_GROUP)
            1 -> {
                if (!Validators.isValidName(d.fullName)) add(SetupField.NAME)
                if (Gender.fromCode(d.gender) == null) add(SetupField.GENDER)
                val year = d.birthYear.trim().toIntOrNull()
                if (year == null || !Validators.isValidBirthYear(year, clock.now())) add(SetupField.BIRTH_YEAR)
                if (d.weightOk == null) add(SetupField.WEIGHT)
            }
            2 -> {
                val area = areaOf(d)
                if (area == null || geo?.isValid(area) != true) add(SetupField.LOCATION)
            }
            3 -> {
                if (Validators.normalizePhone(d.phone) == null) add(SetupField.PHONE)
                val pickedDay = d.lastDonationEpochDay
                val today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
                val donationOk = d.neverDonated == true || (d.neverDonated == false && pickedDay != null && pickedDay <= today)
                if (!donationOk) add(SetupField.LAST_DONATION)
                if (d.listed == null) add(SetupField.CONSENT)
            }
        }
    }

    private fun finish(d: SetupDraft, locale: AppLocale) {
        val uid = user?.uid ?: return
        val listed = d.listed == true
        val draft = ProfileDraft(
            fullName = Validators.normalizeName(d.fullName),
            phone = Validators.normalizePhone(d.phone)!!,
            bloodGroup = BloodGroup.fromCode(d.bloodGroup)!!,
            gender = Gender.fromCode(d.gender)!!,
            birthYear = d.birthYear.trim().toInt(),
            weightOk = d.weightOk == true,
            area = areaOf(d)!!,
            available = listed && d.available,
            lastDonationAt = if (d.neverDonated == true) null else d.lastDonationEpochDay?.let(::donationInstant),
            showInDonorSearch = listed,
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

    /** Midday in Bangladesh on the picked day, but never in the future (the rules check it). */
    private fun donationInstant(epochDay: Long): Instant {
        val midday = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(6))
        val latest = clock.now().minus(Duration.ofMinutes(5))
        return if (midday.isAfter(latest)) latest else midday
    }

    companion object {
        const val STEPS = 4
    }
}
