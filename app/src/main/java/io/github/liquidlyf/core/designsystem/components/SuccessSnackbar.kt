package io.github.liquidlyf.core.designsystem.components

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.R
import io.github.liquidlyf.core.designsystem.LLIcons
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * The "it worked" moments (MOTION-01): a check that springs in (about 400 ms) and one line, on the
 * app's snackbar, for the four things that matter most. Every other message stays a plain snackbar.
 * With "Remove animations" on, Compose skips the spring and the check is simply there.
 */
object SuccessMessages {
    val ids: Set<Int> = setOf(
        R.string.success_request_posted,
        R.string.detail_offer_sent,
        R.string.success_listed,
        R.string.become_donor_done,
        R.string.detail_marked_donated,
        R.string.add_donation_added,
    )
}

/** A snackbar that shows the success check. [duration]: Short; the screen gallery holds it. */
class SuccessVisuals(override val message: String, override val duration: SnackbarDuration = SnackbarDuration.Short) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
}

/** Shows [text]: with the success check when it's one of [SuccessMessages], plain otherwise. */
suspend fun SnackbarHostState.showAppMessage(resources: Resources, @StringRes text: Int) {
    val message = resources.getString(text)
    if (text in SuccessMessages.ids) showSnackbar(SuccessVisuals(message)) else showSnackbar(message)
}

/** The app's snackbar host content: a success check for [SuccessVisuals], Material's snackbar otherwise. */
@Composable
fun LLSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    if (visuals !is SuccessVisuals) {
        Snackbar(data)
        return
    }
    val pop = remember { Animatable(0.4f) }
    LaunchedEffect(data) { pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) }
    Snackbar {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(
                painterResource(LLIcons.CheckCircle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inversePrimary,
                modifier = Modifier.size(24.dp).scale(pop.value).alpha(((pop.value - 0.4f) / 0.6f).coerceIn(0f, 1f)),
            )
            Text(visuals.message)
        }
    }
}
