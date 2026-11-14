package io.github.liquidlyf.feature.request.create

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.core.data.backend.Dispatch
import io.github.liquidlyf.core.data.backend.WorkerClient
import io.github.liquidlyf.core.data.geo.BdGeo
import io.github.liquidlyf.core.data.geo.GeoRepository
import io.github.liquidlyf.core.data.hospital.Hospital
import io.github.liquidlyf.core.data.hospital.HospitalRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.push.AlertStore
import io.github.liquidlyf.core.data.push.Reminders
import io.github.liquidlyf.core.data.request.RequestRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.domain.Validators
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.PatientCondition
import io.github.liquidlyf.core.model.RequestDraft
import io.github.liquidlyf.core.model.RequestLocation
import io.github.liquidlyf.core.model.Urgency
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.Clock
import io.github.liquidlyf.core.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Everything the 3-step form holds. Plain values only, so it survives rotation and process death
 * in [SavedStateHandle] as JSON. Codes are the stored enum codes.
 */
@Serializable
data class RequestForm(
    val step: Int = 0,
    // Step 1: patient
    val bloodGroup: String? = null,
    val component: String = BloodComponent.WHOLE_BLOOD.code,
    val units: Int = 1,
    val condition: String? = null,
    val acceptCompatible: Boolean = false,
    // Step 2: where and when
    val division: String? = null,
    val district: String? = null,
    val upazila: String? = null,
    val hospitalName: String = "",
    /** Set only when a listed hospital was picked; typing clears it ("Other"). */
    val hospitalId: String? = null,
    val hospitalAddress: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val geohash: String? = null,
    val wardOrBed: String = "",
    val urgency: String? = null,
    /** Planned requests only: the chosen date and time, epoch milliseconds. */
    val plannedAtMillis: Long? = null,
    // Step 3: contact
    val contactName: String = "",
    val contactPhone: String = "",
    val patientName: String = "",
    val notes: String = "",
    val genuine: Boolean = false,
)

enum class RequestField {
    BLOOD_GROUP, UNITS, LOCATION, HOSPITAL, WARD, URGENCY, PLANNED_AT,
    CONTACT_NAME, CONTACT_PHONE, PATIENT_NAME, NOTES, GENUINE,
}

/** The open-request limit is reached: 1 for accounts under 7 days old, 3 after that (rules: userStats). */
data class OpenRequestLimit(val max: Int, val newAccount: Boolean)

data class CreateRequestUiState(
    val form: RequestForm,
    val loaded: Boolean = false,
    val geo: BdGeo? = null,
    val limit: OpenRequestLimit? = null,
    val errors: Set<RequestField> = emptySet(),
    val hospitals: List<Hospital> = emptyList(),
    val posting: Boolean = false,
    @param:StringRes val formError: Int? = null,
) {
    val step: Int get() = form.step
    val isLastStep: Boolean get() = form.step == CreateRequestViewModel.STEPS - 1
}

/** Shown on the request's page right after posting. */
enum class PostNotice {
    /** Offline: the request is saved on the phone and goes out when the network is back. */
    WAITING_FOR_NETWORK,

    /** Posted, but over the daily alert cap: donors weren't notified (Worker 429 dailyCap). */
    DAILY_ALERT_LIMIT,
}

sealed interface CreateRequestEvent {
    data class Posted(val requestId: String, val notice: PostNotice?) : CreateRequestEvent
}

