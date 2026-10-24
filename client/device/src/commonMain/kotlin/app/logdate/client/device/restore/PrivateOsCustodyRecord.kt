package app.logdate.client.device.restore

import app.logdate.client.device.crypto.ExistingIdentityKeySnapshot
import app.logdate.client.device.crypto.ExistingMediaKeySource
import app.logdate.client.device.crypto.RootlessIdentityKeyObservation
import app.logdate.client.device.identity.ExistingCanonicalOwner
import app.logdate.client.device.identity.sameOwnerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** Bounded private record encoding does not authorize system delivery or key installation. */
class PrivateOsCustodyRecord private constructor(
    private val bytes: ByteArray,
) {
    fun <T> withEncodedBytes(block: (ByteArray) -> T): T = block(bytes.copyOf())

    override fun toString(): String = "PrivateOsCustodyRecord([REDACTED])"

    companion object {
        /** Callers must supply actual framework flags and separately fence the source snapshot. */
        suspend fun prepare(
            transportFlags: Int,
            transferId: Uuid,
            sourceGeneration: ByteArray,
            owner: ExistingCanonicalOwner,
            checksumsFileSha256: String,
            manifestSha256: String,
            readExistingKeys: suspend () -> ExistingIdentityKeySnapshot?,
        ): PrivateOsCustodyRecord? {
            if (transportFlags and REQUIRED_TRANSPORT_FLAGS != REQUIRED_TRANSPORT_FLAGS) return null
            try {
                require(sourceGeneration.size == 32 && owner.rawId.length == 36)
                Uuid.parse(owner.rawId)
                require(isDigest(checksumsFileSha256) && isDigest(manifestSha256))
                val generation = sourceGeneration.copyOf()
                val keys = readExistingKeys() ?: return null
                require(sameOwnerId(keys.accountId, owner.rawId.takeIf { owner.isBound }))
                val root = keys.identityKey.copyOf()
                val media = keys.mediaKey.copyOf()
                require(root.size == 32 && media.size == 32)
                val rootDigest = root.toByteString().sha256().hex()
                val selected =
                    when (keys.mediaKeySource) {
                        ExistingMediaKeySource.CACHED -> "cached"
                        ExistingMediaKeySource.RETAINED -> "retainedLegacy"
                        ExistingMediaKeySource.DERIVED_EXISTING_ROOT -> "derivedExistingRoot"
                    }
                val document =
                    buildJsonObject {
                        put("version", 1)
                        put("purpose", "logdate-os-private-custody")
                        put("transferID", transferId.toString())
                        put("sourceGeneration", encode(generation))
                        put(
                            "owner",
                            buildJsonObject {
                                put("kind", if (owner.isBound) "cloudAccount" else "localCanonical")
                                put(if (owner.isBound) "accountIDRaw" else "canonicalOwnerIDRaw", owner.rawId)
                                put("rootState", "existingRoot")
                                put("identityKeySHA256", rootDigest)
                            },
                        )
                        put("sourceClassification", "existingRoot")
                        put(
                            "archive",
                            buildJsonObject {
                                put("checksumsFileSHA256", checksumsFileSha256)
                                put("manifestSHA256", manifestSha256)
                            },
                        )
                        put("identityKey", encode(root))
                        put("mediaKey", encode(media))
                        put("mediaSelection", selected)
                    }
                val bytes = document.toString().encodeToByteArray()
                require(bytes.size in 1..4096)
                return PrivateOsCustodyRecord(bytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                throw PrivateOsCustodyUnavailable()
            }
        }

        /** Used only after a complete archive proves it has no media originals or independent media key. */
        suspend fun prepareRootlessPlaintext(
            transportFlags: Int,
            transferId: Uuid,
            sourceGeneration: ByteArray,
            owner: ExistingCanonicalOwner,
            checksumsFileSha256: String,
            manifestSha256: String,
            observeRootless: suspend () -> RootlessIdentityKeyObservation?,
        ): PrivateOsCustodyRecord? {
            if (transportFlags and REQUIRED_TRANSPORT_FLAGS != REQUIRED_TRANSPORT_FLAGS || owner.isBound) return null
            try {
                require(sourceGeneration.size == 32 && owner.rawId.length == 36)
                Uuid.parse(owner.rawId)
                require(isDigest(checksumsFileSha256) && isDigest(manifestSha256))
                val observation = observeRootless() ?: return null
                val hasCachedMedia =
                    observation.mediaKey?.let { key ->
                        key.fill(0)
                        true
                    } ?: false
                if (hasCachedMedia || observation.hasRetainedMedia) return null
                val document =
                    buildJsonObject {
                        put("version", 1)
                        put("purpose", "logdate-os-private-custody")
                        put("transferID", transferId.toString())
                        put("sourceGeneration", encode(sourceGeneration))
                        put(
                            "owner",
                            buildJsonObject {
                                put("kind", "localCanonical")
                                put("canonicalOwnerIDRaw", owner.rawId)
                                put("rootState", "absentUnencrypted")
                            },
                        )
                        put("sourceClassification", "verifiedRootlessPlaintext")
                        put(
                            "archive",
                            buildJsonObject {
                                put("checksumsFileSHA256", checksumsFileSha256)
                                put("manifestSHA256", manifestSha256)
                            },
                        )
                    }
                val bytes = document.toString().encodeToByteArray()
                require(bytes.size in 1..4096)
                return PrivateOsCustodyRecord(bytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                throw PrivateOsCustodyUnavailable()
            }
        }

        private fun encode(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

        private fun isDigest(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        // Client-side encryption (1) and cross-platform iOS transfer (8), supplied by BackupAgent.
        private const val REQUIRED_TRANSPORT_FLAGS = 1 or 8
    }
}

internal class PrivateOsCustodyUnavailable : IllegalStateException("Native transfer encryption custody is unavailable")
