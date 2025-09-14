package saaicom.tcb.docuscanner.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    secondary = Color.White,
    tertiary = Color.White,
    background = Color.Gray,
    onBackground = BottomNavBackground,
    surface = AppBodyBackground, // Use your custom dark gray for surface (often used for navigation)
    onSurface = BottomNavSelectedIcon, // Color for content on surface
    surfaceVariant = BottomNavIndicatorGray, // For indicator or other variants
    /* Other default colors to override */
    onPrimary = Color.LightGray,
    onSecondary = Color.White,
    onTertiary = Color.White

    /* all possible variables
    primary: Color = ColorDarkTokens.Primary,
    onPrimary: Color = ColorDarkTokens.OnPrimary,
    primaryContainer: Color = ColorDarkTokens.PrimaryContainer,
    onPrimaryContainer: Color = ColorDarkTokens.OnPrimaryContainer,
    inversePrimary: Color = ColorDarkTokens.InversePrimary,
    secondary: Color = ColorDarkTokens.Secondary,
    onSecondary: Color = ColorDarkTokens.OnSecondary,
    secondaryContainer: Color = ColorDarkTokens.SecondaryContainer,
    onSecondaryContainer: Color = ColorDarkTokens.OnSecondaryContainer,
    tertiary: Color = ColorDarkTokens.Tertiary,
    onTertiary: Color = ColorDarkTokens.OnTertiary,
    tertiaryContainer: Color = ColorDarkTokens.TertiaryContainer,
    onTertiaryContainer: Color = ColorDarkTokens.OnTertiaryContainer,
    background: Color = ColorDarkTokens.Background,
    onBackground: Color = ColorDarkTokens.OnBackground,
    surface: Color = ColorDarkTokens.Surface,
    onSurface: Color = ColorDarkTokens.OnSurface,
    surfaceVariant: Color = ColorDarkTokens.SurfaceVariant,
    onSurfaceVariant: Color = ColorDarkTokens.OnSurfaceVariant,
    surfaceTint: Color = primary,
    inverseSurface: Color = ColorDarkTokens.InverseSurface,
    inverseOnSurface: Color = ColorDarkTokens.InverseOnSurface,
    error: Color = ColorDarkTokens.Error,
    onError: Color = ColorDarkTokens.OnError,
    errorContainer: Color = ColorDarkTokens.ErrorContainer,
    onErrorContainer: Color = ColorDarkTokens.OnErrorContainer,
    outline: Color = ColorDarkTokens.Outline,
    outlineVariant: Color = ColorDarkTokens.OutlineVariant,
    scrim: Color = ColorDarkTokens.Scrim,
    surfaceBright: Color = ColorDarkTokens.SurfaceBright,
    surfaceContainer: Color = ColorDarkTokens.SurfaceContainer,
    surfaceContainerHigh: Color = ColorDarkTokens.SurfaceContainerHigh,
    surfaceContainerHighest: Color = ColorDarkTokens.SurfaceContainerHighest,
    surfaceContainerLow: Color = ColorDarkTokens.SurfaceContainerLow,
    surfaceContainerLowest: Color = ColorDarkTokens.SurfaceContainerLowest,
    surfaceDim: Color = ColorDarkTokens.SurfaceDim
     */
)

private val LightColorScheme = lightColorScheme(
    primary = Color.White,
    secondary = Color.White,
    tertiary = Color.White,
    background = Color.LightGray,
    onBackground = BottomNavBackground,
    surface = AppBodyBackground, // Use your custom dark gray for surface (often used for navigation)
    onSurface = BottomNavSelectedIcon, // Color for content on surface
    surfaceVariant = BottomNavIndicatorGray, // For indicator or other variants
    /* Other default colors to override */
    onPrimary = Color.LightGray,
    onSecondary = Color.White,
    onTertiary = Color.White
)

@Composable
fun DocuScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}