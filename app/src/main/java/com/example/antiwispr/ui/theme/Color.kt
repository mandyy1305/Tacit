package com.example.antiwispr.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * "Editorial Ink" — warm paper & ink with a burnt-amber accent.
 * Light = paper #FAF7F2 / ink #1A1714; dark = espresso #141210 / cream #ECE6DC.
 * tertiary is a muted sage reserved for "ready/ok" status.
 */

val Paper = Color(0xFFFAF7F2)
val Ink = Color(0xFF1A1714)
val Espresso = Color(0xFF141210)
val Cream = Color(0xFFECE6DC)
val Amber = Color(0xFFC4622D)

val TacitLightColors = lightColorScheme(
    primary = Amber,                            onPrimary = Color(0xFFFFF8F2),
    primaryContainer = Color(0xFFF4DAC7),       onPrimaryContainer = Color(0xFF4A2008),
    secondary = Color(0xFF6E5F4B),              onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAE0CF),     onSecondaryContainer = Color(0xFF2A2318),
    tertiary = Color(0xFF5A6B50),               onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE5D2),      onTertiaryContainer = Color(0xFF1C2417),
    error = Color(0xFF9C3A2E),                  onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DAD3),         onErrorContainer = Color(0xFF3F130D),
    background = Paper,                         onBackground = Ink,
    surface = Paper,                            onSurface = Ink,
    surfaceVariant = Color(0xFFF0EAE0),         onSurfaceVariant = Color(0xFF5C5348),
    surfaceContainerLowest = Color(0xFFFFFEFB),
    surfaceContainerLow = Color(0xFFF6F1E8),
    surfaceContainer = Color(0xFFF3EDE3),
    surfaceContainerHigh = Color(0xFFEFE7DA),
    surfaceContainerHighest = Color(0xFFEAE1D2),
    outline = Color(0xFFCFC5B4),                outlineVariant = Color(0xFFE5DDCF),
    inverseSurface = Color(0xFF2A2520),         inverseOnSurface = Color(0xFFF5EFE6),
    inversePrimary = Color(0xFFE8A87C),         scrim = Color(0xFF000000),
)

val TacitDarkColors = darkColorScheme(
    primary = Color(0xFFD97B45),                onPrimary = Color(0xFF2A1204),
    primaryContainer = Color(0xFF6B3316),       onPrimaryContainer = Color(0xFFF6D9C4),
    secondary = Color(0xFFB3A48D),              onSecondary = Color(0xFF262015),
    secondaryContainer = Color(0xFF3A3227),     onSecondaryContainer = Color(0xFFE6DBC6),
    tertiary = Color(0xFFA6B899),               onTertiary = Color(0xFF1C2417),
    tertiaryContainer = Color(0xFF3B4633),      onTertiaryContainer = Color(0xFFDCE5D2),
    error = Color(0xFFE38B7B),                  onError = Color(0xFF33110B),
    errorContainer = Color(0xFF6E2A20),         onErrorContainer = Color(0xFFF6DAD3),
    background = Espresso,                      onBackground = Cream,
    surface = Espresso,                         onSurface = Cream,
    surfaceVariant = Color(0xFF241F1A),         onSurfaceVariant = Color(0xFFA89C8C),
    surfaceContainerLowest = Color(0xFF0F0D0B),
    surfaceContainerLow = Color(0xFF181511),
    surfaceContainer = Color(0xFF1D1915),
    surfaceContainerHigh = Color(0xFF262019),
    surfaceContainerHighest = Color(0xFF2E2720),
    outline = Color(0xFF4A4237),                outlineVariant = Color(0xFF2E2822),
    inverseSurface = Cream,                     inverseOnSurface = Color(0xFF2A2520),
    inversePrimary = Amber,                     scrim = Color(0xFF000000),
)
