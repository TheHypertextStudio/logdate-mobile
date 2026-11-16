@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.diagnostics

import app.logdate.server.crypto.AesGcmCipher
import app.logdate.server.crypto.EncryptionException
import app.logdate.server.crypto.EncryptionKeyring
import app.logdate.server.crypto.NoOpKeyring
import app.logdate.server.crypto.PayloadHeaderCodec
import app.logdate.server.crypto.sha256
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticReport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlin.uuid.Uuid

enum class DiagnosticSaveResult { CREATED, EXISTING, CONFLICT, DAILY_LIMIT }

data class StoredDiagnosticReport(
    val reportId: Uuid,
    val createdAt: Long,
    val encryptedPayload: ByteArray,
    val encryptedFingerprint: ByteArray,
)

interface DiagnosticReportStore {
    suspend fun save(
        owner: Uuid,
        report: StoredDiagnosticReport,
        matchesFingerprint: (ByteArray) -> Boolean,
    ): DiagnosticSaveResult

    suspend fun list(
        owner: Uuid,
        now: Long,
    ): List<StoredDiagnosticReport>

    suspend fun find(
        owner: Uuid,
        reportId: Uuid,
        now: Long,
    ): StoredDiagnosticReport?

    suspend fun delete(
        owner: Uuid,
        reportId: Uuid,
    ): Boolean

    suspend fun deleteAll(owner: Uuid): Int

    suspend fun purgeExpired(before: Long): Int
}

class DiagnosticReportService(
    private val store: DiagnosticReportStore,
    keyring: EncryptionKeyring,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cipher = DiagnosticReportCipher(keyring)

    suspend fun create(
        owner: Uuid,
        payload: String,
    ): DiagnosticSaveResult {
        val report = DiagnosticReportCodec.decode(payload)
        require(report.reportId[14] == '4' && report.reportId[19].lowercaseChar() in "89ab") {
            "Report reference must be random UUID version four"
        }
        val canonical = DiagnosticReportCodec.encode(report).encodeToByteArray()
        val reportId = Uuid.parse(report.reportId)
        val hash = sha256(canonical)
        val encryptedFingerprint = cipher.encrypt(owner, reportId, hash, fingerprint = true)
        return store.save(
            owner,
            StoredDiagnosticReport(reportId, now(), cipher.encrypt(owner, reportId, canonical), encryptedFingerprint),
        ) { stored ->
            val recovered = cipher.decrypt(owner, reportId, stored, fingerprint = true)
            if (recovered.size != 32) throw EncryptionException("Invalid diagnostic fingerprint")
            MessageDigest.isEqual(recovered, hash)
        }
    }

    suspend fun list(owner: Uuid): List<StoredDiagnosticReport> = store.list(owner, now())

    suspend fun read(
        owner: Uuid,
        reportId: Uuid,
    ): SyncDiagnosticReport? {
        val stored = store.find(owner, reportId, now()) ?: return null
        val plaintext = cipher.decrypt(owner, reportId, stored.encryptedPayload)
        return DiagnosticReportCodec.decode(plaintext.decodeToString())
    }

    suspend fun delete(
        owner: Uuid,
        reportId: Uuid,
    ): Boolean = store.delete(owner, reportId)

    suspend fun deleteAll(owner: Uuid): Int = store.deleteAll(owner)

    suspend fun purgeExpired(): Int = store.purgeExpired(now() - RETENTION_MS)

    companion object {
        const val RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L
        const val DAY_MS = 24 * 60 * 60 * 1_000L
        const val DAILY_LIMIT = 20
        const val RETAINED_LIMIT = 100
    }
}

/** A separate authenticated envelope prevents backup/media ciphertext from being read as diagnostics. */
private class DiagnosticReportCipher(
    private val keyring: EncryptionKeyring,
    private val cipher: AesGcmCipher = AesGcmCipher(),
) {
    init {
        require(keyring !== NoOpKeyring && keyring.getActiveKey().keyId != "noop") { "Diagnostic encryption key unavailable" }
    }

    fun encrypt(
        owner: Uuid,
        id: Uuid,
        plaintext: ByteArray,
        fingerprint: Boolean = false,
    ): ByteArray {
        val key = keyring.getActiveKey()
        val iv = cipher.generateIV()
        val ciphertext = cipher.encrypt(plaintext, key.keyBytes, iv, aad(owner, id, fingerprint))
        return PayloadHeaderCodec.encode(if (fingerprint) FINGERPRINT_PREFIX else PREFIX, key.keyId, iv, ciphertext)
    }

    fun decrypt(
        owner: Uuid,
        id: Uuid,
        payload: ByteArray,
        fingerprint: Boolean = false,
    ): ByteArray {
        val header = PayloadHeaderCodec.decode(payload, if (fingerprint) FINGERPRINT_PREFIX else PREFIX)
        val key = keyring.getKey(header.keyId) ?: throw EncryptionException("Diagnostic key unavailable")
        return cipher.decrypt(
            payload.copyOfRange(header.ciphertextOffset, payload.size),
            key.keyBytes,
            header.iv,
            aad(owner, id, fingerprint),
        )
    }

    private fun aad(
        owner: Uuid,
        id: Uuid,
        fingerprint: Boolean,
    ): ByteArray = "${if (fingerprint) "diagnostic-fingerprint" else "diagnostic-report"}:$owner:$id".encodeToByteArray()

    companion object {
        private val PREFIX = "LDSD1".encodeToByteArray()
        private val FINGERPRINT_PREFIX = "LDSF1".encodeToByteArray()
    }
}

