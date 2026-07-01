package com.example.antiwispr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Editorial Ink. No dynamic color — the brand IS the palette. */
@Composable
fun TacitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TacitDarkColors else TacitLightColors,
        typography = TacitTypography,
        shapes = TacitShapes,
        content = content,
    )
}
