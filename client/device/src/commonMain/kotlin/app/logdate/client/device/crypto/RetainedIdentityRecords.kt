package app.logdate.client.device.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64

/**
 * The checksummed formats every platform's identity backup uses for an account key and for the
 * media keys, so a backup written on one platform's store reads the same way on another.
 *
 * A record that fails any check throws [IllegalStateException]: an unreadable backup must never be
 * mistaken for an absent one.
 */
internal object RetainedIdentityRecords {
    const val MAX_ACCOUNT_BYTES = 4096
    const val MAX_MEDIA_BYTES = 32768
    const val MAX_MEDIA_IDENTITIES = 64

    fun encodeAccount(identity: AccountIdentityBackup): ByteArray {
        require(
            identity.key.size == 32 &&
                identity.accountId.isNotBlank() &&
                (identity.mediaKey == null || identity.mediaKey.size == 32),
        )
        val stored =
            StoredAccountIdentity(
                if (identity.mediaKey == null) 1 else 2,
                identity.accountId,
                Base64.encode(identity.key),
                Base64.encode(checksum(identity.accountId, identity.key + (identity.mediaKey ?: byteArrayOf()))),
                identity.mediaKey?.let { Base64.encode(it) },
            )
        return Json.encodeToString(stored).encodeToByteArray()
    }

    fun decodeAccount(encoded: ByteArray): AccountIdentityBackup =
        try {
            check(encoded.size in 1..MAX_ACCOUNT_BYTES) { "Invalid account identity backup size" }
            val stored = Json.decodeFromString<StoredAccountIdentity>(encoded.decodeToString())
            check(stored.version in 1..2 && stored.accountId.isNotBlank()) { "Unsupported account identity backup" }
            val key = Base64.decode(stored.key)
            check(key.size == 32) { "Invalid account identity key length" }
            val media = stored.mediaKey?.let { Base64.decode(it) }
            check((stored.version == 1 && media == null) || (stored.version == 2 && media?.size == 32)) {
                "Invalid paired media recovery key"
            }
            check(constantTimeEquals(Base64.decode(stored.checksum), checksum(stored.accountId, key + (media ?: byteArrayOf())))) {
                "Account identity backup integrity check failed"
            }
            AccountIdentityBackup(stored.accountId, key, media)
        } catch (_: Exception) {
            throw IllegalStateException("The retained account identity backup could not be read")
        }

    fun decodeMedia(encoded: ByteArray): List<MediaIdentityBackup> =
        try {
            check(encoded.size in 1..MAX_MEDIA_BYTES) { "Invalid media recovery backup size" }
            val stored = Json.decodeFromString<StoredMediaIdentities>(encoded.decodeToString())
            check(stored.version == 1 && stored.identities.size in 1..MAX_MEDIA_IDENTITIES) { "Unsupported media recovery backup" }
            check(
                stored.identities
                    .map { it.identityBinding }
                    .distinct()
                    .size == stored.identities.size,
            ) {
                "Duplicate media recovery bindings"
            }
            stored.identities.map {
                val key = Base64.decode(it.key)
                check(Base64.decode(it.identityBinding).size == 32 && key.size == 32) { "Invalid media recovery key" }
                check(constantTimeEquals(Base64.decode(it.checksum), checksum(it.identityBinding, key))) {
                    "Media recovery backup integrity check failed"
                }
                MediaIdentityBackup(it.identityBinding, key)
            }
        } catch (_: Exception) {
            throw IllegalStateException("The retained media recovery backup could not be read")
        }

    /**
     * Returns [retained] with [identity] added, or null when it is already there. A different key
     * for the same binding, or a full set, throws instead of replacing anything.
     */
    fun addMedia(
        retained: List<MediaIdentityBackup>,
        identity: MediaIdentityBackup,
    ): List<MediaIdentityBackup>? {
        require(identity.key.size == 32 && Base64.decode(identity.identityBinding).size == 32)
        val existing = retained.firstOrNull { it.identityBinding == identity.identityBinding }
        if (existing != null) {
            check(existing.key.contentEquals(identity.key)) { "Conflicting media recovery keys" }
            return null
        }
        check(retained.size < MAX_MEDIA_IDENTITIES) { "The retained media recovery keys need attention" }
        return retained + identity
    }

    fun encodeMedia(identities: List<MediaIdentityBackup>): ByteArray {
        val stored =
            identities.map {
                StoredMediaIdentity(it.identityBinding, Base64.encode(it.key), Base64.encode(checksum(it.identityBinding, it.key)))
            }
        return Json.encodeToString(StoredMediaIdentities(1, stored)).encodeToByteArray()
    }

    private fun checksum(
        label: String,
        key: ByteArray,
    ): ByteArray =
        (label.encodeToByteArray() + key)
            .toByteString()
            .sha256()
            .toByteArray()

    private fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }
}

@Serializable
private data class StoredAccountIdentity(
    val version: Int,
    val accountId: String,
    val key: String,
    val checksum: String,
    val mediaKey: String? = null,
)

@Serializable
private data class StoredMediaIdentity(
    val identityBinding: String,
    val key: String,
    val checksum: String,
)

@Serializable
private data class StoredMediaIdentities(
    val version: Int,
    val identities: List<StoredMediaIdentity>,
)