class InMemoryDiagnosticReportStore : DiagnosticReportStore {
    private data class Upload(
        val createdAt: Long,
        val fingerprint: ByteArray?,
    )

    private val mutex = Mutex()
    private val reports = mutableMapOf<Uuid, MutableMap<Uuid, StoredDiagnosticReport>>()
    private val uploads = mutableMapOf<Uuid, MutableMap<Uuid, Upload>>()

    override suspend fun save(
        owner: Uuid,
        report: StoredDiagnosticReport,
        matchesFingerprint: (ByteArray) -> Boolean,
    ): DiagnosticSaveResult =
        mutex.withLock {
            val owned = reports.getOrPut(owner) { mutableMapOf() }
            val ledger = uploads.getOrPut(owner) { mutableMapOf() }
            val now = report.createdAt
            owned.entries.removeAll { it.value.createdAt <= now - DiagnosticReportService.RETENTION_MS }
            ledger.entries.removeAll { it.value.createdAt <= now - DiagnosticReportService.RETENTION_MS }
            ledger[report.reportId]?.let { existing ->
                return@withLock if (existing.fingerprint != null && matchesFingerprint(existing.fingerprint)) {
                    DiagnosticSaveResult.EXISTING
                } else {
                    DiagnosticSaveResult.CONFLICT
                }
            }
            if (ledger.values.count { it.createdAt > now - DiagnosticReportService.DAY_MS } >= DiagnosticReportService.DAILY_LIMIT) {
                return@withLock DiagnosticSaveResult.DAILY_LIMIT
            }
            owned[report.reportId] =
                report.copy(
                    encryptedPayload = report.encryptedPayload.copyOf(),
                    encryptedFingerprint = report.encryptedFingerprint.copyOf(),
                )
            ledger[report.reportId] = Upload(now, report.encryptedFingerprint.copyOf())
            while (owned.size > DiagnosticReportService.RETAINED_LIMIT) {
                val oldest = owned.values.minWith(compareBy<StoredDiagnosticReport> { it.createdAt }.thenBy { it.reportId.toString() })
                owned.remove(oldest.reportId)
            }
            DiagnosticSaveResult.CREATED
        }

    override suspend fun list(
        owner: Uuid,
        now: Long,
    ): List<StoredDiagnosticReport> =
        mutex.withLock {
            reports[owner]
                .orEmpty()
                .values
                .filter { it.createdAt > now - DiagnosticReportService.RETENTION_MS }
                .sortedByDescending { it.createdAt }
        }

    override suspend fun find(
        owner: Uuid,
        reportId: Uuid,
        now: Long,
    ): StoredDiagnosticReport? =
        mutex.withLock {
            reports[owner]?.get(reportId)?.takeIf { it.createdAt > now - DiagnosticReportService.RETENTION_MS }
        }

    override suspend fun delete(
        owner: Uuid,
        reportId: Uuid,
    ): Boolean =
        mutex.withLock {
            val prior = uploads[owner]?.get(reportId)
            if (prior != null) uploads[owner]?.set(reportId, prior.copy(fingerprint = null))
            val removed = reports[owner]?.remove(reportId) != null
            removed || prior?.fingerprint != null
        }

    override suspend fun deleteAll(owner: Uuid): Int =
        mutex.withLock {
            uploads[owner]?.replaceAll { _, value -> value.copy(fingerprint = null) }
            reports.remove(owner)?.size ?: 0
        }

    override suspend fun purgeExpired(before: Long): Int =
        mutex.withLock {
            var removed = 0
            reports.values.forEach { owned ->
                val beforeSize = owned.size
                owned.entries.removeAll { it.value.createdAt <= before }
                removed += beforeSize - owned.size
            }
            uploads.values.forEach { ledger -> ledger.entries.removeAll { it.value.createdAt <= before } }
            removed
        }
}
