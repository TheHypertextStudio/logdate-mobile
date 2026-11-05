@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.space.SpaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class IdentityAdoptionMediaRetentionPostgresTest {
    @Test fun freshPrivateProofsRenewAttemptRetentionWithoutHoldingSourceWrites() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            assertNotNull(verify())
            auth.clock.time += 8.days
            assertNotNull(begin())
            val cleanup = PostgreSQLIdentityAdoptionMediaCleanup(auth.source, storage, auth.clock)
            assertFalse(upload.uploadId in cleanup.pending())
            assertFalse(cleanup.cleanup(upload.uploadId))
            assertEquals(IdentityAdoptionPhase.PREPARING, current.phase)
            assertEquals(upload.size, used())
            auth.clock.time += 8.days
            assertTrue(upload.uploadId in cleanup.pending())
            assertTrue(cleanup.cleanup(upload.uploadId))
            assertEquals(0L, used())
        }

    @Test fun deletionLeavesDurableOpaqueUploadCleanupWithoutResurrectingAccountUsage() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            assertNotNull(verify())
            val path = assertNotNull(row()).path
            assertTrue(PostgreSQLAccountRepository().deleteAccount(current.binding.accountId))
            val cleanup = PostgreSQLIdentityAdoptionMediaCleanup(auth.source, storage, auth.clock)
            assertTrue(upload.uploadId in cleanup.pending())
            assertTrue(cleanup.cleanup(upload.uploadId))
            assertNull(storage.openBlob(path))
            assertEquals("RETIRED", assertNotNull(row()).phase)
            assertEquals(0L, used())
        }

    @Test fun aCanonicalReferencePreventsTheRetirementFenceAndPreservesThePin() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            assertNotNull(verify())
            val path = assertNotNull(row()).path
            auth.source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO space_blobs(space_uri,cid,account_id,mime_type,size_bytes,storage_path,created_at) " +
                        "VALUES (?,?,?,'application/octet-stream',?,?,?)",
                ).use {
                    it.setString(1, SpaceRef.personal(current.binding.sourceDid).uri)
                    it.setString(2, upload.cid)
                    it.setObject(3, current.binding.accountId.toJavaUUID())
                    it.setLong(4, upload.size)
                    it.setString(5, path)
                    it.setTimestamp(6, auth.clock.now().timestamp())
                    it.executeUpdate()
                }
            }
            transition(IdentityAdoptionPhase.CANCELED)
            val provider =
                object : LogDateBlobStorage by storage {
                    override fun retirePrivateBlob(
                        blobId: String,
                        owner: String,
                    ): Boolean = error("Canonical objects must not reach provider retirement")
                }
            assertFailsWith<IdentityWriteRefusedException> {
                PostgreSQLIdentityAdoptionMediaCleanup(auth.source, provider, auth.clock).cleanup(upload.uploadId)
            }
            assertEquals("VERIFIED", assertNotNull(row()).phase)
            assertEquals(bytes.toList(), storage.getBlob(path)?.toList())
            assertEquals(upload.size, used())
        }
}
