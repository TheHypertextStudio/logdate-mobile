package app.logdate.client.data.restore

import androidx.datastore.preferences.core.Preferences
import java.io.File

/** Partial deliveries may contain irreplaceable media or keys even when their database is absent. */
internal fun hasRetainedNativeContent(
    files: File,
    database: File,
    preferences: Preferences,
): Boolean =
    database.exists() ||
        preferences.asMap().any { (key, value) ->
            key.name.contains("draft") && value.toString().isNotBlank()
        } ||
        listOf(
            "media",
            "user_media",
            "audio_notes",
            "camera-captures",
            "watch_incoming",
            "db_recovery",
            "identity_recovery",
            "identity_recovery_device",
            "datastore",
        ).any {
            // An unreadable directory is retained content, too; background recovery cannot prove it empty.
            File(files, it).exists()
        }
