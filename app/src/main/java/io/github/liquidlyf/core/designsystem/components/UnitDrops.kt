package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.liquidlyf.core.designsystem.LLIcons

/**
 * One drop per bag: red for each one received, grey for the rest (UI-03, N2/N6). Decorative: the
 * words next to it ("0 of 2 units received") say the same, so TalkBack skips it. A request for more
 * than [MAX_DROPS] bags shows no drops, only the words.
 */
@Composable
fun UnitDrops(received: Int, needed: Int, modifier: Modifier = Modifier) {
    if (needed !in 1..MAX_DROPS) return
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(needed) { i ->
            Icon(
                painterResource(LLIcons.BloodDrop),
                contentDescription = null,
                tint = if (i < received) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private const val MAX_DROPS = 6
