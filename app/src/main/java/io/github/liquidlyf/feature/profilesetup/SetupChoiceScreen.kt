package io.github.liquidlyf.feature.profilesetup

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.liquidlyf.R
import io.github.liquidlyf.core.data.session.SessionManager
import io.github.liquidlyf.core.data.session.SessionState
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLBottomBar
import io.github.liquidlyf.core.designsystem.screenGutter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** D28: setup's first page. [donor]: the first option ("Donate blood, and ask for it if I need it"). */
data class SetupChoiceUiState(val donor: Boolean = true, val loaded: Boolean = false)

/**
 * D28, the maintainer's own idea, as D drew it (ui-audit/setup-choice.html): setup starts by asking
 * what the person wants to do. Donating is chosen already (people who only ask often never come back
 * to become donors); asking only is one short step, as before. The choice only decides which pages
 * follow: being listed is still its own real Yes / Not now, on the last donor step (PDPA 2026). It's
 * kept on this phone, so setup starts with it again if they stop before About you saves.
 */
@HiltViewModel
class SetupChoiceViewModel @Inject constructor(
    session: SessionManager,
    private val store: SetupDraftStore,
) : ViewModel() {

    private val uid: String? = (session.state.value as? SessionState.NeedsProfile)?.user?.uid

    private val state = MutableStateFlow(SetupChoiceUiState())
    val uiState: StateFlow<SetupChoiceUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val earlier = uid?.let { store.load(it) }?.donor
            state.update { it.copy(donor = earlier ?: true, loaded = true) }
        }
    }

    fun choose(donor: Boolean) = state.update { it.copy(donor = donor) }

    /** Continue: keeps the choice on this phone, and returns it for the route. */
    fun confirm(): Boolean {
        val donor = state.value.donor
        val id = uid ?: return donor
        viewModelScope.launch { store.save((store.load(id) ?: SetupDraft(uid = id)).copy(donor = donor)) }
        return donor
    }
}

@Composable
fun SetupChoiceRoute(onContinue: (donor: Boolean) -> Unit, viewModel: SetupChoiceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SetupChoiceScreen(state, onChoose = viewModel::choose, onContinue = { onContinue(viewModel.confirm()) })
}

@Composable
fun SetupChoiceScreen(state: SetupChoiceUiState, onChoose: (Boolean) -> Unit, onContinue: () -> Unit) {
    // D's finding: at 200% (Bangla) the second option fell below the screen. From 1.5, the pictures
    // and the small lines go, so both options and Continue are in view.
    val compact = LocalDensity.current.fontScale >= COMPACT_FONT_SCALE
    Scaffold(
        bottomBar = {
            LLBottomBar(primaryText = stringResource(R.string.action_continue), onPrimary = onContinue, primaryEnabled = state.loaded)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter())
                    .padding(top = Spacing.xl, bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.setup_choice_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.setup_choice_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ChoiceOption(
                        selected = state.donor,
                        title = stringResource(R.string.setup_choice_donate),
                        body = stringResource(R.string.setup_choice_donate_body),
                        icon = LLIcons.BloodDrop,
                        compact = compact,
                        onClick = { onChoose(true) },
                    )
                    ChoiceOption(
                        selected = !state.donor,
                        title = stringResource(R.string.setup_choice_need),
                        body = stringResource(R.string.setup_choice_need_body),
                        icon = LLIcons.Hospital,
                        compact = compact,
                        onClick = { onChoose(false) },
                    )
                }
            }
        }
    }
}

/** One option: a radio button, its words and (unless [compact]) a picture; the whole card is the target. */
@Composable
private fun ChoiceOption(selected: Boolean, title: String, body: String, @DrawableRes icon: Int, compact: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surface),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.md),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (!compact) Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (!compact) Icon(painterResource(icon), contentDescription = null, tint = colors.primary, modifier = Modifier.size(32.dp))
        }
    }
}

private const val COMPACT_FONT_SCALE = 1.5f
