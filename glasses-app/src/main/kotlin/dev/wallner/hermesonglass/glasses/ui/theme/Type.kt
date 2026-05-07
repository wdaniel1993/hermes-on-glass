package dev.wallner.hermesonglass.glasses.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * JetBrains Mono advance-width factor — width of one character at fontSize=N
 * is roughly N * MONO_ADVANCE_RATIO sp. Empirical for JetBrains Mono Regular
 * (per Compose preview at fontSize=16: char width ≈ 9.6sp). Used to back-solve
 * the font size that fits a target column count in a given pixel width.
 */
private const val MONO_ADVANCE_RATIO = 0.6f

/**
 * Compute the body font size that fits `targetColumns` JetBrains Mono
 * characters across `availableWidth` after subtracting `horizontalPadding`
 * on each side. Caps at [maxSp] so the HUD never goes giant on a smaller
 * column target.
 */
@Composable
fun rememberMonoBodySize(
    availableWidth: Dp,
    targetColumns: Int = 36,
    horizontalPadding: Dp = 8.dp,
    maxSp: Float = 22f,
    minSp: Float = 11f,
): TextUnit {
    val density = LocalDensity.current
    return remember(availableWidth, targetColumns, horizontalPadding) {
        with(density) {
            val usable = (availableWidth - horizontalPadding * 2).toPx()
            val perCharPx = usable / targetColumns
            val sizeSp = (perCharPx / density.density / MONO_ADVANCE_RATIO)
                .coerceIn(minSp, maxSp)
            sizeSp.sp
        }
    }
}

/**
 * Convenience: derive a body TextStyle for the current width.
 */
@Composable
fun monoBodyStyleForWidth(width: Dp, columns: Int = 36): TextStyle {
    val size = rememberMonoBodySize(width, columns)
    return TextStyle(fontFamily = JetBrainsMono, fontSize = size)
}
