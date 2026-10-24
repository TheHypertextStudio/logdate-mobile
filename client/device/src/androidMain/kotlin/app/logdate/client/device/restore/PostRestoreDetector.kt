package app.logdate.client.device.restore

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Durable native delivery state; content and account completion are acknowledged independently. */
class PostRestoreDetector(
    private val context: Context,
) {
    /** Identity only; the caller must separately prove the completed incoming policy and protected bytes. */
    fun capturePrivacyRepairIdentity(): NativePrivacyRepairIdentity? =
        synchronized(lock) { readNativePrivacyRepairIdentity(context.noBackupFilesDir) }

    /** Caller holds the delivery gate and has durably committed only the proven privacy fields. */
    fun acknowledgePrivacyRepair(expected: NativePrivacyRepairIdentity): Boolean =
        synchronized(lock) { acknowledgeNativePrivacyRepair(context.noBackupFilesDir, expected) }

    /** Even damaged retained delivery receipts prevent portable legacy state becoming local authority. */
    fun hasNativeRestoreHistory(): Boolean =
        synchronized(lock) {
            listOf(pendingRestoreFile(), restorePrivacyFile(), restoreFingerprintFile()).any { file ->
                try {
                    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    true
                } catch (_: NoSuchFileException) {
                    false
                }
            }
        }

    fun recordRestoreCompleted() {
        runBlocking {
            AndroidNativeRestoreDeliveryGate.withLock {
                recordRestoreCompletedUnderDeliveryLock()
            }
        }
    }

    internal fun recordRestoreCompletedUnderDeliveryLock() {
        synchronized(lock) {
            val delivery = readDelivery()
            val restoreSessionId =
                when (delivery?.first) {
                    RESTORE_IN_PROGRESS -> delivery.second
                    DELIVERY_PENDING -> nativeRestoreSessionId()
                    else -> null
                }
            val payloadReceived = wasReceivedForSession(restorePayloadReceivedFile(), restoreSessionId, RESTORE_PAYLOAD_RECEIVED)
            val databaseReceived = wasReceivedForSession(restoreDatabaseReceivedFile(), restoreSessionId, RESTORE_DATABASE_RECEIVED)
            val restoreInProgress = restoreSessionId != null
            // App startup can create an empty SQLite database after the callback. Keep an
            // unacknowledged missing-content delivery pending unless Android delivered the database.
            if ((!payloadReceived || !databaseReceived) &&
                readDelivery()?.let { it.first == CONTENT_MISSING && it.second != null } == true
            ) {
                return@synchronized
            }
            val fingerprint = restoreFingerprint()
            if (!restoreInProgress && !payloadReceived && fingerprint != null && fingerprint in readRestoreFingerprints()) {
                return@synchronized
            }
            val id = nativeRestoreSessionId()?.let { readIdentifier(accountAdmissionFile(it)) } ?: Uuid.random().toString()
            writeAtomically(mediaQuarantineFile(), id)
            writeAtomically(pendingRestoreFile(), "$DELIVERY_PENDING:$id")
            writeAtomically(accountRecoveryFile(), id)
            deleteIfPresent(accountResolvedFile())
            val contentPresent =
                if (restoreInProgress) {
                    databaseReceived && hasDatabase()
                } else {
                    (!payloadReceived || databaseReceived) && hasDatabase()
                }
            writeAtomically(pendingRestoreFile(), "${if (contentPresent) CONTENT_PRESENT else CONTENT_MISSING}:$id")
            if (fingerprint != null) writeRestoreFingerprints(listOf(fingerprint))
            if (payloadReceived) {
                deleteIfPresent(restorePayloadReceivedFile())
                deleteIfPresent(restoreDatabaseReceivedFile())
            }
        }
    }

    /** Reserved only after the framework completion callback has finalized preference custody. */
    internal fun reserveAccountRecoveryForCompletion(): String =
        synchronized(lock) {
            val generation = checkNotNull(nativeRestoreSessionId())
            val file = accountAdmissionFile(generation)
            readIdentifier(file)?.let { return@synchronized it }
            check(!file.exists()) { "Native account completion admission is unreadable" }
            Uuid.random().toString().also { writeAtomically(file, it) }
        }

    internal fun completeReservedAccountRecovery(
        delivery: String,
        generation: String,
    ): Boolean =
        synchronized(lock) {
            if (nativeRestoreSessionId() != generation ||
                readIdentifier(accountAdmissionFile(generation)) != delivery
            ) {
                return@synchronized false
            }
            if (!isNativeRestoreDeliveryComplete()) recordRestoreCompletedUnderDeliveryLock()
            isNativeRestoreDeliveryComplete() && pendingAccountRecoveryId() == delivery
        }

    /** Records Android delivering a backed-up file, distinguishing a retry from a duplicate callback. */
    fun markNativeRestorePayloadReceived(databaseDelivered: Boolean) =
        synchronized(lock) {
            val restoreSessionId = readDelivery()?.takeIf { it.first == RESTORE_IN_PROGRESS }?.second
            val receipt = { value: String -> restoreSessionId?.let { "$value:$it" } ?: value }
            if (!wasReceivedForSession(restorePayloadReceivedFile(), restoreSessionId, RESTORE_PAYLOAD_RECEIVED)) {
                writeAtomically(restorePayloadReceivedFile(), receipt(RESTORE_PAYLOAD_RECEIVED))
            }
            if (databaseDelivered && !wasReceivedForSession(restoreDatabaseReceivedFile(), restoreSessionId, RESTORE_DATABASE_RECEIVED)) {
                writeAtomically(restoreDatabaseReceivedFile(), receipt(RESTORE_DATABASE_RECEIVED))
            }
        }

    /** A new BackupAgent instance starts a new delivery boundary; abandoned receipts cannot cross it. */
    fun beginNativeRestoreSession(retainPreferences: Boolean = false) =
        synchronized(lock) {
            NativeInstallationResetMarker(context.noBackupFilesDir).clear()
            val sessionId = Uuid.random().toString()
            writeAtomically(restorePrivacyFile(), "pending:$sessionId${if (retainPreferences) ":prefs-v1" else ""}")
            // Publish the new generation first. If deletion is interrupted, prior receipts still
            // carry the old generation and cannot establish that this delivery contained a file.
            writeAtomically(pendingRestoreFile(), "$RESTORE_IN_PROGRESS:$sessionId")
            deleteIfPresent(restorePayloadReceivedFile())
            deleteIfPresent(restoreDatabaseReceivedFile())
        }

    /** Includes the post-callback normalization (such as removing the copied device id). */
    fun refreshRestoreFingerprint() =
        synchronized(lock) {
            val fingerprint = restoreFingerprint() ?: return@synchronized
            writeRestoreFingerprints((readRestoreFingerprints() + fingerprint).distinct().takeLast(MAX_RESTORE_FINGERPRINTS))
        }

    fun hasMissingNativePrivacyPayload(): Boolean =
        synchronized(lock) {
            val file = restorePrivacyFile()
            if (!file.exists()) return@synchronized false
            val receipt = readSmallFile(file) ?: return@synchronized true
            val parts = receipt.split(':')
            parts.first() != "received" || !validPrivacyReceipt(parts)
        }

    /** The privacy receipt retains the delivery generation after content reconciliation. */
    fun nativeRestoreSessionId(): String? =
        synchronized(lock) {
            readSmallFile(restorePrivacyFile())?.split(':')?.takeIf(::validPrivacyReceipt)?.get(1)
        }

    fun retainedPreferenceSessionId(): String? =
        synchronized(lock) {
            readSmallFile(restorePrivacyFile())?.split(':')?.takeIf { validPrivacyReceipt(it) && it.last() == "prefs-v1" }?.get(1)
        }

    internal fun canResumeNativeRestoreFinalization(expectedSessionId: String): Boolean =
        synchronized(lock) {
            if (retainedPreferenceSessionId() != expectedSessionId) return@synchronized false
            val delivery = readDelivery() ?: return@synchronized false
            when (delivery.first) {
                RESTORE_IN_PROGRESS -> delivery.second == expectedSessionId
                DELIVERY_PENDING -> true
                else -> false
            }
        }

    private fun validPrivacyReceipt(parts: List<String>): Boolean =
        parts.size in 2..3 &&
            parts[0] in setOf("pending", "received") &&
            runCatching { Uuid.parse(parts[1]) }.isSuccess &&
            (parts.size == 2 || parts[2] == "prefs-v1")

    fun markNativePrivacyPayloadReceived() =
        synchronized(lock) {
            val session = readDelivery()?.takeIf { it.first == RESTORE_IN_PROGRESS }?.second ?: return@synchronized
            val suffix = if (retainedPreferenceSessionId() != null) ":prefs-v1" else ""
            writeAtomically(restorePrivacyFile(), "received:$session$suffix")
        }

    internal fun acknowledgeEmptyNativePrivacyPayload() =
        synchronized(lock) {
            if (hasMissingNativePrivacyPayload()) {
                val suffix = if (retainedPreferenceSessionId() != null) ":prefs-v1" else ""
                val session = nativeRestoreSessionId() ?: Uuid.random().toString()
                writeAtomically(restorePrivacyFile(), "received:$session$suffix")
            }
        }

    fun pendingMediaQuarantineId(): String? =
        synchronized(lock) {
            val file = mediaQuarantineFile()
            if (!file.exists()) null else readIdentifier(file) ?: Uuid.random().toString().also { writeAtomically(file, it) }
        }

    fun acknowledgeMediaQuarantine(id: String): Boolean =
        synchronized(lock) {
            if (readIdentifier(mediaQuarantineFile()) != id) return@synchronized false
            deleteIfPresent(mediaQuarantineFile())
            true
        }

    /** An explicitly retained locked journal is not restored merely because a working DB opens. */
    fun recordRetainedJournalRecovery(id: String) =
        synchronized(lock) {
            Uuid.parse(id)
            writeAtomically(pendingRestoreFile(), "$CONTENT_MISSING:$id")
            writeAtomically(accountRecoveryFile(), id)
            deleteIfPresent(accountResolvedFile())
        }

    fun pendingContentRecoveryId(): String? =
        synchronized(lock) {
            val record = readDelivery() ?: return@synchronized null
            if (record.first != CONTENT_MISSING) return@synchronized null
            record.second ?: Uuid.random().toString().also { writeAtomically(pendingRestoreFile(), "$CONTENT_MISSING:$it") }
        }

    fun isContentRecoveryCurrent(deliveryId: String): Boolean =
        synchronized(lock) {
            readDelivery() == (CONTENT_MISSING to deliveryId)
        }

    fun isNativeRestoreDeliveryComplete(): Boolean =
        synchronized(lock) {
            val marker = pendingRestoreFile()
            if (!marker.exists()) return@synchronized true
            when (readDelivery()?.first) {
                CONTENT_PRESENT, CONTENT_MISSING, CONTENT_COMPLETE -> true
                else -> false
            }
        }

    /** Detects a finished delivery when Android recreates the agent for a duplicate callback. */
    fun hasCompletedNativeRestoreDelivery(): Boolean =
        synchronized(lock) {
            when (readDelivery()?.first) {
                CONTENT_PRESENT, CONTENT_MISSING, CONTENT_COMPLETE -> true
                RESTORE_IN_PROGRESS -> false
                else -> !pendingRestoreFile().exists() && readRestoreFingerprints().isNotEmpty()
            }
        }

    fun acknowledgeContentRecovery(deliveryId: String): Boolean =
        synchronized(lock) {
            if (!isContentRecoveryCurrent(deliveryId)) return@synchronized false
            writeAtomically(pendingRestoreFile(), "content-complete:$deliveryId")
            markDeviceInitialized()
            refreshRestoreFingerprintLocked()
            true
        }

    fun isAccountRecoveryPending(): Boolean =
        synchronized(lock) {
            accountRecoveryFile().exists() || (!initializedFile().exists() && !accountResolvedFile().exists())
        }

    fun pendingAccountRecoveryId(): String? =
        synchronized(lock) {
            if (!isAccountRecoveryPending()) return@synchronized null
            val file = accountRecoveryFile()
            readIdentifier(file) ?: Uuid.random().toString().also { writeAtomically(file, it) }
        }

    fun markAccountRecoveryPending() =
        synchronized(lock) {
            if (!accountRecoveryFile().exists()) writeAtomically(accountRecoveryFile(), Uuid.random().toString())
            deleteIfPresent(accountResolvedFile())
        }

    fun acknowledgeAccountRecovery(expectedId: String? = pendingAccountRecoveryId()) =
        synchronized(lock) {
            if (expectedId != null && readIdentifier(accountRecoveryFile()) != expectedId) return@synchronized
            writeAtomically(accountResolvedFile(), expectedId ?: "resolved")
            deleteIfPresent(accountRecoveryFile())
            refreshRestoreFingerprintLocked()
        }

    fun detect(isOnboarded: Boolean): PostRestoreType =
        synchronized(lock) {
            val pending = pendingRestoreFile()
            if (initializedFile().exists() && !pending.exists()) return@synchronized PostRestoreType.NONE
            if (!isOnboarded && !pending.exists()) return@synchronized PostRestoreType.NONE
            when (readDelivery()?.first) {
                CONTENT_PRESENT, "content-complete" -> {
                    PostRestoreType.CONTENT_RESTORED
                }

                CONTENT_MISSING -> {
                    PostRestoreType.CONTENT_MISSING
                }

                RESTORE_IN_PROGRESS -> {
                    PostRestoreType.RECOVERY_REQUIRED
                }

                else -> {
                    when {
                        hasDatabase() -> PostRestoreType.CONTENT_RESTORED
                        pending.exists() -> PostRestoreType.RECOVERY_REQUIRED
                        else -> PostRestoreType.CONTENT_MISSING
                    }
                }
            }
        }

    /** A fresh or restored installation cannot infer a lost privacy policy from readable content. */
    fun shouldValidatePrivacyMetadata(): Boolean =
        synchronized(lock) {
            !initializedFile().exists() || pendingRestoreFile().exists() || hasCompletedNativeRestoreDelivery()
        }

    fun markDeviceInitialized() =
        synchronized(lock) {
            writeAtomically(initializedFile(), "initialized")
            deleteIfPresent(pendingRestoreFile())
        }

    private fun readDelivery(): Pair<String, String?>? {
        val text = readSmallFile(pendingRestoreFile()) ?: return null
        if (text == CONTENT_PRESENT || text == CONTENT_MISSING) return text to null
        val parts = text.split(':', limit = 2)
        if (parts.size != 2 || runCatching { Uuid.parse(parts[1]) }.isFailure) return null
        return parts[0] to parts[1]
    }

    private fun readIdentifier(file: File): String? = readSmallFile(file)?.takeIf { runCatching { Uuid.parse(it) }.isSuccess }

    private fun wasReceivedForSession(
        file: File,
        sessionId: String?,
        receipt: String,
    ): Boolean {
        val saved = file.readTextOrNull() ?: return false
        return if (sessionId == null) saved == receipt else saved == "$receipt:$sessionId"
    }

    private fun refreshRestoreFingerprintLocked() {
        val fingerprint = restoreFingerprint() ?: return
        writeRestoreFingerprints((readRestoreFingerprints() + fingerprint).distinct().takeLast(MAX_RESTORE_FINGERPRINTS))
    }

    /** Fingerprints compact state; large databases and media use file metadata, with payload receipts for new transfers. */
    private fun restoreFingerprint(): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val files = context.filesDir
            val entries = mutableListOf<Pair<String, Pair<File, Boolean>>>()

            fun add(
                path: String,
                hashContents: Boolean,
            ) {
                val root = File(files, path)
                if (!root.exists()) return
                if (root.isFile) {
                    entries += path to (root to hashContents)
                    return
                }
                root
                    .walkTopDown()
                    .filter { file ->
                        file.isFile &&
                            !file.relativeTo(files).invariantSeparatorsPath.contains("/staging/") &&
                            !file.name.endsWith(".part")
                    }.forEach { file ->
                        entries += file.relativeTo(files).invariantSeparatorsPath to (file to hashContents)
                    }
            }

            listOf("logdate", "logdate-wal", "logdate-shm").forEach { name ->
                val file = context.getDatabasePath(name)
                if (file.isFile) entries += "database/$name" to (file to false)
            }
            add("logdate.preferences_pb", true)
            File(context.applicationInfo.dataDir, "shared_prefs/logdate_widget_instances.xml")
                .takeIf { it.isFile }
                ?.let { entries += "shared_prefs/logdate_widget_instances.xml" to (it to true) }
            add("datastore", true)
            add("db_recovery", true)
            add("identity_recovery", true)
            add("archive_recovery/records", true)
            add("archive_recovery/sources", false)
            add("media", false)
            add("user_media", false)
            add("audio_notes", false)
            add("camera-captures", false)
            entries.sortedBy { it.first }.forEach { (path, entry) ->
                val (file, hashContents) = entry
                digest.update(path.encodeToByteArray())
                digest.update(0.toByte())
                digest.update(file.length().toString().encodeToByteArray())
                digest.update(0.toByte())
                digest.update(file.lastModified().toString().encodeToByteArray())
                digest.update(0.toByte())
                if (hashContents) {
                    file.inputStream().use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }.getOrNull()

    private fun readRestoreFingerprints(): List<String> =
        runCatching {
            restoreFingerprintFile().readLines().filter { it.matches(Regex("[0-9a-f]{64}")) }
        }.getOrDefault(emptyList())

    private fun writeRestoreFingerprints(fingerprints: List<String>) =
        writeAtomically(restoreFingerprintFile(), fingerprints.joinToString("\n"))

    private fun readSmallFile(file: File): String? =
        runCatching {
            if (file.length() in 1..128) file.readText() else null
        }.getOrNull()

    private fun File.readTextOrNull(): String? = runCatching { if (isFile) readText() else null }.getOrNull()

    private fun writeAtomically(
        file: File,
        text: String,
    ) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.part")
        FileOutputStream(temporary).use {
            it.write(text.encodeToByteArray())
            it.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun deleteIfPresent(file: File) {
        check(!file.exists() || file.delete()) { "Native recovery acknowledgement could not be persisted" }
    }

    private fun hasDatabase(): Boolean = context.getDatabasePath("logdate").let { it.exists() && it.length() > 0L }

    private fun initializedFile() = File(context.noBackupFilesDir, ".logdate_device_initialized")

    private fun pendingRestoreFile() = File(context.noBackupFilesDir, ".logdate_native_restore_pending")

    private fun mediaQuarantineFile() = File(context.noBackupFilesDir, ".logdate_media_quarantine_pending")

    private fun accountRecoveryFile() = File(context.noBackupFilesDir, ".logdate_account_restore_pending")

    private fun accountAdmissionFile(generation: String) = File(context.noBackupFilesDir, ".logdate_account_admission_$generation")

    private fun accountResolvedFile() = File(context.noBackupFilesDir, ".logdate_account_restore_resolved")

    private fun restoreFingerprintFile() = File(context.noBackupFilesDir, ".logdate_native_restore_fingerprints")

    private fun restorePayloadReceivedFile() = File(context.noBackupFilesDir, ".logdate_native_restore_payload_received")

    private fun restoreDatabaseReceivedFile() = File(context.noBackupFilesDir, ".logdate_native_restore_database_received")

    private fun restorePrivacyFile() = File(context.noBackupFilesDir, ".logdate_native_restore_privacy_received")

    private companion object {
        val lock = Any()
        const val CONTENT_PRESENT = "content-present"
        const val CONTENT_MISSING = "content-missing"
        const val CONTENT_COMPLETE = "content-complete"
        const val RESTORE_IN_PROGRESS = "restore-in-progress"
        const val DELIVERY_PENDING = "delivery-pending"
        const val RESTORE_PAYLOAD_RECEIVED = "received"
        const val RESTORE_DATABASE_RECEIVED = "received"
        const val MAX_RESTORE_FINGERPRINTS = 4
    }
}

enum class PostRestoreType {
    RECOVERY_REQUIRED,
    CONTENT_RESTORED,
    CONTENT_MISSING,
    NONE,
}
