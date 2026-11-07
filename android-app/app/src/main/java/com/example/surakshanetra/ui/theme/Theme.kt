package com.example.surakshanetra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = GreenLight,
    onPrimary = DeepGreen,
    secondary = MintDark,
    onSecondary = DeepGreen,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightMuted,
    secondaryContainer = NightSurfaceVariant,
    onSecondaryContainer = NightText
)

private val LightColorScheme = lightColorScheme(
    primary = Green,
    onPrimary = White,
    secondary = DeepGreen,
    onSecondary = White,
    background = Pale,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = PaleVariant,
    onSurfaceVariant = Muted,
    secondaryContainer = PaleVariant,
    onSecondaryContainer = Ink
)

@Composable
fun SurakshanetraTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    // Surakshanetra uses a stable green palette so both app themes remain consistent
    // across Android versions and devices.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
