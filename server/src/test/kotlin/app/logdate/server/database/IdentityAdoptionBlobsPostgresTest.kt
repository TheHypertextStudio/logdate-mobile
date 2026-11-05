@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionBlobAdmission
import app.logdate.server.identity.adoption.IdentityAdoptionMediaService
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.space.SpaceBlob
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DagCborCodec
import java.io.FilterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionBlobsPostgresTest {
    @Test fun verifiedSourceBytesStayPrivateWithoutASecondStorageCharge() =
        fixture {
            val admission = assertNotNull(admit())
            assertEquals(blob.storagePath, admission.storagePath)
            assertEquals(held.id, admission.operationId)
            assertEquals(held.revision, admission.generation)
            assertEquals(admission, admit())
            val proof = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, storage))
            assertTrue(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(held, proof, auth.clock.now()))
            assertTrue(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(held, proof, auth.clock.now()))
            assertEquals(admission, admit())
            assertEquals(1L, count())
            assertEquals(1L, count(verified = true))
            assertEquals(blob.size, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
            assertEquals(blob, spaces.getBlob(sourceSpace, blob.cid))
            assertNull(spaces.getBlob(SpaceRef.personal(held.binding.targetDid).uri, blob.cid))
        }

    @Test fun aChangedBodyOrDeviceCannotAdmitMedia() =
        fixture {
            val ticket = ticket()
            val store = PostgreSQLIdentityAdoptionBlobs(auth.source)
            assertNull(store.admit(held, ticket, auth.actionSignature(ticket, held), Cid.rawSha256(byteArrayOf(0)).value, auth.clock.now()))
            val foreign = held.copy(binding = held.binding.copy(deviceId = "other-device"))
            assertNull(store.admit(foreign, ticket, auth.actionSignature(ticket, foreign), blob.cid, auth.clock.now()))
            assertEquals(0L, auth.countConsumed())
            assertNotNull(store.admit(held, ticket, auth.actionSignature(ticket, held), blob.cid, auth.clock.now()))
            assertEquals(1L, auth.countConsumed())
        }

    @Test fun providerReadHoldsNoSqlTransactionAndCanSurviveSameGenerationRenewal() =
        fixture {
            val admission = assertNotNull(admit())
            val original = held
            val guarded =
                object : LogDateBlobStorage by storage {
                    override fun openBlob(storagePath: String): LogDateBlobStream? {
                        assertFalse(RawJdbcTransactionContext.isActive())
                        runBlocking {
                            auth.source.blueskyTransaction {
                                createStatement().use { statement ->
                                    statement
                                        .executeQuery(
                                            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                                                "AND state = 'idle in transaction'",
                                        ).use { rows ->
                                            assertTrue(rows.next())
                                            assertEquals(0L, rows.getLong(1))
                                        }
                                }
                            }
                            held = transition(IdentityAdoptionPhase.FENCED)
                        }
                        return storage.openBlob(storagePath)
                    }
                }
            val proof = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, guarded))
            assertTrue(held.revision > original.revision)
            assertTrue(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(original, proof, auth.clock.now()))
            assertEquals(1L, count(verified = true))
        }

    @Test fun anExpiredReadCannotBecomeVerified() =
        fixture {
            val admission = assertNotNull(admit())
            val proof = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, storage))
            auth.clock.time += 121.seconds
            assertFalse(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(held, proof, auth.clock.now()))
            assertEquals(0L, count(verified = true))
            assertEquals(1L, count())
        }

    @Test fun aNewHoldCannotAcceptReadbackFromAnEarlierGeneration() =
        fixture {
            val admission = assertNotNull(admit())
            val proof = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, storage))
            auth.clock.time += 121.seconds
            assertNull(auth.actions.issue(held, IdentityAdoptionAction.BEGIN_BLOB, digest(blob.cid), auth.clock.now()))
            held = assertNotNull(auth.operations.get(held.binding.accountId, held.id))
            held = transition(IdentityAdoptionPhase.FENCED)
            assertFalse(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(held, proof, auth.clock.now()))
            assertEquals(0L, count(verified = true))
            val fresh = assertNotNull(admit())
            assertTrue(fresh.generation > admission.generation)
            assertTrue(fresh.id != admission.id)
            assertEquals(2L, count())
        }

    @Test fun canceledReadAdmissionsKeepTheOriginalObjectOutOfPurge() =
        fixture {
            assertNotNull(admit())
            held = transition(IdentityAdoptionPhase.CANCELED)
            var calls = 0
            val result = spaces.purge(Clock.System.now() + 1.days, Clock.System.now() + 1.days) { calls++ }
            assertEquals(0, calls)
            assertEquals(0, result.blobsDeleted)
            assertEquals(blob, spaces.getBlob(sourceSpace, blob.cid))
            assertEquals(1L, count())
        }

    @Test fun accountDeletionRemovesPrivateAdmissionsWithoutResurrectingUsage() =
        fixture {
            val admission = assertNotNull(admit())
            val proof = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, storage))
            PostgreSQLAccountRepository().deleteAccount(held.binding.accountId)
            assertFalse(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(held, proof, auth.clock.now()))
            assertEquals(0L, count())
            assertEquals(0L, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun theServiceReturnsVerificationOnlyAfterRealReadbackAndSqlAcknowledgement() =
        fixture {
            val ticket = ticket()
            val service = IdentityAdoptionMediaService(PostgreSQLIdentityAdoptionBlobs(auth.source), storage, auth.clock)
            val result = assertNotNull(service.verify(held, ticket, auth.actionSignature(ticket, held), blob.cid))
            assertEquals(held.id, result.operationId)
            assertEquals(held.revision, result.generation)
            assertEquals(blob.cid, result.cid)
            assertEquals(blob.size, result.size)
            assertEquals(blob.mimeType, result.mimeType)
            assertEquals(1L, count(verified = true))
            assertNull(spaces.getBlob(SpaceRef.personal(held.binding.targetDid).uri, blob.cid))
        }

    @Test fun theServiceNeverReadsProviderBytesForAnInvalidDeviceSignature() =
        fixture {
            val ticket = ticket()
            var opens = 0
            val guarded =
                object : LogDateBlobStorage by storage {
                    override fun openBlob(storagePath: String): LogDateBlobStream? {
                        opens++
                        return storage.openBlob(storagePath)
                    }
                }
            val service = IdentityAdoptionMediaService(PostgreSQLIdentityAdoptionBlobs(auth.source), guarded, auth.clock)
            assertNull(service.verify(held, ticket, "invalid-device-signature", blob.cid))
            assertEquals(0, opens)
            assertEquals(0L, count())
            assertEquals(0L, auth.countConsumed())
        }

    @Test fun theServiceUsesCompletionTimeWhenTheHoldExpiresDuringReadback() =
        fixture {
            val ticket = ticket()
            var opens = 0
            val guarded =
                object : LogDateBlobStorage by storage {
                    override fun openBlob(storagePath: String): LogDateBlobStream? {
                        opens++
                        auth.clock.time += 121.seconds
                        return storage.openBlob(storagePath)
                    }
                }
            val service = IdentityAdoptionMediaService(PostgreSQLIdentityAdoptionBlobs(auth.source), guarded, auth.clock)
            assertNull(service.verify(held, ticket, auth.actionSignature(ticket, held), blob.cid))
            assertEquals(1, opens)
            assertEquals(1L, count())
            assertEquals(0L, count(verified = true))
        }

    @Test fun theServiceClosesTheProviderStreamBeforeCapturingItsAcknowledgementTime() =
        fixture {
            val ticket = ticket()
            var closed = false
            var times = 0
            val guarded =
                object : LogDateBlobStorage by storage {
                    override fun openBlob(storagePath: String): LogDateBlobStream? {
                        val stream = storage.openBlob(storagePath) ?: return null
                        return LogDateBlobStream(
                            stream.size,
                            object : FilterInputStream(stream.input) {
                                override fun close() {
                                    super.close()
                                    closed = true
                                }
                            },
                        )
                    }
                }
            val clock =
                object : Clock {
                    override fun now(): Instant {
                        if (++times > 1) assertTrue(closed)
                        return auth.clock.now()
                    }
                }
            val service = IdentityAdoptionMediaService(PostgreSQLIdentityAdoptionBlobs(auth.source), guarded, clock)
            assertNotNull(service.verify(held, ticket, auth.actionSignature(ticket, held), blob.cid))
            assertTrue(closed)
            assertEquals(2, times)
        }

    @Test fun serviceCancellationRetainsTheUnverifiedAdmissionForSafeRecovery() =
        fixture {
            val ticket = ticket()
            var closed = false
            val guarded =
                object : LogDateBlobStorage by storage {
                    override fun openBlob(storagePath: String): LogDateBlobStream? {
                        val stream = storage.openBlob(storagePath) ?: return null
                        return LogDateBlobStream(
                            stream.size,
                            object : FilterInputStream(stream.input) {
                                override fun read(
                                    buffer: ByteArray,
                                    offset: Int,
                                    length: Int,
                                ): Int = throw CancellationException("synthetic interruption")

                                override fun close() {
                                    super.close()
                                    closed = true
                                }
                            },
                        )
                    }
                }
            val service = IdentityAdoptionMediaService(PostgreSQLIdentityAdoptionBlobs(auth.source), guarded, auth.clock)
            kotlin.test.assertFailsWith<CancellationException> {
                service.verify(held, ticket, auth.actionSignature(ticket, held), blob.cid)
            }
            assertTrue(closed)
            assertEquals(1L, count())
            assertEquals(0L, count(verified = true))
        }

    @Test fun preparingMediaReadbackCompletesBeforeTheSourceWriteHold() =
        fixture(preparing = true) {
            val admission = assertNotNull(admit())
            assertEquals(IdentityAdoptionPhase.PREPARING, held.phase)
            assertEquals(held.revision, admission.generation)
            val proof = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, storage))
            assertTrue(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(held, proof, auth.clock.now()))
            assertEquals(1L, count(verified = true))
            assertEquals(blob, spaces.getBlob(sourceSpace, blob.cid))
            assertNull(spaces.getBlob(SpaceRef.personal(held.binding.targetDid).uri, blob.cid))
        }

    @Test fun onlyVerifiedPreparingMediaCanBeReusedUnderAFreshHold() =
        fixture(preparing = true) {
            val admission = assertNotNull(admit())
            val proof = assertNotNull(IdentityAdoptionVerifiedBlob.read(admission, storage))
            assertTrue(PostgreSQLIdentityAdoptionBlobs(auth.source).complete(held, proof, auth.clock.now()))
            val originalTimes = verificationTimes()
            auth.clock.time += 5.seconds
            held = transition(IdentityAdoptionPhase.FENCED)
            val reused = assertNotNull(reuse(admission))
            assertEquals(held.revision, reused.generation)
            assertEquals(admission.storagePath, reused.storagePath)
            assertEquals(admission.cid, reused.cid)
            assertEquals(reused, reuse(admission))
            assertEquals(1L, count())
            assertEquals(1L, count(verified = true))
            assertEquals(originalTimes, verificationTimes())
            assertEquals(blob.size, spaces.storedBytes(held.binding.accountId.toJavaUUID()))
        }

    @Test fun anUnverifiedPreparingMediaPinCannotBecomeFinalVerification() =
        fixture(preparing = true) {
            val admission = assertNotNull(admit())
            held = transition(IdentityAdoptionPhase.FENCED)
            val consumed = auth.countConsumed()
            val failure = assertFailsWith<IdentityAdoptionStagingException> { reuse(admission) }
            assertEquals(IdentityAdoptionStagingFailure.SOURCE_CHANGED, failure.reason)
            assertEquals(consumed, auth.countConsumed())
            assertEquals(1L, count())
            assertEquals(0L, count(verified = true))
        }

    private fun fixture(
        preparing: Boolean = false,
        block: suspend Setup.() -> Unit,
    ) = withIdentityAdoptionAuthorization {
        source.blueskyTransaction {
            prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
                it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                check(it.executeUpdate() == 1)
            }
        }
        val setup = Setup(this)
        setup.spaces.putBlob(operation.binding.accountId.toJavaUUID(), setup.sourceSpace, setup.blob)
        authorize()
        if (!preparing) setup.held = setup.transition(IdentityAdoptionPhase.FENCED)
        setup.block()
    }

    private class Setup(
        val auth: IdentityAdoptionAuthorizedFixture,
    ) {
        var held = auth.operation
        val storage = InMemoryLogDateBlobStorage()
        val spaces = PostgreSQLSpaceStore()
        val sourceSpace = SpaceRef.personal(held.binding.sourceDid).uri
        val ciphertext = ByteArray(65_537) { (it * 13).toByte() }
        val blob =
            SpaceBlob(
                Cid.rawSha256(ciphertext).value,
                "application/octet-stream",
                ciphertext.size.toLong(),
                storage.putBlob(
                    LogDateBlobWriteRequest(
                        held.binding.accountId.toJavaUUID(),
                        LogDateBlobNamespace.SPACE,
                        Uuid.random().toString(),
                        contentType = "application/octet-stream",
                        bytes = ciphertext,
                    ),
                ),
            )

        suspend fun ticket(): IdentityAdoptionActionChallenge =
            assertNotNull(
                auth.actions.issue(held, IdentityAdoptionAction.BEGIN_BLOB, digest(blob.cid), auth.clock.now()),
            )

        suspend fun admit(): IdentityAdoptionBlobAdmission? {
            val ticket = ticket()
            return PostgreSQLIdentityAdoptionBlobs(auth.source).admit(
                held,
                ticket,
                auth.actionSignature(ticket, held),
                blob.cid,
                auth.clock.now(),
            )
        }

        suspend fun reuse(admission: IdentityAdoptionBlobAdmission): IdentityAdoptionBlobAdmission? {
            val hash =
                stagingHash(
                    DagCborCodec.encode(
                        buildJsonObject {
                            put("version", 1)
                            put("sourceGeneration", admission.generation)
                            put("cid", admission.cid)
                        },
                    ),
                )
            val ticket = assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.BEGIN_BLOB, hash, auth.clock.now()))
            return PostgreSQLIdentityAdoptionBlobs(auth.source).reuse(
                held,
                ticket,
                auth.actionSignature(ticket, held),
                admission.generation,
                admission.cid,
                auth.clock.now(),
            )
        }

        suspend fun verificationTimes(): List<Instant> =
            auth.source.blueskyTransaction {
                createStatement().use { statement ->
                    statement.executeQuery("SELECT created_at, verified_at FROM identity_adoption_staged_blobs").use { rows ->
                        check(rows.next())
                        listOf(rows.getTimestamp(1).connectionInstant(), rows.getTimestamp(2).connectionInstant())
                    }
                }
            }

        suspend fun transition(phase: IdentityAdoptionPhase): IdentityAdoptionOperation =
            (
                auth.operations.transition(held.binding.accountId, held.id, held.revision, phase, auth.clock.now())
                    as IdentityAdoptionOutcome.Accepted
            ).value

        suspend fun count(verified: Boolean = false): Long =
            auth.source.blueskyTransaction {
                createStatement().use { statement ->
                    val predicate = if (verified) " WHERE verified_at IS NOT NULL" else ""
                    statement.executeQuery("SELECT count(*) FROM identity_adoption_staged_blobs" + predicate).use { rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
            }
    }

    companion object {
        private fun digest(cid: String): String =
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("cid", cid)
                    },
                ),
            )
    }
}
