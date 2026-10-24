package app.openread.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.openread.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D50), onPrimary = Color.White,
    primaryContainer = Color(0xFFB2F0DD), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A635B), tertiary = Color(0xFF8A5100),
    background = Color(0xFFF8FAF8), surface = Color(0xFFF8FAF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF96D4C1), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF14503F), onPrimaryContainer = Color(0xFFB2F0DD),
    secondary = Color(0xFFB1CCC2), tertiary = Color(0xFFFFB866),
    background = Color(0xFF101413), surface = Color(0xFF101413),
)

@Composable
fun OpenReadTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
