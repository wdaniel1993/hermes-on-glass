package dev.wallner.hermesonglass.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3C0),
    onPrimary = Color(0xFF003733),
    secondary = Color(0xFFB1CCC6),
    background = Color(0xFF0E1413),
    surface = Color(0xFF161D1C),
    onBackground = Color(0xFFE0E3E1),
    onSurface = Color(0xFFE0E3E1),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color.White,
    secondary = Color(0xFF4A6360),
    background = Color(0xFFF8FBF8),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
