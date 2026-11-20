package io.github.liquidlyf.feature.profilesetup

import io.github.liquidlyf.core.data.ProcessDataStores
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The profile-setup answers so far, saved after every step, so killing the app resumes at the
 * right step with everything filled in (Phase 3 acceptance). Nothing here goes to Firestore until
 * that step saves; then the draft is deleted. D28: also the choice on setup's first page, and the
 * donor answers of Become a donor until it's finished (or after "Need blood now? Skip this"), on
 * this phone only, per account, never sent; cleared on sign-out.
 */
@Serializable
data class SetupDraft(
    val uid: String,
    val step: Int = 0,
    val bloodGroup: String? = null,
    /** "I don't know my blood group" → skipped (P1). Picking a group clears it. */
    val bloodGroupUnknown: Boolean = false,
    val fullName: String = "",
    val gender: String? = null,
    val birthYear: String = "",
    val weightOk: Boolean? = null,
    val division: String? = null,
    val district: String? = null,
    val upazila: String? = null,
    val phone: String = "",
    /** Null until answered: true = never donated, false = picked [lastDonationEpochDay]. */
    val neverDonated: Boolean? = null,
    val lastDonationEpochDay: Long? = null,
    /** The donor-listing consent: no pre-selected answer (PDPA 2026). */
    val listed: Boolean? = null,
    val available: Boolean = true,
    /** 18+ and the Terms, asked here only from accounts that skipped the sign-up form (Google). */
    val termsAccepted: Boolean = false,
    /** D28: setup's first page. Null until chosen: the donor option is chosen then. */
    val donor: Boolean? = null,
) {
    /** Some of Become a donor's answers (D28: kept on this phone after a skip or a stop). */
    val hasDonorAnswers: Boolean
        get() = bloodGroup != null || bloodGroupUnknown || gender != null || birthYear.isNotBlank() || weightOk != null ||
            neverDonated != null || listed != null || step > 0
}

interface SetupDraftStore {
    suspend fun load(uid: String): SetupDraft?

    suspend fun save(draft: SetupDraft)

    suspend fun clear()
}

@Singleton
class DataStoreSetupDraftStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SetupDraftStore {
    private val dataStore: DataStore<Preferences> by lazy {
        ProcessDataStores.get(context, "profile_setup")
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** Only the signed-in user's draft; another account's leftover is ignored. */
    override suspend fun load(uid: String): SetupDraft? {
        val text = dataStore.data.first()[DRAFT] ?: return null
        return runCatching { json.decodeFromString<SetupDraft>(text) }.getOrNull()?.takeIf { it.uid == uid }
    }

    override suspend fun save(draft: SetupDraft) {
        dataStore.edit { it[DRAFT] = json.encodeToString(SetupDraft.serializer(), draft) }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(DRAFT) }
    }

    private companion object {
        val DRAFT = stringPreferencesKey("draft")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileSetupModule {
    @Binds abstract fun draftStore(impl: DataStoreSetupDraftStore): SetupDraftStore
}
