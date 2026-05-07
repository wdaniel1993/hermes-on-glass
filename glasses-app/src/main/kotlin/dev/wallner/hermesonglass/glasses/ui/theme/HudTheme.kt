package dev.wallner.hermesonglass.glasses.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wallner.hermesonglass.glasses.R

/**
 * Rokid panel runs best with high-contrast dark UI. Vendor lore says
 * monochrome green; the docs only assert a 480x640 single-color canvas, so
 * we hedge: green primary accent on a black background works on either
 * panel tuning. The bundled JetBrains Mono replaces the AOSP default for
 * predictable column widths.
 */
private val HudColors = darkColorScheme(
    primary = Color(0xFF7DFFB8),
    onPrimary = Color.Black,
    secondary = Color(0xFFB1CCC6),
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFD0D0D0),
    outline = Color(0xFF707070),
)

val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
)

@Composable
fun HudTheme(content: @Composable () -> Unit) {
    val typography = Typography(
        bodyLarge = TextStyle(fontFamily = JetBrainsMono, fontSize = 18.sp),
        bodyMedium = TextStyle(fontFamily = JetBrainsMono, fontSize = 16.sp),
        bodySmall = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp),
        titleSmall = TextStyle(
            fontFamily = JetBrainsMono,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
    MaterialTheme(
        colorScheme = HudColors,
        typography = typography,
        shapes = Shapes(small = RoundedCornerShape(2.dp)),
        content = content,
    )
}
