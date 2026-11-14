package io.github.liquidlyf.core.data.session

import io.github.liquidlyf.core.data.account.AccountDeleter
import io.github.liquidlyf.core.data.auth.AuthRepository
import io.github.liquidlyf.core.data.auth.AuthUser
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.push.AlertStore
import io.github.liquidlyf.core.data.push.PushTopicManager
import io.github.liquidlyf.core.data.push.Reminders
import io.github.liquidlyf.core.data.push.SystemNotifications
import io.github.liquidlyf.core.model.UserProfile
import io.github.liquidlyf.core.util.AppError
import io.github.liquidlyf.core.util.AppResult
import io.github.liquidlyf.core.util.ApplicationScope
import io.github.liquidlyf.core.util.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Where the user is on the way from install to "ready to donate" (doc 06 §4.1). */
sealed interface SessionState {
    /** Auth or the profile hasn't been read yet; the splash screen stays up. */
    data object Loading : SessionState

    data object SignedOut : SessionState

    data class Unverified(val user: AuthUser) : SessionState

    data class NeedsProfile(val user: AuthUser) : SessionState

    /** Signed in and verified, but the profile couldn't be read (offline on a new phone). */
    data class ProfileUnavailable(val user: AuthUser, val error: AppError) : SessionState

    data class Ready(val user: AuthUser, val profile: UserProfile) : SessionState

    /** An account deletion started on this phone and isn't finished: it resumes before anything else. */
    data class DeletingAccount(val user: AuthUser) : SessionState
}

/**
 * The single answer to "who is using the app, and how far did they get". Auth is the only source
 * of truth (BUG-016); the profile is read cache-first, so this costs no reads on most launches.
 */
interface SessionManager {
    val state: StateFlow<SessionState>

    /** Reads the user and profile again: after "I've verified", profile setup or an edit. */
    fun refresh()

    /** Logout (BUG-003): stop this device's alerts first, then sign out. */
    suspend fun signOut()
}

@Singleton
class AppSessionManager @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val push: PushTopicManager,
    private val deleter: AccountDeleter,
    private val alerts: AlertStore,
    private val notifications: SystemNotifications,
    private val reminders: Reminders,
    @param:ApplicationScope private val scope: CoroutineScope,
) : SessionManager {
    private val refreshes = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: StateFlow<SessionState> = combine(auth.authState, refreshes) { user, _ -> user }
        .mapLatest(::resolve)
        .stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    init {
        // Once per signed-in user and launch: re-sync every topic (FCM may have dropped them) and
        // mark the donor active (at most every 3 days; the repository skips it otherwise).
        scope.launch {
            var lastUid: String? = null
            var lastEligibleAgain: java.time.Instant? = null
            state.filterIsInstance<SessionState.Ready>().collect { ready ->
                val firstForUser = ready.user.uid != lastUid
                lastUid = ready.user.uid
                push.sync(ready.profile, force = firstForUser)
                // "You can donate again" at the end of the rest period (it also brings the alerts back).
                val eligibleAgain = push.nextChangeAt(ready.profile)
                if (firstForUser || eligibleAgain != lastEligibleAgain) reminders.scheduleEligibleAgain(eligibleAgain)
                lastEligibleAgain = eligibleAgain
                // The card must carry the profile's new lastActiveAt in later writes: re-read it.
                if (firstForUser && profiles.touchLastActive(ready.profile).getOrNull() == true) refresh()
            }
        }
        // Signed out (or the account is gone): forget this person's alerts and clear their
        // notifications, so the next person on the phone sees none of them.
        scope.launch {
            var signedIn = false
            state.collect { s ->
                if (s is SessionState.SignedOut && signedIn) {
                    alerts.clear()
                    notifications.cancelAll()
                    reminders.cancelAll()
                }
                if (s !is SessionState.Loading) signedIn = s !is SessionState.SignedOut
            }
        }
    }

    override fun refresh() {
        refreshes.update { it + 1 }
    }

    override suspend fun signOut() {
        push.sync(null)
        auth.signOut()
    }

    private suspend fun resolve(user: AuthUser?): SessionState = when {
        user == null -> SessionState.SignedOut
        deleter.isPending(user.uid) -> SessionState.DeletingAccount(user)
        !user.emailVerified -> SessionState.Unverified(user)
        else -> when (val result = profiles.profile(user.uid)) {
            is AppResult.Success -> result.data?.let { SessionState.Ready(user, it) } ?: SessionState.NeedsProfile(user)
            is AppResult.Failure -> SessionState.ProfileUnavailable(user, result.error)
        }
    }
}
