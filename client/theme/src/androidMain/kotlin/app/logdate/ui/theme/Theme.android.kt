package app.logdate.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Resolves the active [ColorScheme] based on user preferences and platform capabilities.
 *
 * On Android 12+ (API 31+), returns a [dynamicDarkColorScheme] or [dynamicLightColorScheme]
 * derived from the user's wallpaper when [dynamicColor] is true. Falls back to [DarkColorScheme]
 * or [LightColorScheme] on older API levels or when [dynamicColor] is false.
 *
 * @param dynamicColor Whether to use a wallpaper-derived color scheme on supported devices.
 * @param darkTheme Whether to use the dark variant of the resolved color scheme.
 */
@Composable
actual fun rememberColorScheme(
    dynamicColor: Boolean,
    darkTheme: Boolean,
): ColorScheme =
    when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> {
            DarkColorScheme
        }

        else -> {
            LightColorScheme
        }
    }
