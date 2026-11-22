package app.logdate.wear.sync.v2.removal

import java.io.File

internal fun clearWatchFiles(
    filesDir: File,
    cacheDir: File,
) {
    listOf("audio_notes", "media", "synced_audio", "user_media", "identity_recovery", "identity_recovery_device").forEach { name ->
        val directory = File(filesDir, name)
        check(!directory.exists() || directory.deleteRecursively()) { "Watch media or recovery data could not be erased" }
    }
    cacheDir.listFiles()?.forEach { file ->
        check(file.deleteRecursively()) { "Watch cache could not be erased" }
    }
}
