@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.ObjectUploadKind
import app.logdate.space.SpaceRef
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class ObjectUploadRecoveryPostgresTest {
    @Test fun settledOrphansAcrossAllNamespacesReleaseTheAdoptionBarrierAfterRestart() {
        for (kind in ObjectUploadKind.entries) {
            withIdentityAdoptionAuthorization {
                authorize()
                if (kind == ObjectUploadKind.SPACE) {
                    source.blueskyTransaction {
                        prepareStatement("UPDATE accounts SET storage_mode='personalSpaceV1' WHERE id=?").use {
                            it.setObject(1, operation.binding.accountId.toJavaUUID())
                            check(it.executeUpdate() == 1)
                        }
                    }
                }
                val uploads = PostgreSQLObjectUploads(clock = clock)
                val storage = InMemoryLogDateBlobStorage()
                val request = request(kind)
                val upload =
                    uploads.begin(
                        IdentityWriteExpectation(operation.binding.accountId, 0),
                        if (kind == ObjectUploadKind.HOSTED_REPO) AtprotoDid.require(operation.binding.sourceDid) else null,
                        storage.plannedPath(request),
                        kind,
                        if (kind == ObjectUploadKind.SPACE) SpaceRef.personal(operation.binding.sourceDid).uri else null,
                    )
                storage.putBlob(request)
                uploads.stored(upload)
                assertEquals(
                    IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING),
                    operations.transition(
                        operation.binding.accountId,
                        operation.id,
                        operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        clock.now(),
                    ),
                )
                assertTrue(uploads.pendingCleanup().isEmpty())
                assertFalse(uploads.recover(upload) { error("A fresh settled upload still owns its attachment grace") })
                clock.time += 3.minutes
                val restarted = PostgreSQLObjectUploads(clock = clock)
                assertEquals(listOf(upload.id), restarted.pendingCleanup().map { it.id })
                assertTrue(
                    restarted.recover(upload) { path ->
                        storage.deleteBlobIfExists(path)
                        true
                    },
                )
                assertNull(storage.getBlob(upload.path))
                assertTrue(restarted.pendingCleanup().isEmpty())
                assertTrue(restarted.recover(upload) { error("An already retired upload cannot delete again") })
                assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                    operations.transition(
                        operation.binding.accountId,
                        operation.id,
                        operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        clock.now(),
                    ),
                )
            }
        }
    }

    @Test fun elapsedTimeDoesNotTurnAnUncertainProviderWriteIntoCleanupAuthority() =
        withIdentityAdoptionAuthorization {
            val uploads = PostgreSQLObjectUploads(clock = clock)
            val storage = InMemoryLogDateBlobStorage()
            val request = request(ObjectUploadKind.BACKUP)
            val upload =
                uploads.begin(
                    IdentityWriteExpectation(operation.binding.accountId, 0),
                    null,
                    storage.plannedPath(request),
                    ObjectUploadKind.BACKUP,
                )
            storage.putBlob(request)
            clock.time += 30.days
            assertTrue(uploads.pendingCleanup().isEmpty())
            assertFalse(uploads.recover(upload) { error("An uncertain writer must remain fenced") })
            assertEquals(listOf<Byte>(1, 2, 3), storage.getBlob(upload.path)?.toList())
        }

    @Test fun refreshedAttachmentGraceInvalidatesAnEarlierCleanupSelection() =
        withIdentityAdoptionAuthorization {
            val uploads = PostgreSQLObjectUploads(clock = clock)
            val storage = InMemoryLogDateBlobStorage()
            val request = request(ObjectUploadKind.BACKUP)
            val upload =
                uploads.begin(
                    IdentityWriteExpectation(operation.binding.accountId, 0),
                    null,
                    storage.plannedPath(request),
                    ObjectUploadKind.BACKUP,
                )
            storage.putBlob(request)
            uploads.stored(upload)
            clock.time += 3.minutes
            val selected = uploads.pendingCleanup().single()
            uploads.attach(upload, { "another canonical path" }, { false })
            assertFalse(uploads.recover(selected) { error("Stale enumeration cannot retire a refreshed candidate") })
            clock.time += 3.minutes
            assertTrue(
                uploads.recover(selected) { path ->
                    storage.deleteBlobIfExists(path)
                    true
                },
            )
        }

    @Test fun attachedObjectsAreNeverCleanupCandidates() =
        withIdentityAdoptionAuthorization {
            val uploads = PostgreSQLObjectUploads(clock = clock)
            val storage = InMemoryLogDateBlobStorage()
            val request = request(ObjectUploadKind.BACKUP)
            val upload =
                uploads.begin(
                    IdentityWriteExpectation(operation.binding.accountId, 0),
                    null,
                    storage.plannedPath(request),
                    ObjectUploadKind.BACKUP,
                )
            storage.putBlob(request)
            uploads.stored(upload)
            uploads.attach(upload, { upload.path })
            clock.time += 30.days
            assertTrue(uploads.pendingCleanup().isEmpty())
            assertFalse(uploads.recover(upload) { error("An attached candidate must be retained") })
            assertEquals(listOf<Byte>(1, 2, 3), storage.getBlob(upload.path)?.toList())
        }

    @Test fun aFailedProviderDeletionRemainsRetryableAcrossRestart() =
        withIdentityAdoptionAuthorization {
            val uploads = PostgreSQLObjectUploads(clock = clock)
            val storage = InMemoryLogDateBlobStorage()
            val request = request(ObjectUploadKind.BACKUP)
            val upload =
                uploads.begin(
                    IdentityWriteExpectation(operation.binding.accountId, 0),
                    null,
                    storage.plannedPath(request),
                    ObjectUploadKind.BACKUP,
                )
            storage.putBlob(request)
            uploads.stored(upload)
            clock.time += 3.minutes
            assertFalse(uploads.recover(upload) { false })
            val restarted = PostgreSQLObjectUploads(clock = clock)
            assertEquals(upload, restarted.pendingCleanup().single())
            assertTrue(
                restarted.recover(upload) { path ->
                    storage.deleteBlobIfExists(path)
                    true
                },
            )
            assertNull(storage.getBlob(upload.path))
            assertTrue(restarted.pendingCleanup().isEmpty())
        }

    @Test fun anExistingCanonicalReferencePreventsRecoveryEvenForASettledTicket() =
        withIdentityAdoptionAuthorization {
            val uploads = PostgreSQLObjectUploads(clock = clock)
            val storage = InMemoryLogDateBlobStorage()
            val request = request(ObjectUploadKind.BACKUP)
            val owner = operation.binding.accountId
            val upload = uploads.begin(IdentityWriteExpectation(owner, 0), null, storage.plannedPath(request), ObjectUploadKind.BACKUP)
            storage.putBlob(request)
            uploads.stored(upload)
            PostgreSQLLogDateBackupRepository().createBackup(
                owner.toJavaUUID(),
                LogDateBackup(Uuid.random().toJavaUUID(), owner.toJavaUUID(), "synthetic", "{}", upload.path, 1, 3),
                0,
            )
            clock.time += 3.minutes
            assertFailsWith<IdentityWriteRefusedException> {
                uploads.recover(upload) { error("Canonical bytes must never reach provider deletion") }
            }
            assertEquals(listOf<Byte>(1, 2, 3), storage.getBlob(upload.path)?.toList())
        }

    private fun IdentityAdoptionAuthorizedFixture.request(kind: ObjectUploadKind) =
        LogDateBlobWriteRequest(
            operation.binding.accountId.toJavaUUID(),
            when (kind) {
                ObjectUploadKind.MEDIA -> LogDateBlobNamespace.MEDIA
                ObjectUploadKind.BACKUP -> LogDateBlobNamespace.BACKUP
                ObjectUploadKind.SPACE -> LogDateBlobNamespace.SPACE
                ObjectUploadKind.HOSTED_REPO -> LogDateBlobNamespace.ATPROTO
            },
            Uuid.random().toString(),
            "synthetic",
            "application/octet-stream",
            byteArrayOf(1, 2, 3),
        )
}
