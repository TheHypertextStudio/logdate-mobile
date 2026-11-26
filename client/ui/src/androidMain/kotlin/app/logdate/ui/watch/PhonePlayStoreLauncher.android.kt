package app.logdate.ui.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.aakira.napier.Napier

@Composable
actual fun rememberPhonePlayStoreLauncher(): (() -> Boolean)? {
    val context = LocalContext.current
    return remember(context) { { launchPhoneStoreListing(context) } }
}

private fun launchPhoneStoreListing(context: Context): Boolean {
    val packageName = context.packageName
    if (launchStoreIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))) {
        return true
    }
    return launchStoreIntent(
        context,
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")),
    )
}

private fun launchStoreIntent(
    context: Context,
    intent: Intent,
): Boolean =
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (failure: Exception) {
        Napier.w("Could not open LogDate store listing on this phone", failure)
        false
    }
