package io.github.liquidlyf.core.designsystem.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.liquidlyf.R

/** Under a choice whose confirm is greyed out until something is picked (D's part 2, Low 8). */
@Composable
fun ChooseHint() {
    Text(stringResource(R.string.hint_choose_one), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
