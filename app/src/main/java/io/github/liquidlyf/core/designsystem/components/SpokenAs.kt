package io.github.liquidlyf.core.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

/**
 * TalkBack reads [spoken] in place of the text on screen, as text (not a content description), so a
 * row that reads its lines together still reads this one in its place. For a phone number,
 * `PhoneDisplay.spoken` (Low 2: digit by digit).
 */
fun Modifier.spokenAs(spoken: String): Modifier = clearAndSetSemantics { text = AnnotatedString(spoken) }
