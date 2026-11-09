package app.logdate.client

import android.app.HandoffActivityParams
import android.os.Build

/**
 * Opts the activity into the Android 16+ handoff API so a session can continue on another
 * device. [MainActivity.onHandoffActivityDataRequested] supplies the fallback web URL.
 */
internal fun MainActivity.enableHandoffIfSupported() {
    if (Build.VERSION.SDK_INT < 37) return
    // TODO: Enable web handoff once logdate.app can reconstruct app state from the URL.
    //  Flip setAllowHandoffWithoutPackageInstalled to true and verify each deep-link path
    //  renders the correct content on the web before enabling.
    val handoffParams =
        HandoffActivityParams
            .Builder()
            .setAllowHandoffWithoutPackageInstalled(false)
            .build()
    setHandoffEnabled(true, handoffParams)
}
