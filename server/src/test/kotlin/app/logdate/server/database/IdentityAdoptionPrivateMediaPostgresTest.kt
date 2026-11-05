@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.logdate.LogDateBlobCollisionException
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriter
import app.logdate.space.SpaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IdentityAdoptionPrivateMediaPostgresTest {
    @Test fun legacyCiphertextHasPrivateAdmissionExactReadbackAndIdempotentRetry() =
        withPrivateAdoptionMedia {
            val admitted = assertNotNull(begin())
            assertEquals(admitted, begin())
            assertEquals(upload.size, used())
            assertNotNull(write())
            assertNull(write())
            val verified = assertNotNull(verify())
            assertEquals(upload.cid, verified.cid)
            val read = ticket("read")
            assertNotNull(ingress().read(current, read, auth.actionSignature(read, current), upload.uploadId)).use {
                assertEquals(bytes.toList(), it.input.readBytes().toList())
            }
            assertNull(PostgreSQLSpaceStore().getBlob(SpaceRef.personal(current.binding.targetDid).uri, upload.cid))
            assertEquals(upload.size, used())
        }

    @Test fun ownedLegacyMediaAndRepositoryObjectsUseTheSamePrivatePath() {
        for (kind in listOf(IdentityAdoptionSourceKind.MEDIA, IdentityAdoptionSourceKind.ATPROTO_BLOB)) {
            withPrivateAdoptionMedia(kind) {
                assertNotNull(begin())
                assertNotNull(write())
                assertNotNull(verify())
                assertEquals(upload.size, used())
            }
        }
    }

    @Test fun sourceDigestAndQuotaFailuresRollBackBeforePhysicalAdmission() =
        withPrivateAdoptionMedia {
            val changed = upload.copy(sourceDigest = "a".repeat(64))
            assertEquals(
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
                assertFailsWith<IdentityAdoptionStagingException> { begin(changed) }.reason,
            )
            assertNull(row())
            limit = upload.size - 1
            assertEquals(IdentityAdoptionStagingFailure.QUOTA, assertFailsWith<IdentityAdoptionStagingException> { begin() }.reason)
            assertEquals(0L, used())
            assertNull(row())
            limit = upload.size
            assertNotNull(begin())
            assertEquals(upload.size, used())
        }

    @Test fun partialOverlongAndWrongCiphertextRemainUnverifiedAndCannotOverwrite() {
        for (mode in listOf("partial", "long", "wrong")) {
            withPrivateAdoptionMedia {
                assertNotNull(begin())
                val body =
                    when (mode) {
                        "partial" -> bytes.copyOf(bytes.size - 1)
                        "long" -> bytes + byteArrayOf(1)
                        else -> bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
                    }
                assertFailsWith<IdentityAdoptionStagingException> { write(body) }
                assertNull(verify())
                assertNull(write())
                assertEquals("STORED", assertNotNull(row()).phase)
                assertEquals(upload.size, used())
            }
        }
    }

    @Test fun sameBytesUnderAnotherProviderOwnerCannotVerifyAnAmbiguousAttempt() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            val admitted = assertNotNull(row())
            val foreign =
                storage.openBlobWriter(
                    LogDateBlobStreamRequest(
                        current.binding.accountId.toJavaUUID(),
                        LogDateBlobNamespace.SPACE,
                        admitted.blobId,
                        "application/octet-stream",
                        createOnly = true,
                        privateOwner = "B".repeat(43),
                    ),
                )
            foreign.output.write(bytes)
            foreign.commit()
            foreign.closeForCleanup()
            assertFailsWith<LogDateBlobCollisionException> { write() }
            assertEquals("WRITING", assertNotNull(row()).phase)
            assertNull(verify())
            assertEquals("WRITING", assertNotNull(row()).phase)
            assertFalse(storage.retirePrivateBlob(admitted.blobId, admitted.ownerToken))
            assertEquals(bytes.toList(), storage.getBlob(admitted.path)?.toList())
        }

    @Test fun exactProviderReadbackCanRecoverAnAmbiguousWritingAttempt() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            val uncertain =
                object : LogDateBlobStorage by storage {
                    override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter {
                        val writer = storage.openBlobWriter(request)
                        return object : LogDateBlobWriter by writer {
                            override fun commit() {
                                writer.commit()
                                error("synthetic lost provider acknowledgement")
                            }

                            override fun closeForCleanup() {
                                error("synthetic uncertain writer close")
                            }
                        }
                    }
                }
            assertFailsWith<IllegalStateException> { write(provider = uncertain) }
            assertEquals("WRITING", assertNotNull(begin()).state)
            assertNotNull(verify())
            assertEquals("VERIFIED", assertNotNull(row()).phase)
            assertNull(write())
            assertEquals(upload.size, used())
        }

    @Test fun providerReadbackHasNoTransactionAndExpiryCannotAcknowledgeIt() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            transition(IdentityAdoptionPhase.FENCED)
            val provider =
                object : LogDateBlobStorage by storage {
                    override fun openPrivateBlob(
                        storagePath: String,
                        owner: String,
                    ): LogDateBlobStream? {
                        assertFalse(RawJdbcTransactionContext.isActive())
                        auth.clock.time += 121.seconds
                        return storage.openPrivateBlob(storagePath, owner)
                    }
                }
            assertNull(verify(provider))
            assertEquals("STORED", assertNotNull(row()).phase)
            assertEquals(upload.size, used())
        }

    @Test fun preparingVerificationMustBeReadmittedToTheFinalHoldWithoutAnotherCharge() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            val preparing = assertNotNull(verify())
            transition(IdentityAdoptionPhase.FENCED)
            val held = assertNotNull(verify())
            assertTrue(held.generation > preparing.generation)
            assertEquals(preparing.admissionId, held.admissionId)
            assertEquals(upload.size, used())
            auth.source.blueskyTransaction {
                prepareStatement("SELECT COUNT(*) FROM identity_adoption_staged_blobs WHERE operation_id=?").use {
                    it.setString(1, current.id)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1L, rows.getLong(1))
                    }
                }
            }
        }

    @Test fun canceledSettledUploadCleanupResumesAnExactBarrierAndReleasesCharge() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            assertNotNull(verify())
            val path = assertNotNull(row()).path
            val cleanup = PostgreSQLIdentityAdoptionMediaCleanup(auth.source, storage, auth.clock)
            assertFalse(cleanup.cleanup(upload.uploadId))
            transition(IdentityAdoptionPhase.CANCELED)
            val failing =
                object : LogDateBlobStorage by storage {
                    override fun retirePrivateBlob(
                        blobId: String,
                        owner: String,
                    ): Boolean = error("synthetic provider unavailable")
                }
            assertFailsWith<IllegalStateException> {
                PostgreSQLIdentityAdoptionMediaCleanup(
                    auth.source,
                    failing,
                    auth.clock,
                ).cleanup(upload.uploadId)
            }
            assertEquals("DELETE_PENDING", assertNotNull(row()).phase)
            assertEquals(upload.size, used())
            assertTrue(cleanup.cleanup(upload.uploadId))
            assertNull(storage.openBlob(path))
            assertEquals("RETIRED", assertNotNull(row()).phase)
            assertEquals(0L, used())
        }

    @Test fun settledFailedAttemptsCanBeDiscardedBeforeRetryWithoutLosingTheNativeCiphertext() =
        withPrivateAdoptionMedia {
            limit = upload.size
            assertNotNull(begin())
            assertFailsWith<IdentityAdoptionStagingException> { write(bytes.copyOf(1)) }
            val discard = ticket("discard")
            val retired = assertNotNull(ingress().discard(current, discard, auth.actionSignature(discard, current), upload.uploadId))
            assertEquals("RETIRED", retired.state)
            assertEquals(0L, used())
            val retry =
                upload.copy(
                    uploadId =
                        kotlin.uuid.Uuid
                            .random()
                            .toString(),
                )
            assertNotNull(begin(retry))
            assertEquals(upload.size, used())
        }

    @Test fun verifiedUploadsCannotBeDiscardedByAnActiveCopy() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            assertNotNull(verify())
            val discard = ticket("discard")
            assertNull(ingress().discard(current, discard, auth.actionSignature(discard, current), upload.uploadId))
            assertEquals("VERIFIED", assertNotNull(row()).phase)
            assertEquals(upload.size, used())
        }

    @Test fun completeCoverageRecognizesUploadedMediaAndBindsItsSourceProvenance() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            transition(IdentityAdoptionPhase.FENCED)
            assertNotNull(verify())
            auth.mapStorageAuthority(current)
            val mapping =
                IdentityAdoptionMappingRequest(
                    upload.source,
                    upload.sourceDigest,
                    IdentityAdoptionDisposition.MEDIA,
                    emptyList(),
                    listOf(upload.cid),
                )
            val digest = prepareIdentityAdoptionMapping(mapping).bodyDigest
            val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
            assertNotNull(
                PostgreSQLIdentityAdoptionMappings(auth.source).put(
                    current,
                    ticket,
                    auth.actionSignature(ticket, current),
                    mapping,
                    Long.MAX_VALUE,
                    auth.clock.now(),
                ),
            )
            val coverage = assertNotNull(PostgreSQLIdentityAdoptionCoverage(auth.source).read(current, auth.clock.now()))
            assertEquals(1L, coverage.targetMedia)
            assertEquals(2L, coverage.mappingCount)
            assertFailsWith<IdentityAdoptionStagingException> {
                auth.source.blueskyTransaction { requireMediaUploadMapping(current, mapping.copy(sourceDigest = "a".repeat(64))) }
            }
        }

    @Test fun aFinalVerifiedOperationCannotMutateItsMediaPinsThroughAReadProof() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            transition(IdentityAdoptionPhase.FENCED)
            auth.source.blueskyTransaction {
                prepareStatement(
                    "UPDATE identity_adoption_operations SET phase='VERIFIED',revision=revision+1 WHERE id=? AND account_id=?",
                ).use {
                    it.setString(1, current.id)
                    it.setObject(2, current.binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            current = assertNotNull(auth.operations.get(current.binding.accountId, current.id))
            assertNull(verify())
            assertEquals("STORED", assertNotNull(row()).phase)
        }

    @Test fun promotionTransfersThePrivateChargeAndCleanupCannotDeleteDestinationOwnedBytes() =
        withPrivateAdoptionMedia {
            assertNotNull(begin())
            assertNotNull(write())
            val verified = assertNotNull(verify())
            val path = assertNotNull(row()).path
            auth.source.blueskyTransaction { IdentityAdoptionPromotion(this, current, verified.generation).promote() }
            assertEquals(upload.size, used())
            val target = assertNotNull(PostgreSQLSpaceStore().getBlob(SpaceRef.personal(current.binding.targetDid).uri, upload.cid))
            assertEquals(path, target.storagePath)
            assertEquals("PROMOTED", assertNotNull(row()).phase)
            assertFalse(PostgreSQLIdentityAdoptionMediaCleanup(auth.source, storage, auth.clock).cleanup(upload.uploadId))
            assertNotNull(storage.openBlob(path)).close()
        }
}
