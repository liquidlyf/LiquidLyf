package io.github.liquidlyf.feature.safety

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.donor.DonorRepository
import io.github.liquidlyf.core.data.profile.ProfileRepository
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.EmptyState
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.components.LoadingState
import io.github.liquidlyf.core.designsystem.components.LocalSnackbarHostState
import io.github.liquidlyf.core.util.AppResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One blocked person. [name] comes from their public donor card, when they have one. */
data class BlockedPerson(val uid: String, val name: String?)

data class BlockedPeopleUiState(val people: List<BlockedPerson>? = null, val working: Boolean = false)

/** 7.2: Settings → Blocked people. Unblocking lets their requests, offers and asks reach me again. */
@HiltViewModel
class BlockedPeopleViewModel @Inject constructor(
    private val session: SessionManager,
    private val profiles: ProfileRepository,
    private val donors: DonorRepository,
) : ViewModel() {
    private val state = MutableStateFlow(BlockedPeopleUiState())
    val uiState: StateFlow<BlockedPeopleUiState> = state.asStateFlow()
    private val events = Channel<Int>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            session.state.mapNotNull { (it as? SessionState.Ready)?.profile?.blockedUids }
                .distinctUntilChanged()
                .collectLatest { uids ->
                    // Newest block first; names only for people with a donor card (1 read each).
                    val names = if (uids.isEmpty()) {
                        emptyMap()
                    } else {
                        (donors.cards(uids) as? AppResult.Success)?.data.orEmpty().associate { it.uid to it.displayName }
                    }
                    state.update { it.copy(people = uids.reversed().map { uid -> BlockedPerson(uid, names[uid]) }) }
                }
        }
    }

    fun unblock(uid: String) {
        val me = (session.state.value as? SessionState.Ready)?.profile ?: return
        if (state.value.working) return
        state.update { it.copy(working = true) }
        viewModelScope.launch {
            val result = profiles.unblock(me, uid)
            state.update { it.copy(working = false) }
            if (result is AppResult.Success) session.refresh()
            events.send(if (result is AppResult.Failure) result.error.messageRes else R.string.blocked_unblocked)
        }
    }
}

@Composable
fun BlockedPeopleRoute(onBack: () -> Unit, viewModel: BlockedPeopleViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { text -> launch { snackbar.showSnackbar(resources.getString(text)) } }
    }
    BlockedPeopleScreen(state, onUnblock = viewModel::unblock, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockedPeopleScreen(state: BlockedPeopleUiState, onUnblock: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { LLTopBar(title = stringResource(R.string.title_blocked_people), onBack = onBack) }) { padding ->
        val people = state.people
        when {
            people == null -> LoadingState(Modifier.padding(padding))
            people.isEmpty() -> EmptyState(
                icon = LLIcons.Block,
                title = stringResource(R.string.blocked_empty_title),
                body = stringResource(R.string.blocked_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth(), contentPadding = PaddingValues(vertical = Spacing.sm)) {
                    items(people, key = { it.uid }) { p ->
                        ListItem(
                            headlineContent = { Text(p.name ?: stringResource(R.string.blocked_someone)) },
                            leadingContent = { Icon(painterResource(LLIcons.Block), contentDescription = null) },
                            trailingContent = {
                                TextButton(onClick = { onUnblock(p.uid) }, enabled = !state.working, modifier = Modifier.heightIn(min = Spacing.minTouchTarget)) {
                                    Text(stringResource(R.string.action_unblock))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
