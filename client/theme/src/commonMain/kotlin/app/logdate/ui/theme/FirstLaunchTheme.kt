@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * LogDate's own look, for the moments where the app introduces itself: first launch, signing in
 * and LogDate Cloud.
 *
 * The rest of the app takes its colors from the person's device. These screens do not, because
 * they are where LogDate has to be recognisable as itself: warm cream paper, plum for what you
 * tap, peach for what is being offered. Every color role is set, so a step drawn inside this theme
 * never falls back to a device color half way down the screen. Headings are heavier than the
 * app's, and controls use the expressive motion and shapes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FirstLaunchTheme(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val colors = if (dark) MaterialTheme.colorScheme.warmDark() else MaterialTheme.colorScheme.warmLight()
    val base = MaterialTheme.typography
    val typography =
        base.copy(
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
            headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), typography = typography) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}

private fun ColorScheme.warmLight(): ColorScheme =
    copy(
        primary = Color(0xFF82506B),
        onPrimary = Color(0xFFFFFAF5),
        primaryContainer = Color(0xFFF3DCE6),
        onPrimaryContainer = Color(0xFF3B1F2E),
        inversePrimary = Color(0xFFE1A9C1),
        secondary = Color(0xFF7A5C68),
        onSecondary = Color(0xFFFFFAF5),
        secondaryContainer = Color(0xFFEFE0E4),
        onSecondaryContainer = Color(0xFF4A3540),
        tertiary = Color(0xFFA9603F),
        onTertiary = Color(0xFFFFFAF5),
        tertiaryContainer = Color(0xFFF1D9C5),
        onTertiaryContainer = Color(0xFF614433),
        background = Color(0xFFF7F2ED),
        onBackground = Color(0xFF322A30),
        surface = Color(0xFFF7F2ED),
        onSurface = Color(0xFF322A30),
        surfaceVariant = Color(0xFFEDE3DD),
        onSurfaceVariant = Color(0xFF6B5E64),
        surfaceTint = Color(0xFF82506B),
        inverseSurface = Color(0xFF322A30),
        inverseOnSurface = Color(0xFFF7F2ED),
        outline = Color(0xFF9A8A90),
        outlineVariant = Color(0xFFE4D7D1),
        surfaceBright = Color(0xFFFFFBF7),
        surfaceDim = Color(0xFFE3DAD3),
        surfaceContainerLowest = Color(0xFFFFFDFA),
        surfaceContainerLow = Color(0xFFFBF6F1),
        surfaceContainer = Color(0xFFF3ECE6),
        surfaceContainerHigh = Color(0xFFEDE5DE),
        surfaceContainerHighest = Color(0xFFE7DED7),
    )

// On a dark table the paper is the lightest surface, so the lowest container is the brightest one.
private fun ColorScheme.warmDark(): ColorScheme =
    copy(
        primary = Color(0xFFE1A9C1),
        onPrimary = Color(0xFF3B2030),
        primaryContainer = Color(0xFF5A3A4A),
        onPrimaryContainer = Color(0xFFFFD9E6),
        inversePrimary = Color(0xFF82506B),
        secondary = Color(0xFFD8BFC8),
        onSecondary = Color(0xFF3B2B32),
        secondaryContainer = Color(0xFF40363E),
        onSecondaryContainer = Color(0xFFEADAE8),
        tertiary = Color(0xFFF0B596),
        onTertiary = Color(0xFF4A2A18),
        tertiaryContainer = Color(0xFF513740),
        onTertiaryContainer = Color(0xFFFFE4D3),
        background = Color(0xFF211B20),
        onBackground = Color(0xFFFFF3E9),
        surface = Color(0xFF211B20),
        onSurface = Color(0xFFFFF3E9),
        surfaceVariant = Color(0xFF40363B),
        onSurfaceVariant = Color(0xFFC6B6B9),
        surfaceTint = Color(0xFFE1A9C1),
        inverseSurface = Color(0xFFFFF3E9),
        inverseOnSurface = Color(0xFF322A30),
        outline = Color(0xFF9B8990),
        outlineVariant = Color(0xFF58434D),
        surfaceBright = Color(0xFF40363B),
        surfaceDim = Color(0xFF191418),
        surfaceContainerLowest = Color(0xFF30272E),
        surfaceContainerLow = Color(0xFF272025),
        surfaceContainer = Color(0xFF2B242A),
        surfaceContainerHigh = Color(0xFF362D34),
        surfaceContainerHighest = Color(0xFF41373F),
    )
