package app.logdate.client.device.crypto

import android.content.Context
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.DeleteBytesRequest
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.io.encoding.Base64

/** The few Block Store calls the identity mirror needs, so its logic runs on the JVM in tests. */
interface BlockStoreBytes {
    suspend fun read(key: String): ByteArray?

    suspend fun write(
        key: String,
        bytes: ByteArray,
        backUpToCloud: Boolean,
    )

    suspend fun delete(key: String)

    suspend fun isEndToEndEncryptionAvailable(): Boolean
}

class PlayServicesBlockStoreBytes(
    context: Context,
) : BlockStoreBytes {
    private val client = Blockstore.getClient(context.applicationContext)

    override suspend fun read(key: String): ByteArray? {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(key)).build()
        return client
            .retrieveBytes(request)
            .await()
            .blockstoreDataMap[key]
            ?.bytes
    }

    override suspend fun write(
        key: String,
        bytes: ByteArray,
        backUpToCloud: Boolean,
    ) {
        val data =
            StoreBytesData
                .Builder()
                .setKey(key)
                .setBytes(bytes)
                .setShouldBackupToCloud(backUpToCloud)
                .build()
        client.storeBytes(data).await()
    }

    override suspend fun delete(key: String) {
        client.deleteBytes(DeleteBytesRequest.Builder().setKeys(listOf(key)).build()).await()
    }

    override suspend fun isEndToEndEncryptionAvailable(): Boolean = client.isEndToEndEncryptionAvailable().await()
}

/**
 * Mirrors the root identity record (the recovery phrase, or an account key with its paired media
 * key) into Android Block Store, used as the mirror of [LayeredIdentityKeyBackupStore].
 *
 * Block Store survives an uninstall and a new-phone restore on the same Google account, and it is
 * not subject to Auto Backup's 25 MB app-data quota. Each write asks for cloud backup only when
 * Block Store can encrypt it end to end (Android 9+ with a screen lock). A write without it keeps
 * the copy on this device, and Block Store deletes any older cloud copy on its next sync, so the
 * key never reaches Google readable.
 *
 * Media identities are not mirrored: Block Store entries are limited to 4 KB.
 */
class BlockStoreIdentityKeyBackupStore(
    private val bytes: BlockStoreBytes,
) : IdentityKeyBackupStore {
    override suspend fun hasRootBackup(): Boolean = bytes.read(KEY) != null

    override suspend fun hasBackup(): Boolean = hasRootBackup()

    override suspend fun readPhrase(): String? = readRecord()?.phrase

    override suspend fun readAccountIdentity(): AccountIdentityBackup? =
        readRecord()?.takeIf { it.accountId != null }?.let { record ->
            AccountIdentityBackup(
                accountId = checkNotNull(record.accountId),
                key = Base64.decode(checkNotNull(record.key)),
                mediaKey = record.mediaKey?.let { Base64.decode(it) },
            )
        }

    override suspend fun writePhrase(phrase: String) {
        require(phrase.isNotBlank())
        write(StoredRoot(VERSION, phrase = phrase, checksum = checksum(phrase.encodeToByteArray())))
    }

    override suspend fun writeAccountIdentity(identity: AccountIdentityBackup) {
        require(identity.key.size == 32 && identity.accountId.isNotBlank() && (identity.mediaKey == null || identity.mediaKey.size == 32))
        val material = identity.accountId.encodeToByteArray() + identity.key + (identity.mediaKey ?: byteArrayOf())
        write(
            StoredRoot(
                VERSION,
                accountId = identity.accountId,
                key = Base64.encode(identity.key),
                mediaKey = identity.mediaKey?.let { Base64.encode(it) },
                checksum = checksum(material),
            ),
        )
    }

    override suspend fun clear() = bytes.delete(KEY)

    private suspend fun write(record: StoredRoot) {
        val encoded = Json.encodeToString(record).encodeToByteArray()
        check(encoded.size <= MAX_ENTRY_BYTES) { "The identity backup mirror record is too large" }
        bytes.write(KEY, encoded, backUpToCloud = bytes.isEndToEndEncryptionAvailable())
    }

    private suspend fun readRecord(): StoredRoot? {
        val encoded = bytes.read(KEY) ?: return null
        return try {
            check(encoded.size in 1..MAX_ENTRY_BYTES) { "Invalid mirror size" }
            val record = Json.decodeFromString<StoredRoot>(encoded.decodeToString())
            check(record.version == VERSION) { "Unsupported mirror version" }
            val material =
                if (record.phrase != null) {
                    check(record.accountId == null && record.key == null && record.mediaKey == null && record.phrase.isNotBlank())
                    record.phrase.encodeToByteArray()
                } else {
                    val accountId = checkNotNull(record.accountId).also { check(it.isNotBlank()) }
                    val key = Base64.decode(checkNotNull(record.key)).also { check(it.size == 32) }
                    val media = record.mediaKey?.let { Base64.decode(it) }?.also { check(it.size == 32) }
                    accountId.encodeToByteArray() + key + (media ?: byteArrayOf())
                }
            check(MessageDigest.isEqual(Base64.decode(record.checksum), Base64.decode(checksum(material)))) { "Mirror checksum mismatch" }
            record
        } catch (_: Exception) {
            throw IllegalStateException("The identity backup mirror could not be read")
        }
    }

    private fun checksum(material: ByteArray): String = Base64.encode(MessageDigest.getInstance("SHA-256").digest(material))

    private companion object {
        const val KEY = "app.logdate.identity.root.v1"
        const val VERSION = 1
        const val MAX_ENTRY_BYTES = 4096
    }
}

@Serializable
private data class StoredRoot(
    val version: Int,
    val phrase: String? = null,
    val accountId: String? = null,
    val key: String? = null,
    val mediaKey: String? = null,
    val checksum: String,
)
