package app.logdate.client.data.restore

import android.app.backup.FullBackupDataOutput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.logdate.client.data.account.NativeRestoreAccountPolicy
import app.logdate.client.database.openRetainedDatabaseCopy
import app.logdate.client.database.readRetainedDatabasePassphrases
import app.logdate.client.database.requirePortableCloudBackupKey
import app.logdate.client.device.restore.NativeBackupAgent
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.media.storage.AndroidMediaClaimFence
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/** The restricted callback retains a nonsecret request; the system job performs authentication. */
class NativeAccountBackupAgent : NativeBackupAgent() {
    private val journalTransfer by lazy { NativeJournalTransferSnapshot(this) }
    private val journalRestore by lazy { NativeJournalTransferRestore(this, ::restoreNativeJournalPreferences) }

    override fun onCreate() {
        discardAbandonedForProviderStart {
            NativeIosTransferSender(this, journalTransfer).discardAbandoned()
        }
        super.onCreate()
    }

    override fun inspectNativePrivacyRepairCopy(files: Map<String, File>) {
        val databaseFiles = files.filterKeys { it.startsWith("database/") }.mapKeys { it.key.removePrefix("database/") }
        if (databaseFiles.isEmpty()) return
        val keys =
            readRetainedDatabasePassphrases(
                listOfNotNull(files["files/db_recovery/passphrase_backup"], files["files/db_recovery/working_passphrase_backup"]),
            )
        try {
            checkNotNull(openRetainedDatabaseCopy(this, databaseFiles, keys, File(noBackupFilesDir, "privacy-repair-validation"))) {
                "Incoming native privacy metadata cannot validate the retained encrypted journal"
            }.use { }
        } finally {
            keys.forEach { it.fill(0) }
        }
    }

    override val retainedPreferenceNames: Set<String> = setOf("logdate_widget_instances")

    override fun requireCloudJournalRecoverability() =
        requirePortableCloudBackupKey(
            getDatabasePath("logdate"),
            File(filesDir, "db_recovery/passphrase_backup"),
        )

    override fun prepareNativeJournalTransferBackup() =
        runBlocking { AndroidMediaClaimFence(noBackupFilesDir).withClaim { journalTransfer.prepare() } }

    override fun backupSelectedNativeMedia(data: FullBackupDataOutput) =
        runBlocking {
            AndroidMediaClaimFence(noBackupFilesDir).withClaim {
                journalTransfer.backupSelectedMedia { fullBackupFile(it, data) }
            }
        }

    override fun backupNativeIosTransfer(data: FullBackupDataOutput) {
        runBlocking {
            AndroidMediaClaimFence(noBackupFilesDir).withClaim {
                NativeIosTransferSender(this@NativeAccountBackupAgent, journalTransfer).send(data.transportFlags) { file ->
                    fullBackupFile(file, data)
                }
            }
        }
    }

    override fun finishNativeJournalTransferBackup() =
        runBlocking { AndroidMediaClaimFence(noBackupFilesDir).withClaim { journalTransfer.finish() } }

    override fun restoreNativeJournalTransferSnapshot(): Boolean =
        runBlocking { AndroidMediaClaimFence(noBackupFilesDir).withClaim { journalRestore.restore() } }

    override fun wasNativeJournalTransferStaged(): Boolean = journalRestore.wasStaged()

    override fun finishNativeJournalTransferRestore(): Boolean =
        runBlocking { AndroidMediaClaimFence(noBackupFilesDir).withClaim { journalRestore.finish() } }

    override fun retainedNativeCameraReconciliationScheduled() =
        runBlocking { AndroidMediaClaimFence(noBackupFilesDir).withClaim { journalRestore.reconciliationScheduled() } }

    override fun scheduleRetainedJournalReconciliation(): Boolean =
        app.logdate.client.data.restore
            .scheduleRetainedJournalReconciliation(this)

    override suspend fun retainAccountRecoveryBeforeCompletion(
        preferences: DataStore<Preferences>,
        deliveryId: String,
    ) {
        retain(preferences, freshDelivery = true, delivery = deliveryId, requireScheduled = true)
    }

    override suspend fun retainAccountRecovery(
        preferences: DataStore<Preferences>,
        freshDelivery: Boolean,
    ) {
        val delivery = PostRestoreDetector(this).pendingAccountRecoveryId() ?: return
        retain(preferences, freshDelivery, delivery, requireScheduled = false)
    }

    private suspend fun retain(
        preferences: DataStore<Preferences>,
        freshDelivery: Boolean,
        delivery: String,
        requireScheduled: Boolean,
    ) {
        val detector = PostRestoreDetector(this)
        if (detector.hasMissingNativePrivacyPayload()) return
        val generation = detector.nativeRestoreSessionId() ?: return
        val snapshot = preferences.data.first()
        val request = nativeAccountRecoveryRequest(delivery, generation, snapshot) ?: return
        if (!NativeRestoreAccountPolicy(
                request.owner,
                request.binding,
                hasRetainedNativeContent(filesDir, getDatabasePath("logdate"), snapshot),
            ).canAttempt
        ) {
            return
        }
        val inbox = NativeAccountRecoveryInbox(noBackupFilesDir)
        val retained =
            try {
                if (freshDelivery) inbox.retainFreshDelivery(request) else inbox.retain(request)
            } catch (error: Exception) {
                // JobScheduler's persisted extras retain the original request if private storage cannot.
                if (scheduleNativeAccountRecovery(this, request)) return
                throw error
            }
        if (retained) {
            val scheduled = scheduleNativeAccountRecovery(this, request)
            check(scheduled || !requireScheduled) { "Native account recovery scheduling remains pending" }
        } else {
            check(!requireScheduled) { "Native account recovery admission changed" }
        }
    }
}

/** A stale iOS export must not prevent ordinary Android provider callbacks from starting. */
internal fun discardAbandonedForProviderStart(cleanup: () -> Unit): Boolean =
    try {
        cleanup()
        true
    } catch (error: Exception) {
        Napier.w("Native iOS transfer cleanup remains pending", error)
        false
    }
