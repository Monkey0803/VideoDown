package com.videodown.douyin.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Orange seed preserves the app identity on devices without wallpaper-based dynamic colors.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF9C4323), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCD), onPrimaryContainer = Color(0xFF370E00),
    secondary = Color(0xFF77574A), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCD), onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF626033), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9E5AC), onTertiaryContainer = Color(0xFF1E1D00),
    background = Color(0xFFFFF8F5), onBackground = Color(0xFF231A16),
    surface = Color(0xFFFFF8F5), onSurface = Color(0xFF231A16),
    surfaceVariant = Color(0xFFF5DED5), onSurfaceVariant = Color(0xFF53433C),
    surfaceDim = Color(0xFFE8D6CE), surfaceBright = Color(0xFFFFF8F5),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEAE2), surfaceContainerHigh = Color(0xFFF6E4DC),
    surfaceContainerHighest = Color(0xFFF0DED6),
    outline = Color(0xFF85736B), outlineVariant = Color(0xFFD8C2B8),
    inverseSurface = Color(0xFF392E29), inverseOnSurface = Color(0xFFFFEDE5),
    inversePrimary = Color(0xFFFFB598), surfaceTint = Color(0xFF9C4323),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB598), onPrimary = Color(0xFF5B1F08),
    primaryContainer = Color(0xFF7B3014), onPrimaryContainer = Color(0xFFFFDBCD),
    secondary = Color(0xFFE7BEAE), onSecondary = Color(0xFF442A1F),
    secondaryContainer = Color(0xFF5D4034), onSecondaryContainer = Color(0xFFFFDBCD),
    tertiary = Color(0xFFCCC992), onTertiary = Color(0xFF333209),
    tertiaryContainer = Color(0xFF4A491E), onTertiaryContainer = Color(0xFFE9E5AC),
    background = Color(0xFF1A120E), onBackground = Color(0xFFF0DED6),
    surface = Color(0xFF1A120E), onSurface = Color(0xFFF0DED6),
    surfaceVariant = Color(0xFF53433C), onSurfaceVariant = Color(0xFFD8C2B8),
    surfaceDim = Color(0xFF1A120E), surfaceBright = Color(0xFF423731),
    surfaceContainerLowest = Color(0xFF140D09), surfaceContainerLow = Color(0xFF231A16),
    surfaceContainer = Color(0xFF271E19), surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D332D),
    outline = Color(0xFFA08C82), outlineVariant = Color(0xFF53433C),
    inverseSurface = Color(0xFFF0DED6), inverseOnSurface = Color(0xFF392E29),
    inversePrimary = Color(0xFF9C4323), surfaceTint = Color(0xFFFFB598),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
private val AppTypography = Typography()

@Composable
fun VideoDownTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
