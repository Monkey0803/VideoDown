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

// Fixed warm palette follows the approved 拾影 brand; darker orange keeps small text readable.
internal val LightColors = lightColorScheme(
    primary = Color(0xFFA64021), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE6D8), onPrimaryContainer = Color(0xFFA64021),
    secondary = Color(0xFF785F53), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE6D8), onSecondaryContainer = Color(0xFF33251F),
    tertiary = Color(0xFF865039), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDFC6), onTertiaryContainer = Color(0xFF402718),
    background = Color(0xFFFFF8F5), onBackground = Color(0xFF33251F),
    surface = Color(0xFFFFF8F5), onSurface = Color(0xFF33251F),
    surfaceVariant = Color(0xFFF3E6DF), onSurfaceVariant = Color(0xFF745E53),
    surfaceDim = Color(0xFFE8D8CF), surfaceBright = Color(0xFFFFF8F5),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFCFA),
    surfaceContainer = Color(0xFFFFF2EB), surfaceContainerHigh = Color(0xFFF9EBE3),
    surfaceContainerHighest = Color(0xFFF2E1D7),
    outline = Color(0xFF977D6F), outlineVariant = Color(0xFFE8D1C4),
    inverseSurface = Color(0xFF33251F), inverseOnSurface = Color(0xFFFFF8F5),
    inversePrimary = Color(0xFFFFAD75), surfaceTint = Color(0xFFA64021),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFFAD75), onPrimary = Color(0xFF4B2412),
    primaryContainer = Color(0xFF653421), onPrimaryContainer = Color(0xFFFFE6D8),
    secondary = Color(0xFFE7BEAE), onSecondary = Color(0xFF442A1F),
    secondaryContainer = Color(0xFF5D4034), onSecondaryContainer = Color(0xFFFFE6D8),
    tertiary = Color(0xFFF1BB97), onTertiary = Color(0xFF492916),
    tertiaryContainer = Color(0xFF62402A), onTertiaryContainer = Color(0xFFFFDFC6),
    background = Color(0xFF211813), onBackground = Color(0xFFFFF0E7),
    surface = Color(0xFF211813), onSurface = Color(0xFFFFF0E7),
    surfaceVariant = Color(0xFF53433C), onSurfaceVariant = Color(0xFFD8C2B8),
    surfaceDim = Color(0xFF211813), surfaceBright = Color(0xFF423731),
    surfaceContainerLowest = Color(0xFF140D09), surfaceContainerLow = Color(0xFF2B201A),
    surfaceContainer = Color(0xFF30241D), surfaceContainerHigh = Color(0xFF382A22),
    surfaceContainerHighest = Color(0xFF443229),
    outline = Color(0xFFA08C82), outlineVariant = Color(0xFF53433C),
    inverseSurface = Color(0xFFFFF0E7), inverseOnSurface = Color(0xFF392E29),
    inversePrimary = Color(0xFFA64021), surfaceTint = Color(0xFFFFAD75),
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
    dynamicColor: Boolean = false,
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
