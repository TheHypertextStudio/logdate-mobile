package app.logdate.client.data.restore

import android.content.Context
import app.logdate.client.data.journals.ArchiveDraftStorageState
import app.logdate.client.data.journals.PartialDraftStorageRecovery
import app.logdate.client.data.notes.drafts.AndroidLocalEntryDraftStore
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.device.restore.PostRestoreDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** A draft can precede the first Room open. Give it the ordinary encrypted journal transfer contract. */
internal fun prepareNativeDraftOnlyDatabase(
    context: Context,
    initializeDatabase: () -> Unit,
    hasEntryDrafts: () -> Boolean = {
        File(context.filesDir, "datastore/entry_drafts.preferences_pb").exists() &&
            runBlocking { AndroidLocalEntryDraftStore(context).getAllDrafts().isNotEmpty() }
    },
) {
    val database = context.getDatabasePath("logdate")
    if (Files.exists(database.toPath(), LinkOption.NOFOLLOW_LINKS)) return
    val entryDraftsExist = hasEntryDrafts()
    val hasMainStoreDrafts =
        runBlocking {
            val preferences = createAndroidMainDataStore(context).data.first()
            val json = Json { ignoreUnknownKeys = true }
            preferences.asMap().any { (key, value) ->
                if (value !is String) return@any false
                when {
                    isRetainedDraftStorageKey(key.name) -> {
                        ArchiveDraftStorageState.decode(json, value).drafts.isNotEmpty()
                    }

                    key.name == "entry_drafts" -> {
                        ArchiveDraftStorageState.decode(json, value).drafts.isNotEmpty()
                    }

                    key.name.startsWith("editor_drafts_native_unparsed_") -> {
                        PartialDraftStorageRecovery.recover(json, value).drafts.isNotEmpty()
                    }

                    else -> {
                        false
                    }
                }
            }
        }
    if (!entryDraftsExist && !hasMainStoreDrafts) return

    val detector = PostRestoreDetector(context)
    check(detector.isNativeRestoreDeliveryComplete() && detector.pendingContentRecoveryId() == null) {
        "Native restore must finish before preparing a draft-only transfer"
    }
    check(listOf("-wal", "-shm", "-journal").none { Files.exists(File(database.path + it).toPath(), LinkOption.NOFOLLOW_LINKS) }) {
        "Existing journal sidecars must remain available for recovery"
    }
    initializeDatabase()
    check(Files.isRegularFile(database.toPath(), LinkOption.NOFOLLOW_LINKS) && database.length() > 0) {
        "Draft-only transfer did not create a durable journal"
    }
}
