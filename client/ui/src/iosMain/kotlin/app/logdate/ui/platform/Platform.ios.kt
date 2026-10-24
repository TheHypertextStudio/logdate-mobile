package app.logdate.ui.platform

import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad

/**
 * Resolves the Apple-side platform once at process start by reading
 * [UIDevice.userInterfaceIdiom]. Catalyst reports `.mac`, but its raw value isn't bound in
 * Kotlin/Native at the moment, so we treat any non-pad, non-phone idiom as Catalyst until a
 * dedicated build flag lands with the L2 Catalyst enablement work.
 */
actual val currentPlatform: PlatformKind =
    when (UIDevice.currentDevice.userInterfaceIdiom) {
        UIUserInterfaceIdiomPad -> PlatformKind.IpadOs

        // 0 = phone (UIUserInterfaceIdiomPhone), 5 = mac (UIUserInterfaceIdiomMac, Catalyst)
        5L -> PlatformKind.MacCatalyst

        else -> PlatformKind.Ios
    }
