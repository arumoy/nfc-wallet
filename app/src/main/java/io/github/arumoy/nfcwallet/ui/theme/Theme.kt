package io.github.arumoy.nfcwallet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Fixed neutral schemes. Device dynamic color (Material You) is intentionally
// not used, so the accent is always grey-on-black (dark) or black-on-white (light).

private val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    secondary = DarkAccent,
    onSecondary = DarkOnAccent,
    tertiary = DarkAccent,
    onTertiary = DarkOnAccent,
    background = Black,
    onBackground = White,
    surface = DarkSurface,
    onSurface = White,
    primaryContainer = DarkContainer,
    onPrimaryContainer = White,
    secondaryContainer = DarkContainer,
    onSecondaryContainer = White,
    surfaceVariant = DarkContainer,
    onSurfaceVariant = DarkAccent
)

private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    secondary = LightAccent,
    onSecondary = LightOnAccent,
    tertiary = LightAccent,
    onTertiary = LightOnAccent,
    background = White,
    onBackground = Black,
    surface = LightSurface,
    onSurface = Black,
    primaryContainer = LightContainer,
    onPrimaryContainer = Black,
    secondaryContainer = LightContainer,
    onSecondaryContainer = Black,
    surfaceVariant = LightContainer,
    onSurfaceVariant = LightAccent
)

@Composable
fun NFCWalletTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
