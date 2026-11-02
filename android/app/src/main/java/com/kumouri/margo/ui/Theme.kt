package com.kumouri.margo.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Kumouri purple — Ceryce's primary brand colour. */
val KumouriPurple = Color(0xFF8E00FF)
/** Toxic green — the offset/accent. Used ONLY for "this is delivering", never for chrome. */
val ToxicGreen = Color(0xFF00FF0F)

// Status colours. Deliberately not Material's error/tertiary roles: this app's whole job is reporting
// whether a feed is alive, so the three states it can be in are first-class rather than borrowed.
val StatusOk = Color(0xFF00C853)
val StatusWarn = Color(0xFFFFA000)
val StatusBad = Color(0xFFD32F2F)
val StatusIdle = Color(0xFF9E9E9E)

private val DarkColors = darkColorScheme(
    primary = KumouriPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B0066),
    onPrimaryContainer = Color(0xFFE9D0FF),
    secondary = ToxicGreen,
    onSecondary = Color.Black,
    background = Color(0xFF121014),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF1B181F),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF2A2530),
    onSurfaceVariant = Color(0xFFCAC3D0),
    error = StatusBad,
)

private val LightColors = lightColorScheme(
    primary = KumouriPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0DCFF),
    onPrimaryContainer = Color(0xFF2C0048),
    secondary = Color(0xFF108A18),   // toxic green is unreadable on white; darkened for contrast only
    onSecondary = Color.White,
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
    error = StatusBad,
)

@Composable
fun MargoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    // LocalContext is referenced so the preview tooling resolves; harmless at runtime.
    @Suppress("UNUSED_EXPRESSION") LocalContext
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
