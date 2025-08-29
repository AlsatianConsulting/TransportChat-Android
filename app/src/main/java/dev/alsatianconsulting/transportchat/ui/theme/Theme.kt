package dev.alsatianconsulting.transportchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TransportDarkScheme = darkColorScheme(
    // Brand orange for buttons/accents
    primary = Color(0xFFE87722),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7A3D12),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFE87722),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF7A3D12),
    onSecondaryContainer = Color.White,

    // Make all surfaces (incl. TopAppBar default) black
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEFEFEF),

    // Neutral variants (used by chips, list items, incoming bubbles in your UI)
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFE0E0E0),
)

@Composable
fun TransportChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TransportDarkScheme,
        content = content
    )
}
