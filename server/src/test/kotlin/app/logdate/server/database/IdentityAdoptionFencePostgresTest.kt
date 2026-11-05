package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceRecordWrite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.sql.DataSource
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionFencePostgresTest {
    @Test
    fun anOlderRepeatableReadSnapshotCannotHideANewHold() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            source.connection.use { old ->
                old.autoCommit = false
                old.transactionIsolation = java.sql.Connection.TRANSACTION_REPEATABLE_READ
                try {
                    old.prepareStatement("SELECT identity_epoch FROM accounts WHERE id = ?").use {
                        it.setObject(1, account.id.toJavaUUID())
                        it.executeQuery().close()
                    }
                    hold(source, account)
                    assertFailsWith<IllegalStateException> {
                        PostgreSQLAccountIdentityFence(old).lockAndCheck(IdentityWriteExpectation(account.id, 0), now)
                    }
                } finally {
                    old.rollback()
                }
            }
        }

    @Test
    fun completionCannotBorrowAnotherEpochOrReservationOwner() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val input = write(account)
            val store = PostgreSQLSpaceStore()
            val reservation = store.reserveUpload(input.accountId, 16, quota)
            val blob = SpaceBlob("bafy-bound", "application/octet-stream", 3, "synthetic/bound", "intent-bound")
            val wrongOwner =
                assertFailsWith<IdentityWriteRefusedException> {
                    store.putBlob(input.accountId, input.space, blob, quota, reservation.copy(accountId = Uuid.random().toJavaUUID()))
                }
            assertEquals(IdentityFenceOutcome.CHANGED, wrongOwner.outcome)
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, input.accountId)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val wrongEpoch =
                assertFailsWith<IdentityWriteRefusedException> {
                    store.putBlob(input.accountId, input.space, blob, quota, reservation, identityEpoch = 1)
                }
            assertEquals(IdentityFenceOutcome.CHANGED, wrongEpoch.outcome)
            assertNull(store.getBlob(input.space, blob.cid))
            assertNull(store.getBlobUploadReceipt(input.space, "intent-bound"))
            assertEquals(0L, store.storedBytes(input.accountId))
            assertEquals(16L, reserved(source, account.id))
        }

    @Test
    fun oldDeleteLeavesVerifiedRecordAndUsageUnchanged() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val input = write(account)
            val store = PostgreSQLSpaceStore()
            val record = store.put(input)
            val usage = store.storedBytes(input.accountId)
            hold(source, account, Clock.System.now())
            val failure =
                assertFailsWith<IdentityWriteRefusedException> {
                    PostgreSQLSpaceStore().delete(input.accountId, input.space, input.author, input.rkey, null)
                }
            assertEquals(IdentityFenceOutcome.HELD, failure.outcome)
            assertEquals(record, store.get(input.space, input.author, input.rkey))
            assertEquals(usage, store.storedBytes(input.accountId))
        }

    @Test
    fun transferCompletionCannotRegisterBlobOrReceiptDuringHold() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val input = write(account)
            val store = PostgreSQLSpaceStore()
            val reservation = store.reserveUpload(input.accountId, 16, quota)
            hold(source, account, Clock.System.now())
            val blob = SpaceBlob("bafy-upload", "application/octet-stream", 3, "synthetic/upload", "intent-a")
            val failure =
                assertFailsWith<IdentityWriteRefusedException> {
                    PostgreSQLSpaceStore().putBlob(input.accountId, input.space, blob, quota, reservation)
                }
            assertEquals(IdentityFenceOutcome.HELD, failure.outcome)
            assertNull(store.getBlob(input.space, blob.cid))
            assertNull(store.getBlobUploadReceipt(input.space, "intent-a"))
            assertEquals(0L, store.storedBytes(input.accountId))
            assertEquals(16L, reserved(source, account.id))
        }

    @Test
    fun holdPreventsNewQuotaReservations() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            hold(source, account, Clock.System.now())
            val failure =
                assertFailsWith<IdentityWriteRefusedException> {
                    PostgreSQLSpaceStore().reserveUpload(account.id.toJavaUUID(), 16, quota)
                }
            assertEquals(IdentityFenceOutcome.HELD, failure.outcome)
            assertEquals(0L, reserved(source, account.id))
        }

    @Test
    fun oldReservationReleaseCannotChangeHeldQuota() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val store = PostgreSQLSpaceStore()
            val reservation = store.reserveUpload(account.id.toJavaUUID(), 16, quota)
            hold(source, account, Clock.System.now())
            val failure = assertFailsWith<IdentityWriteRefusedException> { PostgreSQLSpaceStore().releaseUpload(reservation) }
            assertEquals(IdentityFenceOutcome.HELD, failure.outcome)
            assertEquals(16L, reserved(source, account.id))
        }

    @Test
    fun allUploadAndDeleteMutationsRequireAcknowledgedCurrentEpoch() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val input = write(account)
            val store = PostgreSQLSpaceStore()
            val original = store.put(input)
            val usage = store.storedBytes(input.accountId)
            val oldReservation = store.reserveUpload(input.accountId, 16, quota)
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, input.accountId)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val blob = SpaceBlob("bafy-current", "application/octet-stream", 3, "synthetic/current", "intent-b")
            val attempts =
                listOf(
                    runCatching { store.delete(input.accountId, input.space, input.author, input.rkey, null) },
                    runCatching { store.reserveUpload(input.accountId, 8, quota) },
                    runCatching { store.putBlob(input.accountId, input.space, blob, quota, oldReservation) },
                    runCatching { store.releaseUpload(oldReservation) },
                )
            assertEquals(
                listOf(
                    IdentityFenceOutcome.CHANGED,
                    IdentityFenceOutcome.CHANGED,
                    IdentityFenceOutcome.CHANGED,
                    IdentityFenceOutcome.CHANGED,
                ),
                attempts.map { (it.exceptionOrNull() as? IdentityWriteRefusedException)?.outcome },
            )
            assertEquals(original, store.get(input.space, input.author, input.rkey))
            assertNull(store.getBlob(input.space, blob.cid))
            assertNull(store.getBlobUploadReceipt(input.space, "intent-b"))
            assertEquals(usage, store.storedBytes(input.accountId))
            assertEquals(16L, reserved(source, account.id))
            val current = store.reserveUpload(input.accountId, 8, quota, identityEpoch = 1)
            assertEquals(1L, current.identityEpoch)
            assertEquals(blob, store.putBlob(input.accountId, input.space, blob, quota, current, identityEpoch = 1))
            store.delete(input.accountId, input.space, input.author, input.rkey, null, identityEpoch = 1)
            assertNull(store.get(input.space, input.author, input.rkey))
            assertEquals(3L, store.storedBytes(input.accountId))
            val temporary = store.reserveUpload(input.accountId, 8, quota, identityEpoch = 1)
            store.releaseUpload(temporary)
            assertEquals(16L, reserved(source, account.id))
        }

    @Test
    fun authorizedBeforeFenceCannotCommitAfterVerification() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val preparedBeforeHold = write(account)
            val failure =
                withTimeout(5_000) {
                    coroutineScope {
                        val authorized = CompletableDeferred<Unit>()
                        val resume = CompletableDeferred<Unit>()
                        val request =
                            async(Dispatchers.IO) {
                                authorized.complete(Unit)
                                resume.await()
                                runCatching { PostgreSQLSpaceStore().put(preparedBeforeHold) }
                            }
                        try {
                            authorized.await()
                            hold(source, account, Clock.System.now())
                            resume.complete(Unit)
                            assertIs<IdentityWriteRefusedException>(request.await().exceptionOrNull())
                        } finally {
                            resume.complete(Unit)
                        }
                    }
                }
            assertEquals(IdentityFenceOutcome.HELD, failure.outcome)
            assertNull(PostgreSQLSpaceStore().get(preparedBeforeHold.space, preparedBeforeHold.author, preparedBeforeHold.rkey))
            assertEquals(0L, PostgreSQLSpaceStore().storedBytes(account.id.toJavaUUID()))
        }

    @Test
    fun legacyRecordWriteCannotChangeBytesAfterEpochAdvance() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val oldRequest = write(account)
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, account.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val failure = assertFailsWith<IdentityWriteRefusedException> { PostgreSQLSpaceStore().put(oldRequest) }
            assertEquals(IdentityFenceOutcome.CHANGED, failure.outcome)
            assertEquals(0L, PostgreSQLSpaceStore().storedBytes(account.id.toJavaUUID()))
            val written = PostgreSQLSpaceStore().put(oldRequest.copy(identityEpoch = 1))
            assertEquals(written, PostgreSQLSpaceStore().get(oldRequest.space, oldRequest.author, oldRequest.rkey))
        }

    @Test
    fun staleOrMissingEpochCannotWriteAfterAuthorityAdvanced() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            assertEquals(IdentityFenceOutcome.ALLOWED, check(source, account.id, 0))
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, account.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(IdentityFenceOutcome.CHANGED, check(source, account.id, 0))
            assertEquals(IdentityFenceOutcome.CHANGED, check(source, account.id, -1))
            assertEquals(IdentityFenceOutcome.ALLOWED, check(source, account.id, 1))
        }

    @Test
    fun verifiedHoldCannotBeBypassedBySupplyingOperationId() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val operation = hold(source, account)
            assertEquals(IdentityFenceOutcome.HELD, check(source, account.id, 0))
            assertEquals(IdentityFenceOutcome.HELD, check(source, account.id, 0, operation.id))
            assertEquals(IdentityAdoptionPhase.VERIFIED, PostgreSQLIdentityAdoptionRepository(source).get(account.id, operation.id)?.phase)
        }

    @Test
    fun fenceExpiryInvalidatesVerificationOnceAcrossInstances() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            val operation = hold(source, account)
            val outcomes =
                coroutineScope {
                    (1..2)
                        .map {
                            async(Dispatchers.IO) { check(source, account.id, 0, at = now + 121.seconds) }
                        }.awaitAll()
                }
            assertEquals(listOf(IdentityFenceOutcome.ALLOWED, IdentityFenceOutcome.ALLOWED), outcomes)
            val stored = assertNotNull(PostgreSQLIdentityAdoptionRepository(source).get(account.id, operation.id))
            assertEquals(IdentityAdoptionPhase.PREPARING, stored.phase)
            assertEquals(operation.revision + 1, stored.revision)
            source.blueskyTransaction {
                prepareStatement("SELECT COUNT(*) FROM identity_adoption_reservations WHERE operation_id = ?").use {
                    it.setString(1, operation.id)
                    it.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }

    @Test
    fun deletedOrDeactivatedAccountCannotAcquireWriteAuthority() =
        withBlueskyOAuthDatabase { source ->
            assertEquals(IdentityFenceOutcome.DELETED, check(source, Uuid.random(), 0))
            val account = account()
            assertTrue(PostgreSQLAccountRepository().deactivateAccount(account.id))
            assertEquals(IdentityFenceOutcome.DELETED, check(source, account.id, 0))
        }

    @Test
    fun fenceRefusesAutocommitInsteadOfReleasingBeforeMutation() =
        withBlueskyOAuthDatabase { source ->
            val account = account()
            source.connection.use { connection ->
                connection.autoCommit = true
                assertFailsWith<IllegalStateException> {
                    PostgreSQLAccountIdentityFence(connection).lockAndCheck(IdentityWriteExpectation(account.id, 0), now)
                }
            }
        }

    private suspend fun reserved(
        source: DataSource,
        accountId: Uuid,
    ): Long =
        source.blueskyTransaction {
            prepareStatement("SELECT COALESCE(SUM(bytes), 0) FROM space_upload_reservations WHERE account_id = ?").use {
                it.setObject(1, accountId.toJavaUUID())
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    private fun write(account: Account): SpaceRecordWrite {
        val author = requireNotNull(account.did)
        val record =
            buildJsonObject {
                put("\$type", "app.logdate.crypto.sealed")
                put("version", 1)
                put("keyId", "abcdefghijklm")
                putJsonObject("nonce") { put("\$bytes", Base64.encode(ByteArray(12))) }
                putJsonObject("ciphertext") { put("\$bytes", Base64.encode(ByteArray(528))) }
            }
        return SpaceRecordWrite(
            account.id.toJavaUUID(),
            "at://$author/space/app.logdate.space.personal/self",
            author,
            "pending",
            record,
            emptyList(),
            null,
            false,
        )
    }

    private suspend fun account(): Account {
        val id = Uuid.random()
        val account = Account(id, id.toHexString(), "Test account", createdAt = now, did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
        assertTrue(PostgreSQLAccountRepository().create(account))
        return account
    }

    private suspend fun hold(
        source: DataSource,
        account: Account,
        at: Instant = now,
    ): IdentityAdoptionOperation {
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                    "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
            ).use {
                it.setObject(1, Uuid.random().toJavaUUID())
                it.setObject(2, account.id.toJavaUUID())
                it.setString(3, account.did)
                it.setTimestamp(4, at.timestamp())
                assertEquals(1, it.executeUpdate())
            }
        }
        val repository = PostgreSQLIdentityAdoptionRepository(source)
        val binding = IdentityAdoptionBinding(account.id, requireNotNull(account.did), "did:web:target.test", "device", 0)
        val preparing = assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(repository.begin(binding, at)).value
        val held =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                repository.transition(account.id, preparing.id, preparing.revision, IdentityAdoptionPhase.FENCED, at),
            ).value
        source.blueskyTransaction {
            prepareStatement("UPDATE identity_adoption_operations SET phase = 'VERIFIED' WHERE id = ?").use {
                it.setString(1, held.id)
                assertEquals(1, it.executeUpdate())
            }
        }
        return held.copy(phase = IdentityAdoptionPhase.VERIFIED)
    }

    private suspend fun check(
        source: DataSource,
        accountId: Uuid,
        epoch: Long,
        operationId: String? = null,
        at: Instant = now,
    ): IdentityFenceOutcome =
        source.blueskyTransaction {
            PostgreSQLAccountIdentityFence(this).lockAndCheck(IdentityWriteExpectation(accountId, epoch, operationId), at)
        }

    private companion object {
        val quota = SpaceQuota(1_000_000, 0)
        val now = Instant.parse("2026-10-29T00:00:00Z")
    }
}
