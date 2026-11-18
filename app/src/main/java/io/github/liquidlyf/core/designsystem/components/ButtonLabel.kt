package io.github.liquidlyf.core.designsystem.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

/**
 * A button's words, centred when they wrap to a second line (D's part 2, Low 1: "Become a / donor"
 * and "Call Karim H. / করুন" sat left in the pill at 200%).
 */
@Composable
fun ButtonLabel(text: String) {
    Text(text, textAlign = TextAlign.Center)
}
