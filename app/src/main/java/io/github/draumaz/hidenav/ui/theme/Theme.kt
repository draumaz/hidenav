package io.github.draumaz.hidenav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// minSdk 31 always has dynamic color; these only show if a vendor ships without it.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3D5BA9),
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF001849),
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF151B2C),
    surfaceContainer = Color(0xFFF3F4F9),
    surfaceBright = Color(0xFFFAF9FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5C4FF),
    primaryContainer = Color(0xFF234290),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDDE1F9),
    surfaceContainer = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
)

@Composable
fun HideNavTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = runCatching {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }.getOrElse { if (darkTheme) DarkColors else LightColors }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
