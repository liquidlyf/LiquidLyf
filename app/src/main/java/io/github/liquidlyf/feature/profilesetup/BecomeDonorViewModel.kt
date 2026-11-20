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
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.IneligibleReason
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.Area
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
    /** D18: the profile's own area. The donor steps don't ask for it, so their draft has none. */
    val area: Area? = null,
    val errors: Set<SetupField> = emptySet(),
    /** Counts failed Next taps: each one brings the first error into view (FLOW-04). */
    val errorAttempt: Int = 0,
    val saving: Boolean = false,
    @StringRes val formError: Int? = null,
    /** D23 a: weight না or age outside 18–60: step 3 says why instead of asking "list me". */
    val listingBlock: IneligibleReason? = null,
) {
    val step: Int get() = draft.step
    val isLastStep: Boolean get() = draft.step == BecomeDonorViewModel.STEPS - 1
}

sealed interface BecomeDonorEvent {
    /** Saved: back to where the person came from. */
    data object Done : BecomeDonorEvent

    /** D28: "Need blood now? Skip this" in setup: the answers so far stay on this phone only. */
    data object Skipped : BecomeDonorEvent
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
 * "I can donate", and (D28) as steps 2-4 of setup. The answers so far are kept on this phone only,
 * per account and never sent, until Finish (or sign-out): someone who stops or skips picks up where
 * they were from "Want to donate too?".
 */
@HiltViewModel
class BecomeDonorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val clock: Clock,
    geoRepository: GeoRepository,
    private val eligibility: EligibilityCalculator,
    private val store: SetupDraftStore,
) : ViewModel() {

    private val profile: UserProfile? = (session.state.value as? SessionState.Ready)?.profile

    private val state = MutableStateFlow(restore().let { d -> BecomeDonorUiState(d, area = profile?.area, listingBlock = blockOf(d)) })
    val uiState: StateFlow<BecomeDonorUiState> = state.asStateFlow()

    private val events = Channel<BecomeDonorEvent>(Channel.BUFFERED)
    val eventFlow: Flow<BecomeDonorEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo) }
        }
        // D28: the answers kept on this phone (skipped in setup, or stopped half way).
        if (savedState.get<String>(DRAFT_KEY) == null) {
            viewModelScope.launch {
                val kept = profile?.uid?.let { store.load(it) }?.takeIf { it.hasDonorAnswers } ?: return@launch
                val draft = kept.copy(step = kept.step.coerceIn(0, STEPS - 1), bloodGroup = kept.bloodGroup ?: profile?.bloodGroup?.code)
                state.update { it.copy(draft = draft, listingBlock = blockOf(draft)) }
            }
        }
    }

    /** Any answer: clears the errors, and is kept for rotation and process death, and on this phone (D28). */
    fun edit(change: SetupDraft.() -> SetupDraft) {
        state.update { s -> s.draft.change().let { d -> s.copy(draft = d, errors = emptySet(), formError = null, listingBlock = blockOf(d)) } }
        val draft = state.value.draft
        savedState[DRAFT_KEY] = JSON.encodeToString(SetupDraft.serializer(), draft)
        if (draft.uid.isNotEmpty()) viewModelScope.launch { store.save(draft) }
    }

    /** D28: "Need blood now? Skip this" in setup: nothing is sent; the answers stay on this phone. */
    fun skip() {
        if (state.value.saving) return
        val draft = state.value.draft
        viewModelScope.launch {
            if (draft.uid.isNotEmpty()) store.save(draft)
            events.send(BecomeDonorEvent.Skipped)
        }
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
                // Without a known group there is no listing question (P1), nor for someone who can't be listed (D23).
                if (d.listed == null && BloodGroup.fromCode(d.bloodGroup) != null && blockOf(d) == null) add(SetupField.CONSENT)
            }
        }
    }

    private fun finish(d: SetupDraft) {
        val before = profile ?: return
        val group = BloodGroup.fromCode(d.bloodGroup) ?: before.bloodGroup
        val listed = d.listed == true && group != null && blockOf(d) == null
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
                    store.clear()
                    AppSnackbars.show(AppSnackbar(R.string.become_donor_done))
                    events.send(BecomeDonorEvent.Done)
                }
                is AppResult.Failure -> state.update { it.copy(saving = false, formError = result.error.messageRes) }
            }
        }
    }

    /** D23 a: why these answers can't be listed (weight না, or age outside 18–60); null if they can. */
    private fun blockOf(d: SetupDraft): IneligibleReason? {
        val year = d.birthYear.trim().toIntOrNull() ?: return null
        return eligibility.listingBlock(year, d.weightOk ?: return null, clock.now())
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
