package dev.alsatianconsulting.transportchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Orange500,
    onPrimary = White100,
    secondary = Orange400,
    tertiary = Orange300,
    background = White100,
    onBackground = Black900,
    surface = White100,
    onSurface = Black900,
    error = WarningRed
)

private val DarkColors = darkColorScheme(
    primary = Orange500,
    secondary = Orange400,
    tertiary = Orange300,
    background = Black900,
    onBackground = White100,
    surface = Black900,
    onSurface = White100,
    error = WarningRed
)

@Composable
fun TransportChatTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TransportTypography,
        content = content
    )
}
