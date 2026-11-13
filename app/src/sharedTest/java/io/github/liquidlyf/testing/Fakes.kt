package io.github.liquidlyf.testing

import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.auth.AuthUser
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.data.settings.SettingsRepository
import io.github.liquidlyf.core.data.settings.ThemeMode
import io.github.liquidlyf.core.data.settings.UserSettings
import io.github.liquidlyf.core.firebase.dto.UserDoc
import io.github.liquidlyf.core.model.AppLocale
import io.github.liquidlyf.core.model.Area
import io.github.liquidlyf.core.model.BloodComponent
import io.github.liquidlyf.core.model.BloodGroup
import io.github.liquidlyf.core.model.BloodRequest
import io.github.liquidlyf.core.model.Donation
import io.github.liquidlyf.core.model.Gender
import io.github.liquidlyf.core.model.ProfileDraft
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.model.UserStats
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/*
 * In-memory fakes for ViewModel tests (src/test) and Compose UI tests (src/androidTest).
 * They follow the same rules as the real repositories where a test could notice.
 */

class FakeSettingsRepository(initial: UserSettings? = null) : SettingsRepository {
    private val flow = MutableSharedFlow<UserSettings>(replay = 1).apply { initial?.let(::tryEmit) }
    override val settings: SharedFlow<UserSettings> = flow

    var onboardingSeen: Boolean? = null
    var lastSelectedTab: String? = null

    suspend fun emit(value: UserSettings) = flow.emit(value)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit

    override suspend fun setOnboardingSeen(seen: Boolean) {
        onboardingSeen = seen
    }

    override suspend fun setLastSelectedTab(tab: String) {
        lastSelectedTab = tab
    }
}

class FakeSessionManager(initial: SessionState = SessionState.SignedOut) : SessionManager {
    override val state = MutableStateFlow(initial)
    var refreshes = 0
    var signOuts = 0

    override fun refresh() {
        refreshes++
    }

    override suspend fun signOut() {
        signOuts++
        state.value = SessionState.SignedOut
    }
}

/** Accounts live in a map; [failNext] makes the next call fail with that error. */
class FakeAuthRepository : AuthRepository {
    data class Account(val uid: String, val password: String, val name: String?, var verified: Boolean)

    val accounts = mutableMapOf<String, Account>()
    val verificationEmails = mutableListOf<String>()
    val resetEmails = mutableListOf<String>()
    var failNext: AppError? = null
    private val state = MutableStateFlow<AuthUser?>(null)
    override val authState: StateFlow<AuthUser?> = state
    private var current: String? = null

    private fun <T> result(block: () -> AppResult<T>): AppResult<T> =
        failNext?.let { failNext = null; AppResult.Failure(it) } ?: block()

    private fun publish(): AuthUser? = current?.let { email ->
        val a = accounts.getValue(email)
        AuthUser(a.uid, email, a.verified, a.name, setOf("password"))
    }.also { state.value = it }

    override suspend fun signInEmail(email: String, password: String) = result {
        val a = accounts[email.trim()]
        if (a == null || a.password != password) {
            AppResult.Failure(AppError.InvalidCredentials)
        } else {
            current = email.trim()
            AppResult.Success(publish()!!)
        }
    }

    override suspend fun signUpEmail(name: String, email: String, password: String) = result {
        when {
            email.trim() in accounts -> AppResult.Failure(AppError.EmailInUse)
            password.length < 6 -> AppResult.Failure(AppError.WeakPassword)
            else -> {
                accounts[email.trim()] = Account("uid-${accounts.size + 1}", password, name.trim(), verified = false)
                current = email.trim()
                verificationEmails += email.trim()
                AppResult.Success(publish()!!)
            }
        }
    }

    override suspend fun signInWithGoogle(idToken: String) = result<AuthUser> { AppResult.Failure(AppError.Unknown) }

    override suspend fun sendVerification() = result {
        current?.let { verificationEmails += it }
        AppResult.Success(Unit)
    }

    override suspend fun reloadUser() = result { AppResult.Success(publish()) }

    override suspend fun sendPasswordReset(email: String) = result {
        resetEmails += email.trim()
        AppResult.Success(Unit)
    }

