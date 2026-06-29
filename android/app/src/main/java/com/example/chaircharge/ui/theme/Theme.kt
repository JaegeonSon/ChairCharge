package com.example.chaircharge.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = WheelBlueDark,
    secondary = WheelSecondaryText,
    tertiary = WheelBlueDark
)

private val LightColorScheme = lightColorScheme(
    primary = WheelBlue,
    primaryContainer = WheelBlueLight,
    secondary = WheelSecondaryText,
    secondaryContainer = WheelCard,
    tertiary = WheelBlue,
    background = WheelBackground,
    surface = androidx.compose.ui.graphics.Color.White,
    onBackground = WheelText,
    onSurface = WheelText,
    onSurfaceVariant = WheelSecondaryText,
    outline = WheelOutline,
    outlineVariant = WheelOutline
)

@Composable
fun ChairChargeTheme(
    darkTheme: Boolean = false,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
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
