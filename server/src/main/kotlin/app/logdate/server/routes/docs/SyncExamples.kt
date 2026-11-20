package app.logdate.server.routes.docs

import app.logdate.server.routes.ErrorCase
import app.logdate.shared.model.StorageModeErrorCodes
import app.logdate.shared.model.sync.DeviceId

/** Shared identifiers and values for the sync examples, consistent with the overview's walkthrough. */
internal object SyncExamples {
    const val CONTENT_ID = "01J7Q2X4Y5Z6A7B8C9D0E1F2G3"
    const val JOURNAL_ID = "01J7Q2Y8N3M4K5L6P7Q8R9S0T1"
    const val DRAFT_ID = "01J7Q30C7D8E9F0G1H2J3K4M5N"
    const val MEDIA_ID = "6f1c2a8e-3b4d-3c5e-9f0a-1b2c3d4e5f60"
    const val BACKUP_ID = "b7e4d2c1-9a8f-4e6d-b5c4-a3f2e1d0c9b8"
    const val BACKUP_UPLOAD_ID = "5b1d9a7e-3c2f-4e8a-9b6d-0f4c2e7a1d93"

    /** The moment the entry was written on the device. */
    const val CREATED_AT = DocExamples.EPOCH_NOW

    /** The version the server assigned when it accepted the write; also the next `since` cursor. */
    const val SERVER_VERSION = 1_789_221_791_530L

    val deviceId = DeviceId(DocExamples.DEVICE_ID)

    const val NOTE_TEXT = "Walked to the lake before work. Fog on the water."

    val storageModeMismatch =
        ErrorCase(
            StorageModeErrorCodes.SYNC_STORAGE_MODE_MISMATCH,
            "The account's data isn't kept in legacy sync storage: its `storageMode` is `personalSpaceV1`, or one this " +
                "server doesn't recognize. Nothing was read or written. Read the account again from `/auth/me` and sync " +
                "the way its `storageMode` says.",
            "This account's data isn't kept in legacy sync storage; read the account again",
        )

    val formatUpgradeRequired =
        ErrorCase(
            StorageModeErrorCodes.SYNC_FORMAT_UPGRADE_REQUIRED,
            "The account's journal was moved into its personal space. Nothing was read or written. An app that can sync a " +
                "personal space reads the account again from `/auth/me`; one that can't asks the person to update it.",
            "This account's journal moved to a newer sync format; update the app to keep syncing",
        )

    val migrationInProgress =
        ErrorCase(
            StorageModeErrorCodes.SYNC_MIGRATION_IN_PROGRESS,
            "A device is checking the account's journal before moving it into its personal space. Nothing was written. " +
                "Keep the change queued and send it again after `Retry-After` seconds.",
            "This account's journal is being moved; send this change again later",
        )

    val serverMisconfigured =
        ErrorCase(
            "SERVER_MISCONFIGURED",
            "This deployment cannot validate tokens (no signing secret configured). No client action helps; the operator must fix the server configuration.",
            "Token service is not configured",
        )
    val invalidSince =
        ErrorCase(
            "INVALID_PARAMETER",
            "`since` is not a whole number. Send the `lastTimestamp` from the previous page, or `0`.",
            "since parameter must be a valid long",
        )
    val conflict =
        ErrorCase(
            "CONFLICT",
            "Your `versionConstraint.serverVersion` is older than what the server holds: another device wrote in between. " +
                "Fetch the current record, merge, and patch again with the new version.",
            "Server has a newer version",
        )
}