    override suspend fun reauthenticate(password: String) = result {
        val a = current?.let(accounts::get)
        if (a?.password == password) AppResult.Success(Unit) else AppResult.Failure(AppError.InvalidCredentials)
    }

    override suspend fun changePassword(newPassword: String) = result {
        current?.let { accounts[it] = accounts.getValue(it).copy(password = newPassword) }
        AppResult.Success(Unit)
    }

    override fun signOut() {
        current = null
        state.value = null
    }

    override suspend fun deleteUser() = result {
        current?.let(accounts::remove)
        signOut()
        AppResult.Success(Unit)
    }

    /** What the user does in their email app. */
    fun verify(email: String) {
        accounts.getValue(email).verified = true
    }
}

class FakeProfileRepository(private val now: Instant = TestData.NOW) : ProfileRepository {
    val profiles = mutableMapOf<String, UserProfile>()
    val created = mutableListOf<Pair<String, ProfileDraft>>()
    var failNext: AppError? = null

    private fun <T> result(block: () -> AppResult<T>): AppResult<T> =
        failNext?.let { failNext = null; AppResult.Failure(it) } ?: block()

    override suspend fun profile(uid: String, refresh: Boolean) = result { AppResult.Success(profiles[uid]) }

    override suspend fun stats(uid: String, refresh: Boolean) =
        result { AppResult.Success(profiles[uid]?.let { UserStats(0, 0, null) }) }

    override suspend fun create(uid: String, email: String, draft: ProfileDraft) = result {
        created += uid to draft
        profiles[uid] = TestData.profile(uid).let { UserDoc.applyDraft(it, draft, now) }.copy(email = email)
        AppResult.Success(Unit)
    }

    override suspend fun update(before: UserProfile, draft: ProfileDraft) = result {
        profiles[before.uid] = UserDoc.applyDraft(before, draft, now)
        AppResult.Success(Unit)
    }

    override suspend fun touchLastActive(profile: UserProfile) = result { AppResult.Success(false) }

    override suspend fun markInboxSeen(profile: UserProfile) = result { AppResult.Success(Unit) }

    override suspend fun donations(uid: String) = result { AppResult.Success(emptyList<Donation>()) }

    override suspend fun addSelfDonation(profile: UserProfile, donatedAt: Instant, component: BloodComponent, hospitalName: String?) =
        result { AppResult.Success(Unit) }

    override suspend fun deleteSelfDonation(profile: UserProfile, donation: Donation) = result { AppResult.Success(Unit) }

    override suspend fun recordVerifiedDonation(profile: UserProfile, request: BloodRequest, donatedAt: Instant) =
        result { AppResult.Success(Unit) }
}

object TestData {
    val NOW: Instant = Instant.parse("2026-11-13T06:00:00Z")
    val DHAKA_SOUTH = Area("BD30", "BD3026", "BD30262000")

    fun user(uid: String = "uid-1", verified: Boolean = true) =
        AuthUser(uid, "rahim@example.test", verified, "Rahim Uddin", setOf("password"))

    fun draft() = ProfileDraft(
        fullName = "Rahim Uddin", phone = "+8801712345678", bloodGroup = BloodGroup.B_POS, gender = Gender.MALE,
        birthYear = 1995, weightOk = true, area = DHAKA_SOUTH, available = true, lastDonationAt = null,
        showInDonorSearch = true, locale = AppLocale.EN, termsVersion = "2026-11",
    )

    fun profile(uid: String = "uid-1") = UserProfile(
        uid = uid, fullName = "Rahim Uddin", email = "rahim@example.test", phone = "+8801712345678", phoneVerified = false,
        bloodGroup = BloodGroup.B_POS, gender = Gender.MALE, birthYear = 1995, weightOk = true, area = DHAKA_SOUTH,
        available = true, lastDonationAt = null, showInDonorSearch = true, consentAt = NOW, locale = AppLocale.EN,
        termsVersion = "2026-11", termsAcceptedAt = NOW, createdAt = NOW, updatedAt = NOW, role = null,
        hiddenRequestIds = emptyList(), blockedUids = emptyList(), inboxSeenAt = null, lastRequestAt = null,
        lastActiveAt = NOW, donationCount = 0, verifiedDonations = 0, lastDonationId = null,
    )
}
