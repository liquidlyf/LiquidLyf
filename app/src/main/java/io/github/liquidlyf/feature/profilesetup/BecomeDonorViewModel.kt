package io.github.liquidlyf.feature.profilesetup

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.components.AppSnackbar
import io.github.liquidlyf.core.designsystem.components.AppSnackbars
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

data class BecomeDonorUiState(
    val draft: SetupDraft,
    /** For "Your area: …" on the List me step (VT-21). */
    val geo: BdGeo? = null,
    val errors: Set<SetupField> = emptySet(),
    /** Counts failed Next taps: each one brings the first error into view (FLOW-04). */
    val errorAttempt: Int = 0,
    val saving: Boolean = false,
    @StringRes val formError: Int? = null,
) {
    val step: Int get() = draft.step
    val isLastStep: Boolean get() = draft.step == BecomeDonorViewModel.STEPS - 1
}

sealed interface BecomeDonorEvent {
    /** Saved: back to where the person came from. */
    data object Done : BecomeDonorEvent
}

/** The donor steps' fields, in the order they appear. */
internal val DONOR_ORDER = listOf(
    SetupField.BLOOD_GROUP, SetupField.GENDER, SetupField.BIRTH_YEAR, SetupField.WEIGHT, SetupField.LAST_DONATION, SetupField.CONSENT,
)

/**
 * "Become a donor" (D1, N3, N6): the donor questions the short setup left out, in 3 steps: blood
 * group · gender, birth year and weight · last donation and the listing consent. One profile edit
 * adds them all (B's contract: all three details together, and with "list me" the same batch sets
 * consentAt and the donor card). Opened from Home's "Want to donate too?", Profile, Settings and
 * "I can donate".
 */
@HiltViewModel
class BecomeDonorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val clock: Clock,
    geoRepository: GeoRepository,
) : ViewModel() {

    private val profile: UserProfile? = (session.state.value as? SessionState.Ready)?.profile

    private val state = MutableStateFlow(BecomeDonorUiState(restore()))
    val uiState: StateFlow<BecomeDonorUiState> = state.asStateFlow()

    private val events = Channel<BecomeDonorEvent>(Channel.BUFFERED)
    val eventFlow: Flow<BecomeDonorEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo) }
        }
    }

    /** Any answer: clears the errors, and is kept for rotation and process death. */
    fun edit(change: SetupDraft.() -> SetupDraft) {
        state.update { it.copy(draft = it.draft.change(), errors = emptySet(), formError = null) }
        savedState[DRAFT_KEY] = JSON.encodeToString(SetupDraft.serializer(), state.value.draft)
    }

    /** "I don't know my blood group" → "Skip for now" (P1): the details are saved, without listing. */
    fun skipBloodGroup() {
        edit { copy(bloodGroup = null, bloodGroupUnknown = true) }
        next()
    }

    fun next() {
        val s = state.value
        if (s.saving) return
        val errors = validate(s.draft)
        if (errors.isNotEmpty()) {
            state.update { it.copy(errors = errors, errorAttempt = it.errorAttempt + 1) }
            return
        }
        if (s.isLastStep) finish(s.draft) else edit { copy(step = step + 1) }
    }

    /** Back inside the steps; false on the first one (the screen then closes). */
    fun back(): Boolean {
        val s = state.value
        if (s.step == 0 || s.saving) return false
        edit { copy(step = step - 1) }
        return true
    }

    internal fun validate(d: SetupDraft): Set<SetupField> = buildSet {
        when (d.step) {
            0 -> if (BloodGroup.fromCode(d.bloodGroup) == null && !d.bloodGroupUnknown) add(SetupField.BLOOD_GROUP)
            1 -> {
                if (Gender.fromCode(d.gender) == null) add(SetupField.GENDER)
                val year = d.birthYear.trim().toIntOrNull()
                if (year == null || !Validators.isValidBirthYear(year, clock.now())) add(SetupField.BIRTH_YEAR)
                if (d.weightOk == null) add(SetupField.WEIGHT)
            }
            else -> {
                val pickedDay = d.lastDonationEpochDay
                val today = clock.now().atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
                val donationOk = d.neverDonated == true || (d.neverDonated == false && pickedDay != null && pickedDay <= today)
                if (!donationOk) add(SetupField.LAST_DONATION)
                // Without a known group there is no listing question (P1).
                if (d.listed == null && BloodGroup.fromCode(d.bloodGroup) != null) add(SetupField.CONSENT)
            }
        }
    }

    private fun finish(d: SetupDraft) {
        val before = profile ?: return
        val group = BloodGroup.fromCode(d.bloodGroup) ?: before.bloodGroup
        val listed = d.listed == true && group != null
        val draft = ProfileDraft(
            fullName = before.fullName,
            phone = before.phone,
            bloodGroup = group,
            gender = Gender.fromCode(d.gender),
            birthYear = d.birthYear.trim().toIntOrNull(),
            weightOk = d.weightOk,
            area = before.area,
            available = listed && d.available,
            lastDonationAt = if (d.neverDonated == true) null else d.lastDonationEpochDay?.let(::donationInstant),
            showInDonorSearch = listed,
            locale = before.locale,
            termsVersion = before.termsVersion,
        )
        state.update { it.copy(saving = true, formError = null) }
        viewModelScope.launch {
            when (val result = profiles.update(before, draft)) {
                is AppResult.Success -> {
                    // The session re-reads the profile; the alert topics follow it (PushTopicManager).
                    session.refresh()
                    savedState.remove<String>(DRAFT_KEY)
                    AppSnackbars.show(AppSnackbar(R.string.become_donor_done))
                    events.send(BecomeDonorEvent.Done)
                }
                is AppResult.Failure -> state.update { it.copy(saving = false, formError = result.error.messageRes) }
            }
        }
    }

    private fun restore(): SetupDraft =
        savedState.get<String>(DRAFT_KEY)?.let { runCatching { JSON.decodeFromString(SetupDraft.serializer(), it) }.getOrNull() }
            ?: SetupDraft(uid = profile?.uid.orEmpty(), bloodGroup = profile?.bloodGroup?.code)

    /** Midday in Bangladesh on the picked day, but never in the future (the rules check it). */
    private fun donationInstant(epochDay: Long): Instant {
        val midday = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(6))
        val latest = clock.now().minus(Duration.ofMinutes(5))
        return if (midday.isAfter(latest)) latest else midday
    }

    companion object {
        const val STEPS = 3
        private const val DRAFT_KEY = "become_donor_draft"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
