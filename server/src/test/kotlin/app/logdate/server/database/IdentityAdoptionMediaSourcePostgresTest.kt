@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.crypto.EncryptionMode
import app.logdate.server.crypto.EncryptionPolicy
import app.logdate.server.crypto.EncryptionService
import app.logdate.server.crypto.NoOpKeyring
import app.logdate.server.crypto.PayloadCodec
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionMediaSourceStream
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobWriteRequest
import studio.hypertext.atproto.repo.Cid
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class IdentityAdoptionMediaSourcePostgresTest {
    @Test fun ownedInlineLegacyBytesHaveAnExactIndependentServedCommitment() =
        withPrivateAdoptionMedia {
            val result = assertNotNull(readSource())
            assertEquals("stored", result.encoding)
            assertEquals(upload.sourceDigest, result.sourceDigest)
            assertEquals(3L, result.stream.size)
            result.stream.use { assertEquals(listOf<Byte>(1, 2, 3), it.input.readBytes().toList()) }
            assertEquals(hash(byteArrayOf(1, 2, 3)), result.sha256)
        }

    @Test fun serverEncryptedLegacySourceIsAuthenticatedBeforePlaintextReadback() =
        withPrivateAdoptionMedia {
            val encryption = EncryptionService(EncryptionPolicy(EncryptionMode.AT_REST_ONLY, true, true), PayloadCodec(NoOpKeyring))
            val plain = ByteArray(70000) { (it * 13).toByte() }
            val cipher = encryption.processMediaUpload(plain, "synthetic-user", "synthetic-media", "synthetic-content").data
            auth.source.blueskyTransaction {
                prepareStatement("UPDATE sync_media SET data=?,size_bytes=? WHERE user_id=?").use {
                    it.setBytes(1, cipher)
                    it.setLong(2, plain.size.toLong())
                    it.setObject(3, current.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            val result = assertNotNull(readSource(encryption))
            assertEquals("legacy-server-decrypted", result.encoding)
            assertEquals(plain.size.toLong(), result.stream.size)
            assertEquals(hash(plain), result.sha256)
            result.stream.use { assertEquals(plain.toList(), it.input.readBytes().toList()) }
        }

    @Test fun pathBackedMediaUsesTheExternalBytesRatherThanItsInlinePlaceholder() =
        withPrivateAdoptionMedia {
            val actual = ByteArray(70000) { it.toByte() }
            val path =
                storage.putBlob(
                    LogDateBlobWriteRequest(
                        current.binding.accountId.toJavaUUID(),
                        LogDateBlobNamespace.ATPROTO,
                        Uuid.random().toString(),
                        contentType = "application/octet-stream",
                        bytes = actual,
                    ),
                )
            auth.source.blueskyTransaction {
                prepareStatement("UPDATE sync_media SET storage_path=?,data=decode('','hex') WHERE user_id=?").use {
                    it.setString(1, path)
                    it.setObject(2, current.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            val result = assertNotNull(readSource())
            assertEquals(actual.size.toLong(), result.stream.size)
            assertEquals(hash(actual), result.sha256)
            result.stream.use { assertEquals(actual.toList(), it.input.readBytes().toList()) }
        }

    @Test fun repositoryBlobCidBindsItsExactStoredRepresentation() =
        withPrivateAdoptionMedia {
            val actual = "LDSM1-opaque-repository-source".toByteArray()
            val cid = Cid.rawSha256(actual).value
            val path =
                storage.putBlob(
                    LogDateBlobWriteRequest(
                        current.binding.accountId.toJavaUUID(),
                        LogDateBlobNamespace.ATPROTO,
                        Uuid.random().toString(),
                        contentType = "application/octet-stream",
                        bytes = actual,
                    ),
                )
            auth.source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO logdate_atproto_blobs(user_id,cid,mime_type,size_bytes,storage_path,created_at) " +
                        "VALUES (?,?,'image/jpeg',?,?,0)",
                ).use {
                    it.setObject(1, current.binding.accountId.toJavaUUID())
                    it.setString(2, cid)
                    it.setLong(3, actual.size.toLong())
                    it.setString(4, path)
                    it.executeUpdate()
                }
            }
            val result = assertNotNull(readSource(kind = IdentityAdoptionSourceKind.ATPROTO_BLOB))
            assertEquals("stored", result.encoding)
            assertEquals(hash(actual), result.sha256)
            result.stream.use { assertEquals(actual.toList(), it.input.readBytes().toList()) }
        }

    @Test fun aWrongSourceDigestCannotFetchTheOwnedBinary() =
        withPrivateAdoptionMedia {
            assertFailsWith<IdentityAdoptionStagingException> { readSource(digestOverride = "a".repeat(64)) }
            assertEquals(0L, used())
        }

    @Test fun legacyMediaTableBytesUseTheSameProtectedReadback() =
        withPrivateAdoptionMedia(IdentityAdoptionSourceKind.MEDIA) {
            val result = assertNotNull(readSource(kind = IdentityAdoptionSourceKind.MEDIA))
            assertEquals("stored", result.encoding)
            result.stream.use { assertEquals(listOf<Byte>(1, 2, 3), it.input.readBytes().toList()) }
        }

    @Test fun corruptServerCiphertextNeverProducesAPlaintextResponse() =
        withPrivateAdoptionMedia {
            val encryption = EncryptionService(EncryptionPolicy(EncryptionMode.AT_REST_ONLY, true, true), PayloadCodec(NoOpKeyring))
            val cipher =
                encryption
                    .processMediaUpload(
                        ByteArray(70000) { it.toByte() },
                        "synthetic-user",
                        "synthetic-media",
                        "synthetic-content",
                    ).data
            cipher[cipher.lastIndex] = (cipher.last().toInt() xor 1).toByte()
            auth.source.blueskyTransaction {
                prepareStatement("UPDATE sync_media SET data=? WHERE user_id=?").use {
                    it.setBytes(1, cipher)
                    it.setObject(2, current.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            assertFails { readSource(encryption) }
        }

    private suspend fun PrivateAdoptionMediaFixture.readSource(
        encryption: EncryptionService? = null,
        kind: IdentityAdoptionSourceKind = IdentityAdoptionSourceKind.SYNC_MEDIA,
        digestOverride: String? = null,
    ): IdentityAdoptionMediaSourceStream? {
        val item =
            assertNotNull(
                PostgreSQLIdentityAdoptionSourceInventory(auth.source).page(current, kind, now = auth.clock.now()),
            ).items.single()
        val reference = IdentityAdoptionSourceReference(kind, item.key)
        val digest = digestOverride ?: HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item))
        val challenge =
            assertNotNull(
                auth.actions.issue(current, IdentityAdoptionAction.READ_STAGED, mediaSourceDigest(reference, digest), auth.clock.now()),
            )
        return PostgreSQLIdentityAdoptionPrivateMedia(auth.source, storage, { limit }, auth.clock, encryption).readSource(
            current,
            challenge,
            auth.actionSignature(challenge, current),
            reference,
            digest,
        )
    }

    private fun hash(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
