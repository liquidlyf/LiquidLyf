package io.github.liquidlyf.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.liquidlyf.core.designsystem.Spacing

/**
 * A bottom sheet's drag handle with a 48 dp touch area (8.5). TalkBack can collapse or close the
 * sheet from it, and the standard handle is only as wide as its 32 dp bar (the Accessibility
 * Scanner flags it). The bar looks the same.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDragHandle() {
    Box(Modifier.widthIn(min = Spacing.minTouchTarget), contentAlignment = Alignment.Center) {
        BottomSheetDefaults.DragHandle()
    }
}
