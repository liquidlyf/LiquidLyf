package io.github.liquidlyf.feature.profile

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.Badge
import io.github.liquidlyf.core.domain.BadgeCalculator
import io.github.liquidlyf.core.domain.Eligibility
import io.github.liquidlyf.core.domain.EligibilityCalculator
import io.github.liquidlyf.core.domain.Impact
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject

/** What the "Add a past donation" sheet hands over. */
data class DonationDraft(val donatedAt: Instant, val component: BloodComponent, val place: String?)

data class ProfileUiState(
    val profile: UserProfile? = null,
    val eligibility: Eligibility? = null,
    /** Newest first; null while loading. */
    val donations: List<Donation>? = null,
    val donationsFailed: Boolean = false,
    val badges: List<Badge> = emptyList(),
    val impact: Impact = Impact(0, 0, 0, 0),
    val geo: BdGeo? = null,
    val saving: Boolean = false,
    /** A donation newer than the last one, waiting for "Update your eligibility?". */
    val pendingDonation: DonationDraft? = null,
)

sealed interface ProfileEvent {
    data class Message(@param:StringRes val text: Int) : ProfileEvent

    /** Closes the add sheet. */
    data object DonationSaved : ProfileEvent
}

/**
 * Phase 6.3/6.4: the donor's own profile. The profile comes from the session (it refreshes after
 * every write and after a verified donation arrives), the history is read again only when the
 * donation count or last donation changes, and badges and impact are computed here, for free.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val geoRepository: GeoRepository,
    private val eligibility: EligibilityCalculator,
    private val clock: Clock,
) : ViewModel() {
    private val badgeCalculator = BadgeCalculator()
    private val state = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = state.asStateFlow()
    private val events = Channel<ProfileEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val profileFlow = session.state.mapNotNull { (it as? SessionState.Ready)?.profile }

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo) }
        }
        // The session reads the profile cache-first, but some fields only the server writes (the
        // Worker's rapidResponder; A17 showed the badge late). One server read per open: if it
        // differs, refresh the session, whose cache-first read then sees the new copy.
        viewModelScope.launch {
            val cached = profileFlow.first()
            val fresh = profiles.profile(cached.uid, refresh = true)
            if (fresh is AppResult.Success && fresh.data != null && fresh.data != cached) session.refresh()
        }
        viewModelScope.launch {
            profileFlow.collect { p -> state.update { recompute(it.copy(profile = p)) } }
        }
        viewModelScope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            profileFlow.map { Triple(it.uid, it.donationCount, it.lastDonationId) }
                .distinctUntilChanged()
                .collectLatest { (uid) -> loadDonations(uid) }
        }
    }

    fun retry() {
        state.value.profile?.let { p -> viewModelScope.launch { loadDonations(p.uid) } }
    }

    /** From the sheet: asks first when this would be the latest donation (6.4). */
    fun addDonation(draft: DonationDraft) {
        val p = state.value.profile ?: return
        if (state.value.saving) return
        val latest = p.lastDonationAt == null || draft.donatedAt.isAfter(p.lastDonationAt)
        if (latest) state.update { it.copy(pendingDonation = draft) } else save(draft, updateEligibility = false)
    }

    fun answerEligibility(update: Boolean) {
        val draft = state.value.pendingDonation ?: return
        state.update { it.copy(pendingDonation = null) }
        save(draft, update)
    }

    fun cancelEligibilityQuestion() = state.update { it.copy(pendingDonation = null) }

    /** N1: the Donor card's switches, as in Settings. On: after the consent dialog. */
    fun setListed(listed: Boolean) = saveProfile(done = if (listed) R.string.success_listed else null) { p -> profiles.setListed(p, listed) }

    fun setAvailable(available: Boolean) = saveProfile { p -> profiles.setAvailable(p, available) }

    private fun saveProfile(@StringRes done: Int? = null, write: suspend (UserProfile) -> AppResult<Unit>) {
        val p = state.value.profile ?: return
        if (state.value.saving) return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = write(p)
            // The session re-reads the profile; the alert topics follow it (PushTopicManager).
            if (result is AppResult.Success) {
                session.refresh()
                done?.let { events.send(ProfileEvent.Message(it)) }
            } else {
                events.send(ProfileEvent.Message((result as AppResult.Failure).error.messageRes))
            }
            state.update { it.copy(saving = false) }
        }
    }

    fun removeDonation(donation: Donation) {
        val p = state.value.profile ?: return
        if (state.value.saving) return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = profiles.deleteSelfDonation(p, donation)
            state.update { it.copy(saving = false) }
            if (result is AppResult.Success) session.refresh()
            events.send(ProfileEvent.Message(if (result is AppResult.Failure) result.error.messageRes else R.string.profile_donation_removed))
        }
    }

    /** The earliest date a past donation can have: 1 January of the year the donor turned 18. */
    fun earliestDonationDate(): LocalDate? =
        state.value.profile?.birthYear?.let { LocalDate.of(it + MIN_AGE, 1, 1) }

    /**
     * The sheet's date as an instant: midday local time, but never later than a few minutes ago
     * (the rules reject a donation time after the server's clock).
     */
    fun donationInstant(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Instant =
        minOf(date.atTime(12, 0).atZone(zone).toInstant(), clock.now().minus(CLOCK_MARGIN))

    /** The date-picker's UTC midnight as a calendar date. */
    fun dateOf(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atOffset(ZoneOffset.UTC).toLocalDate()

    /** When the donor could donate again after [draft], for the eligibility question. */
    fun eligibleAfter(draft: DonationDraft): Instant? =
        state.value.profile?.let { eligibility.eligibleFrom(draft.donatedAt, it.gender) }

    private fun save(draft: DonationDraft, updateEligibility: Boolean) {
        val p = state.value.profile ?: return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = profiles.addSelfDonation(p, draft.donatedAt, draft.component, draft.place?.trim()?.ifEmpty { null }, updateEligibility)
            state.update { it.copy(saving = false) }
            when (result) {
                is AppResult.Success -> {
                    session.refresh()
                    events.send(ProfileEvent.DonationSaved)
                    events.send(ProfileEvent.Message(R.string.add_donation_added))
                }
                is AppResult.Failure -> events.send(ProfileEvent.Message(result.error.messageRes))
            }
        }
    }

    private suspend fun loadDonations(uid: String) {
        when (val result = profiles.donations(uid)) {
            is AppResult.Success -> state.update {
                recompute(it.copy(donations = result.data.sortedByDescending { d -> d.donatedAt }, donationsFailed = false))
            }
            is AppResult.Failure -> state.update { it.copy(donationsFailed = it.donations == null) }
        }
    }

    private fun recompute(s: ProfileUiState): ProfileUiState {
        val p = s.profile ?: return s
        val now = clock.now()
        val history = s.donations.orEmpty()
        return s.copy(
            eligibility = eligibility.evaluate(p, now),
            // Set by the Worker when one of my offers came within 15 minutes of its request.
            badges = badgeCalculator.badges(history, p.gender, p.rapidResponder, now),
            impact = badgeCalculator.impact(history),
        )
    }

    private companion object {
        const val MIN_AGE = 18
        val CLOCK_MARGIN: Duration = Duration.ofMinutes(5)
    }
}
