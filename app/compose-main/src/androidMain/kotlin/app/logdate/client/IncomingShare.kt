package app.logdate.client

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.logdate.client.feature.widgets.shortcuts.DynamicShortcutDescriptor
import app.logdate.client.media.AndroidManagedMediaDiscarder
import app.logdate.client.media.AndroidManagedMediaImportSource
import app.logdate.client.media.ManagedMediaImporter
import app.logdate.client.media.MediaManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

internal data class IncomingEditorShare(
    val initialText: String?,
    val attachments: List<String>,
    val targetJournalIds: List<Uuid> = emptyList(),
)

internal suspend fun Context.importIncomingEditorShare(
    intent: Intent,
    mediaManager: MediaManager,
): IncomingEditorShare? =
    withContext(Dispatchers.IO) {
        val managedMediaDiscarder = AndroidManagedMediaDiscarder(this@importIncomingEditorShare, mediaManager)
        val mediaImporter =
            ManagedMediaImporter(
                mediaManager = mediaManager,
                source = AndroidManagedMediaImportSource(this@importIncomingEditorShare),
                discardManagedMedia = managedMediaDiscarder::discard,
            )
        val action = intent.action ?: return@withContext null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return@withContext null
        }

        val sharedText =
            intent
                .getCharSequenceExtra(Intent.EXTRA_TEXT)
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotBlank)
        val attachments =
            intent.extractSharedUris().mapNotNull { uri ->
                importSharedAttachment(uri.toString()) {
                    mediaImporter.import(uri.toString())
                }
            }
        val targetJournalIds = listOfNotNull(intent.parseShareToJournalShortcutId())

        if (sharedText.isNullOrBlank() && attachments.isEmpty()) {
            null
        } else {
            IncomingEditorShare(sharedText, attachments, targetJournalIds)
        }
    }

internal suspend fun <T> importSharedAttachment(
    sourceDescription: String,
    importMedia: suspend () -> T,
): T? =
    try {
        importMedia()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Napier.e("Failed to import shared media: $sourceDescription", error)
        null
    }

private fun Intent.parseShareToJournalShortcutId(): Uuid? {
    val shortcutId = getStringExtra(Intent.EXTRA_SHORTCUT_ID) ?: return null
    val prefix = "${DynamicShortcutDescriptor.ShareToJournal.ID_PREFIX}:"
    if (!shortcutId.startsWith(prefix)) return null
    val rawUuid = shortcutId.removePrefix(prefix)
    return runCatching { Uuid.parse(rawUuid) }
        .onFailure { Napier.w("Could not parse journal id from sharing shortcut: $shortcutId", it) }
        .getOrNull()
}

private fun Intent.extractSharedUris(): List<Uri> {
    val streamUris =
        when (action) {
            Intent.ACTION_SEND -> listOfNotNull(getParcelableUriExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> getParcelableUriArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }

    if (streamUris.isNotEmpty()) {
        return streamUris.distinct()
    }

    return buildList {
        val clipData = clipData ?: return@buildList
        repeat(clipData.itemCount) { index ->
            clipData.getItemAt(index).uri?.let(::add)
        }
    }.distinct()
}

private fun Intent.getParcelableUriExtra(name: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

private fun Intent.getParcelableUriArrayListExtra(name: String): ArrayList<Uri>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }
