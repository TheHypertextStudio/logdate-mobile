@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionStatusPostgresTest {
    @Test fun preparingStatusSurvivesRestartWithoutChangingTheOperation() =
        fixture {
            val observed = assertNotNull(PostgreSQLIdentityAdoptionRepository(source).status(owner.id, operation.id))
            assertEquals(operation, observed.operation)
            assertNull(observed.receipt)
            assertEquals(operation, repository.get(owner.id, operation.id))
        }

    @Test fun committedStatusContainsTheExactDurableReceipt() =
        fixture {
            recordReceipt(committed = true)
            val observed = assertNotNull(PostgreSQLIdentityAdoptionRepository(source).status(owner.id, operation.id))
            assertEquals(IdentityAdoptionPhase.COMMITTED, observed.operation.phase)
            val receipt = assertNotNull(observed.receipt)
            assertEquals(operation.id, receipt.operationId)
            assertEquals(owner.id, receipt.accountId)
            assertEquals(operation.binding.sourceDid, receipt.sourceDid)
            assertEquals(operation.binding.targetDid, receipt.targetDid)
            assertEquals(1L, receipt.identityEpoch)
            assertEquals(now, receipt.committedAt)
            assertEquals(receipt, repository.receipt(owner.id, operation.id))
        }

    @Test fun anotherAccountCannotObserveTheOperationOrItsReceipt() =
        fixture {
            recordReceipt(committed = true)
            assertNull(repository.status(Uuid.random(), operation.id))
            assertNull(repository.status(owner.id, Uuid.random().toString()))
            assertNotNull(repository.receipt(owner.id, operation.id))
        }

    @Test fun aCommittedOperationWithoutItsReceiptIsNotUsableStatus() =
        fixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE identity_adoption_operations SET phase = 'COMMITTED' WHERE id = ?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertFailsWith<IllegalStateException> { repository.status(owner.id, operation.id) }
        }

    @Test fun aReceiptWithoutACommittedOperationIsNotUsableStatus() =
        fixture {
            recordReceipt(committed = false)
            assertFailsWith<IllegalStateException> { repository.status(owner.id, operation.id) }
            assertEquals(operation, repository.get(owner.id, operation.id))
        }

    @Test fun aReceiptForAnotherTargetCannotBeReturnedAsCommittedStatus() =
        fixture {
            recordReceipt(committed = true, target = "did:web:foreign.example")
            assertFailsWith<IllegalStateException> { repository.status(owner.id, operation.id) }
        }

    @Test fun statusObservationDoesNotThawOrRenewAnExpiredHold() =
        fixture {
            val held =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    repository.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, now),
                ).value
            // This synthetic deadline is already in the past. Only a mutation fence may expire it.
            val observed = assertNotNull(repository.status(owner.id, operation.id))
            assertEquals(held, observed.operation)
            assertEquals(held, repository.get(owner.id, operation.id))
            assertNull(observed.receipt)
        }

    @Test fun concurrentCommitCannotMixAnOldOperationWithANewReceipt() =
        fixture {
            val observedSource = CommitAfterOperationRead(source) { runBlocking { recordReceipt(committed = true) } }
            val observed = assertNotNull(PostgreSQLIdentityAdoptionRepository(observedSource).status(owner.id, operation.id))
            if (observed.operation.phase == IdentityAdoptionPhase.PREPARING) {
                assertEquals(operation, observed.operation)
                assertNull(observed.receipt)
            } else {
                assertEquals(IdentityAdoptionPhase.COMMITTED, observed.operation.phase)
                assertEquals(operation.id, assertNotNull(observed.receipt).operationId)
            }
            assertEquals(IdentityAdoptionPhase.COMMITTED, repository.get(owner.id, operation.id)?.phase)
        }

    /** Commit on a second real connection after the first SELECT has captured its snapshot. */
    private class CommitAfterOperationRead(
        private val source: DataSource,
        private val afterRead: () -> Unit,
    ) : DataSource by source {
        private val committed = AtomicBoolean(false)

        override fun getConnection(): Connection {
            val connection = source.connection
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                val result = invoke(connection, method, arguments)
                val sql = arguments?.firstOrNull() as? String
                if (method.name == "prepareStatement" &&
                    sql != null &&
                    sql.trimStart().startsWith("SELECT", ignoreCase = true) &&
                    sql.contains("identity_adoption_operations", ignoreCase = true)
                ) {
                    statement(result as PreparedStatement)
                } else {
                    result
                }
            } as Connection
        }

        private fun statement(delegate: PreparedStatement): PreparedStatement =
            Proxy.newProxyInstance(
                PreparedStatement::class.java.classLoader,
                arrayOf(PreparedStatement::class.java),
            ) { _, method, arguments ->
                val result = invoke(delegate, method, arguments)
                if (method.name == "executeQuery" && committed.compareAndSet(false, true)) afterRead()
                result
            } as PreparedStatement

        private fun invoke(
            delegate: Any,
            method: Method,
            arguments: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
    }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val operation: IdentityAdoptionOperation,
    ) {
        val repository = PostgreSQLIdentityAdoptionRepository(source)

        suspend fun recordReceipt(
            committed: Boolean,
            target: String = operation.binding.targetDid,
        ) = source.blueskyTransaction {
            if (committed) {
                prepareStatement("UPDATE identity_adoption_operations SET phase = 'COMMITTED', revision = revision + 1 WHERE id = ?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            prepareStatement(
                "INSERT INTO identity_adoption_receipts(operation_id, account_id, source_did, target_did, identity_epoch, committed_at) " +
                    "VALUES (?, ?, ?, ?, 1, ?)",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, owner.id.toJavaUUID())
                it.setString(3, owner.did)
                it.setString(4, target)
                it.setTimestamp(5, now.timestamp())
                assertEquals(1, it.executeUpdate())
            }
        }
    }

    private fun fixture(action: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner = Account(id, id.toHexString(), "Synthetic status owner", did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa", createdAt = now)
            assertTrue(PostgreSQLAccountRepository().create(owner))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) " +
                        "VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, now.timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    PostgreSQLIdentityAdoptionRepository(source).begin(
                        IdentityAdoptionBinding(id, requireNotNull(owner.did), "did:web:target.example", "synthetic-device", 0),
                        now,
                    ),
                ).value
            Fixture(source, owner, operation).action()
        }

    private companion object {
        val now = Instant.parse("2026-10-29T00:00:00Z")
    }
}
