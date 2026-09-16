package com.ringautopilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF231F2A)
private val Cyan = Color(0xFF89DDFB)

@Composable
fun RingAutopilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme(
            primary = Cyan, onPrimary = Ink, primaryContainer = Color(0xFF123C58),
            onPrimaryContainer = Color(0xFFDEF5FF), secondary = Color(0xFFCCC2DC),
            background = Color(0xFF17151C), surface = Ink, surfaceVariant = Color(0xFF302B39),
            onSurface = Color(0xFFF7F0FA), onSurfaceVariant = Color(0xFFD2CCD9),
        ) else lightColorScheme(
            primary = Color(0xFF246DCE), onPrimary = Color.White,
            primaryContainer = Color(0xFFDCEBFF), onPrimaryContainer = Color(0xFF163957),
            secondary = Color(0xFF625B71), background = Color(0xFFF9F7FB),
            surface = Color.White, surfaceVariant = Color(0xFFF0ECF4),
            onSurface = Ink, onSurfaceVariant = Color(0xFF635D69),
        ),
        content = content,
    )
}
