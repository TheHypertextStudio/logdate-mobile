package app.logdate.client.sharing

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logdate.client.sharing.generated.resources.Res
import logdate.client.sharing.generated.resources.share_link_copied
import logdate.client.sharing.generated.resources.share_title_share_qr_code
import org.jetbrains.compose.resources.getString

/**
 * Handles LogDate's own share sheet callbacks: copying a link, sharing a QR code, and logging which
 * app the person chose. Content shared into LogDate from other apps goes to MainActivity instead.
 */
class ShareReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            CustomIntents.ACTION_COPY_LINK -> {
                finishAfter { copyLinkToClipboard(context, intent.getStringExtra(CustomIntents.EXTRA_SHARE_TEXT)) }
            }

            CustomIntents.ACTION_SHARE_QR_CODE -> {
                finishAfter { shareQrCode(context, intent) }
            }

            else -> {
                logChosenComponent(intent)
            }
        }
    }

    /**
     * Runs [block] after [onReceive] returns and finishes the broadcast once it completes, so the
     * confirmation and the share sheet can read their words from LogDate's resources first.
     */
    private fun finishAfter(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun logChosenComponent(intent: Intent) {
        val clickedComponent: ComponentName? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
            }
        clickedComponent?.let { component ->
            Napier.i("User shared via ${component.flattenToShortString()}")
        }
    }

    private suspend fun copyLinkToClipboard(
        context: Context,
        text: String?,
    ) {
        val linkText = text?.takeIf(String::isNotBlank) ?: return
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("LogDate share", linkText))
        Toast.makeText(context, getString(Res.string.share_link_copied), Toast.LENGTH_SHORT).show()
    }

    private suspend fun shareQrCode(
        context: Context,
        intent: Intent,
    ) {
        val shareUri = intent.parcelableExtra<Uri>(CustomIntents.EXTRA_SHARE_URI) ?: return
        context.shareContent(
            ShareContentRequest(
                text = intent.getStringExtra(CustomIntents.EXTRA_SHARE_TEXT),
                mediaUris = listOf(shareUri),
                title = intent.getStringExtra(CustomIntents.EXTRA_SHARE_TITLE),
                chooserTitle = getString(Res.string.share_title_share_qr_code),
                copyText = intent.getStringExtra(CustomIntents.EXTRA_SHARE_TEXT),
                actionLabels = shareActionLabels(),
            ),
        )
    }

    private inline fun <reified T : Any> Intent.parcelableExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
}
