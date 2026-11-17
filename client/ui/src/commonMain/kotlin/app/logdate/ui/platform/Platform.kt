package app.logdate.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Identifies the Apple/iOS-family platform the app is running on so common Compose code can
 * branch idiomatic decisions (FAB vs toolbar action, sheet styling, gesture treatment, …)
 * without reaching into `expect/actual` for every micro-decision.
 *
 * Resolved at link time on Apple targets, false everywhere else. Catalyst is reported as
 * [PlatformKind.MacCatalyst] so callers can opt into Mac-style chrome when needed.
 */
expect val currentPlatform: PlatformKind

enum class PlatformKind {
    Android,
    Ios,
    IpadOs,
    MacCatalyst,
    Desktop,
    ;

    val isApple: Boolean get() = this == Ios || this == IpadOs || this == MacCatalyst
}

/**
 * Composition local mirror of [currentPlatform]. Prefer this in Composable bodies so previews
 * and tests can override the resolved platform.
 */
val LocalPlatformKind = staticCompositionLocalOf { PlatformKind.Android }

@Composable
@ReadOnlyComposable
fun rememberPlatformKind(): PlatformKind = LocalPlatformKind.current
