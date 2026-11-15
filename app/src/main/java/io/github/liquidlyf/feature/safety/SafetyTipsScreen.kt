package io.github.liquidlyf.feature.safety

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing
import io.github.liquidlyf.core.designsystem.components.LLPrimaryButton
import io.github.liquidlyf.core.designsystem.components.LLTopBar
import io.github.liquidlyf.core.designsystem.screenGutter
import io.github.liquidlyf.core.util.rememberSafeIntentLauncher

/** Phase 7.6: linked from the request screen's safety line and from Settings. English for now; Bangla in Phase 8. */
@Composable
fun SafetyTipsRoute(onBack: () -> Unit) {
    val launcher = rememberSafeIntentLauncher()
    SafetyTipsScreen(onBack = onBack, onCallEmergency = { launcher.dial(EMERGENCY_NUMBER) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SafetyTipsScreen(onBack: () -> Unit, onCallEmergency: () -> Unit) {
    Scaffold(topBar = { LLTopBar(title = stringResource(R.string.title_safety_tips), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = screenGutter(), vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.safety_intro), style = MaterialTheme.typography.bodyLarge)
                TIPS.forEach { (icon, title, body) -> Tip(icon, title, body) }
                LLPrimaryButton(
                    text = stringResource(R.string.action_call_999),
                    onClick = onCallEmergency,
                    icon = LLIcons.Call,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Tip(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Bangladesh's national emergency number (police, fire, ambulance). */
internal const val EMERGENCY_NUMBER = "999"

private val TIPS = listOf(
    Triple(LLIcons.Shield, R.string.safety_tip_money_title, R.string.safety_tip_money_body),
    Triple(LLIcons.Hospital, R.string.safety_tip_place_title, R.string.safety_tip_place_body),
    Triple(LLIcons.Group, R.string.safety_tip_company_title, R.string.safety_tip_company_body),
    Triple(LLIcons.Verified, R.string.safety_tip_screening_title, R.string.safety_tip_screening_body),
    Triple(LLIcons.Flag, R.string.safety_tip_report_title, R.string.safety_tip_report_body),
    Triple(LLIcons.Call, R.string.safety_tip_emergency_title, R.string.safety_tip_emergency_body),
)
