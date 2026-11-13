package io.github.liquidlyf.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * Shape (doc 04 §3): chips and fields 8dp · cards 12dp · FAB and sheets 16dp ·
 * dialogs 28dp · buttons fully rounded (the Material 3 default).
 */
internal val LiquidLyfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
