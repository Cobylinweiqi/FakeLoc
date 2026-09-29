package io.github.coby.fakeloc.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * A cool blue/grey palette rather than Material You's wallpaper-derived colours.
 *
 * Two reasons: the module needs a stable "active / idle" signal, and a
 * wallpaper-driven palette can hand back a red primary that reads as an error
 * state. The accent is fixed so `primary` always means "spoofing".
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1A6BE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF072F63),

    secondary = Color(0xFF0E9E8F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3F3EF),
    onSecondaryContainer = Color(0xFF063F39),

    background = Color(0xFFF5F8FC),
    onBackground = Color(0xFF11151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF11151C),
    surfaceVariant = Color(0xFFE9EEF6),
    onSurfaceVariant = Color(0xFF556072),

    outline = Color(0xFFC2CBDA),
    outlineVariant = Color(0xFFE2E8F1),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86B4FF),
    onPrimary = Color(0xFF002C61),
    primaryContainer = Color(0xFF13406F),
    onPrimaryContainer = Color(0xFFD6E4FF),

    secondary = Color(0xFF4FD1C5),
    onSecondary = Color(0xFF00332E),
    secondaryContainer = Color(0xFF0C4A44),
    onSecondaryContainer = Color(0xFFC9F2EE),

    background = Color(0xFF0C1118),
    onBackground = Color(0xFFE5EAF3),
    surface = Color(0xFF141B24),
    onSurface = Color(0xFFE5EAF3),
    surfaceVariant = Color(0xFF1E2733),
    onSurfaceVariant = Color(0xFF9DAABC),

    outline = Color(0xFF3A4658),
    outlineVariant = Color(0xFF232C39),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Monospace, tabular figures — coordinates that do not jitter as they change. */
val CoordinateTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    letterSpacing = 0.5.sp,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
)

/** Standard gap used between cards, so spacing stays consistent. */
val ScreenGutter = 16.dp
val CardRadius = 22.dp

@Composable
fun FakeLocTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // Match the system bars to the surface behind them. Done here rather than in
    // the activity so it follows the theme, including a runtime dark-mode switch.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