/**
 * Create a blood request (Phase 4.2): patient → where and when → contact. One rules-checked batch
 * (contract 6), then the Worker's dispatch alerts tier 1; if that call fails, the Worker's sweep
 * sends it. The write runs in the app scope: leaving the screen, or no network, never loses it.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreateRequestViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    session: SessionManager,
    private val profiles: ProfileRepository,
    private val requests: RequestRepository,
    private val hospitals: HospitalRepository,
    private val geoRepository: GeoRepository,
    private val worker: WorkerClient,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
    network: NetworkMonitor,
    private val alerts: AlertStore,
    private val reminders: Reminders,
) : ViewModel() {

    val online: StateFlow<Boolean> = network.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val me: UserProfile? = (session.state.value as? SessionState.Ready)?.profile

    private val state = MutableStateFlow(CreateRequestUiState(form = restoreForm()))
    val uiState: StateFlow<CreateRequestUiState> = state.asStateFlow()

    private val events = Channel<CreateRequestEvent>(Channel.BUFFERED)
    val eventFlow: Flow<CreateRequestEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val geo = geoRepository.geo()
            state.update { it.copy(geo = geo, loaded = true) }
        }
        // In parallel: a slow network mustn't hold the form; at the limit it's swapped for the reason.
        viewModelScope.launch {
            val limit = me?.let { openRequestLimitReached(it) }
            if (limit != null) state.update { it.copy(limit = limit) }
        }
        // Hospital suggestions 300 ms after typing stops; a newer query cancels the older search.
        viewModelScope.launch {
            state.map { Triple(it.form.district, it.form.hospitalName, it.form.hospitalId) }
                .distinctUntilChanged()
                .debounce(SEARCH_DEBOUNCE_MS)
                .flatMapLatest { (district, query, pickedId) ->
                    flow { emit(if (district == null || pickedId != null || query.isBlank()) emptyList() else hospitals.search(district, query)) }
                }
                .collect { found -> state.update { it.copy(hospitals = found) } }
        }
    }

    /** Any answer: clears the errors, and is kept for rotation and process death. */
    fun edit(change: RequestForm.() -> RequestForm) {
        state.update { it.copy(form = it.form.change(), errors = emptySet(), formError = null) }
        savedState[FORM_KEY] = JSON.encodeToString(RequestForm.serializer(), state.value.form)
    }

    fun onDivision(code: String) = edit { copy(division = code, district = null, upazila = null).withoutHospital() }

    fun onDistrict(code: String) = edit { copy(district = code, upazila = null).withoutHospital() }

    /** Typing a name: it's a hospital that isn't in the list, until one is picked. */
    fun onHospitalTyped(name: String) = edit { withoutHospital().copy(hospitalName = name.take(Validators.HOSPITAL_NAME_MAX)) }

    fun onHospitalPicked(h: Hospital, name: String) = edit {
        copy(
            hospitalId = h.id, hospitalName = name, hospitalAddress = h.address,
            upazila = h.upazila ?: upazila,
            lat = h.location?.lat, lng = h.location?.lng, geohash = h.location?.geohash,
        )
    }

    fun next() {
        val s = state.value
        if (!s.loaded || s.posting) return
        val errors = validate(s.form, s.form.step)
        if (errors.isNotEmpty()) {
            state.update { it.copy(errors = errors) }
            return
        }
        if (s.isLastStep) post() else edit { copy(step = step + 1) }
    }

    /** Back inside the steps; false on the first step (the screen then closes). */
    fun back(): Boolean {
        val s = state.value
        if (s.form.step == 0 || s.posting) return false
        edit { copy(step = step - 1) }
        return true
    }

    private fun post() {
        val requester = me ?: return
        val draft = toDraft(state.value.form) ?: return
        state.update { it.copy(posting = true, formError = null) }
        val requestId = requests.newRequestId()
        val work = appScope.async {
            alerts.rememberMyRequest(requestId) // its alert reaches my phone too: don't show it to me
            when (val created = requests.create(requester, draft, requestId)) {
                is AppResult.Failure -> PostResult.Failed(created.error)
                is AppResult.Success -> {
                    reminders.scheduleRequestEnding(requestId, draft.neededBy) // "ends in 2 hours"
                    PostResult.Created(worker.dispatch(requestId))
                }
            }
        }
        viewModelScope.launch {
            when (val result = withTimeoutOrNull(POST_WAIT_MS) { work.await() }) {
                null -> posted(requestId, PostNotice.WAITING_FOR_NETWORK)
                is PostResult.Failed -> state.update { it.copy(posting = false, formError = result.error.messageRes) }
                is PostResult.Created -> {
                    val capped = (result.dispatch as? AppResult.Failure)?.error == AppError.DailyAlertLimit
                    posted(requestId, if (capped) PostNotice.DAILY_ALERT_LIMIT else null)
                }
            }
        }
    }

    private suspend fun posted(requestId: String, notice: PostNotice?) {
        savedState.remove<String>(FORM_KEY)
        events.send(CreateRequestEvent.Posted(requestId, notice))
    }

    private sealed interface PostResult {
        data class Created(val dispatch: AppResult<Dispatch>) : PostResult
        data class Failed(val error: AppError) : PostResult
    }

    private suspend fun openRequestLimitReached(p: UserProfile): OpenRequestLimit? {
        // Offline or unreadable: let the form open; the rules still enforce the limit.
        val stats = (profiles.stats(p.uid, refresh = true) as? AppResult.Success)?.data ?: return null
        val newAccount = p.createdAt?.let { Duration.between(it, clock.now()) < NEW_ACCOUNT_AGE } ?: true
        val max = if (newAccount) MAX_OPEN_NEW_ACCOUNT else MAX_OPEN
        return if (stats.openRequests >= max) OpenRequestLimit(max, newAccount) else null
    }

    private fun restoreForm(): RequestForm =
        savedState.get<String>(FORM_KEY)?.let { runCatching { JSON.decodeFromString(RequestForm.serializer(), it) }.getOrNull() }
            ?: me.defaultForm()

    /** The requester's own area and contact details, since most requests are local. */
    private fun UserProfile?.defaultForm(): RequestForm = if (this == null) {
        RequestForm()
    } else {
        RequestForm(
            division = area.division, district = area.district, upazila = area.upazila,
            contactName = fullName, contactPhone = "0" + phone.removePrefix("+880"),
        )
    }

    internal fun validate(f: RequestForm, step: Int): Set<RequestField> = buildSet {
        val now = clock.now()
        when (step) {
            0 -> {
                if (BloodGroup.fromCode(f.bloodGroup) == null) add(RequestField.BLOOD_GROUP)
                if (!Validators.isValidUnits(f.units)) add(RequestField.UNITS)
            }
            1 -> {
                val geo = state.value.geo
                val area = areaOf(f)
                if (area == null || (geo != null && !geo.isValid(area))) add(RequestField.LOCATION)
                if (!Validators.isValidHospitalName(f.hospitalName)) add(RequestField.HOSPITAL)
                if (f.wardOrBed.trim().length > Validators.WARD_OR_BED_MAX) add(RequestField.WARD)
                when (Urgency.fromCode(f.urgency)) {
                    null -> add(RequestField.URGENCY)
                    Urgency.PLANNED -> if (neededBy(f, now)?.let { Validators.isValidNeededBy(it, now) } != true) add(RequestField.PLANNED_AT)
                    else -> Unit
                }
            }
            else -> {
                if (!Validators.isValidName(f.contactName)) add(RequestField.CONTACT_NAME)
                if (Validators.normalizePhone(f.contactPhone) == null) add(RequestField.CONTACT_PHONE)
                if (f.patientName.trim().length > Validators.PATIENT_NAME_MAX) add(RequestField.PATIENT_NAME)
                if (f.notes.trim().length > Validators.NOTES_MAX) add(RequestField.NOTES)
                if (!f.genuine) add(RequestField.GENUINE)
            }
        }
    }

    private fun toDraft(f: RequestForm): RequestDraft? {
        val now = clock.now()
        val area = areaOf(f) ?: return null
        return RequestDraft(
            bloodGroup = BloodGroup.fromCode(f.bloodGroup) ?: return null,
            component = BloodComponent.fromCode(f.component) ?: return null,
            acceptCompatible = f.acceptCompatible,
            unitsNeeded = f.units,
            condition = PatientCondition.fromCode(f.condition),
            hospitalId = f.hospitalId,
            hospitalName = f.hospitalName.trim(),
            hospitalAddress = f.hospitalAddress,
            division = area.division,
            district = area.district,
            upazila = area.upazila,
            location = if (f.lat != null && f.lng != null && f.geohash != null) RequestLocation(f.lat, f.lng, f.geohash) else null,
            urgency = Urgency.fromCode(f.urgency) ?: return null,
            neededBy = neededBy(f, now) ?: return null,
            notes = f.notes.trim().ifEmpty { null },
            contactName = Validators.normalizeName(f.contactName),
            contactPhone = Validators.normalizePhone(f.contactPhone) ?: return null,
            patientName = f.patientName.trim().ifEmpty { null },
            wardOrBed = f.wardOrBed.trim().ifEmpty { null },
        )
    }

    companion object {
        const val STEPS = 3
        const val FORM_KEY = "request_form"
        const val SEARCH_DEBOUNCE_MS = 300L

        /** How long "Posting…" waits for the server before showing the request as waiting for network. */
        const val POST_WAIT_MS = 10_000L
        const val MAX_OPEN = 3
        const val MAX_OPEN_NEW_ACCOUNT = 1
        val NEW_ACCOUNT_AGE: Duration = Duration.ofDays(7)
        val CRITICAL_WITHIN: Duration = Duration.ofHours(6)
        val URGENT_WITHIN: Duration = Duration.ofHours(24)
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Critical = within 6 h and urgent = within 24 h of posting; planned = the chosen time. */
        fun neededBy(f: RequestForm, now: Instant): Instant? = when (Urgency.fromCode(f.urgency)) {
            Urgency.CRITICAL -> now.plus(CRITICAL_WITHIN)
            Urgency.URGENT -> now.plus(URGENT_WITHIN)
            Urgency.PLANNED -> f.plannedAtMillis?.let(Instant::ofEpochMilli)
            null -> null
        }

        private fun areaOf(f: RequestForm): Area? {
            val division = f.division ?: return null
            val district = f.district ?: return null
            val upazila = f.upazila ?: return null
            return Area(division, district, upazila)
        }

        private fun RequestForm.withoutHospital(): RequestForm =
            copy(hospitalId = null, hospitalAddress = null, lat = null, lng = null, geohash = null)
    }
}
