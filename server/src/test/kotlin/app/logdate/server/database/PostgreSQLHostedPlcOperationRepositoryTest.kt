package app.logdate.server.database

import app.logdate.server.database.support.withH2Database
import app.logdate.server.identity.HostedPlcOperationConflictException
import app.logdate.server.identity.StoredHostedPlcOperation
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for [PostgreSQLHostedPlcOperationRepository], verifying the persistent
 * storage of AT Protocol PLC operations in a relational database.
 *
 * This suite uses an embedded database (H2 in PostgreSQL compatibility mode)
 * to ensure that:
 * - Signed PLC operations are correctly inserted with all required metadata
 *   (CIDs, operation types, and raw JSON).
 * - The sequential history of an identity is maintained via `prev` CID linking.
 * - Operations can be efficiently queried and reconstructed for both internal
 *   account management and external AT Protocol audit logs.
 */
@OptIn(ExperimentalUuidApi::class)
class PostgreSQLHostedPlcOperationRepositoryTest {
    @Test
    fun `postgres hosted plc operation repository stores and queries history`() =
        kotlinx.coroutines.test.runTest {
            withH2Database(AccountsTable, HostedPlcOperationsTable, IdentityPublicationAttemptsTable) {
                val repository = PostgreSQLHostedPlcOperationRepository()
                val accountId = Uuid.random()
                val did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"

                transaction {
                    AccountsTable.insert {
                        it[id] = accountId.toJavaUUID()
                        it[username] = "plc-history"
                        it[displayName] = "PLC History"
                        it[createdAt] = Clock.System.now()
                        it[isActive] = true
                        it[preferences] = "{}"
                    }
                }

                val first =
                    StoredHostedPlcOperation(
                        id = Uuid.random(),
                        accountId = accountId,
                        did = did,
                        cid = "cid-1",
                        operationType = "plc_operation",
                        operationJson = """{"sig":"one"}""",
                    )
                val second =
                    StoredHostedPlcOperation(
                        id = Uuid.random(),
                        accountId = accountId,
                        did = did,
                        cid = "cid-2",
                        prevCid = "cid-1",
                        operationType = "plc_operation",
                        operationJson = """{"sig":"two"}""",
                    )

                kotlinx.coroutines.runBlocking {
                    repository.save(first)
                    repository.save(second)

                    val byAccount = repository.listByAccountId(accountId)
                    val byDid = repository.listByDid(did)

                    assertEquals(listOf(first, second).map(StoredHostedPlcOperation::id), byAccount.map(StoredHostedPlcOperation::id))
                    assertEquals(listOf("cid-1", "cid-2"), byDid.map(StoredHostedPlcOperation::cid))
                    assertEquals(listOf(null, "cid-1"), byDid.map(StoredHostedPlcOperation::prevCid))

                    val unpublished =
                        repository.save(
                            first.copy(id = Uuid.random(), did = "did:plc:unpublishedaccountxyz234", cid = null),
                        )
                    assertTrue(repository.recordPublishedCid(unpublished.id, "cid-published"))
                    assertFalse(repository.recordPublishedCid(Uuid.random(), "cid-missing"))
                    assertEquals("cid-published", repository.listByDid(unpublished.did).single().cid)
                }
            }
        }

    @Test
    fun `a second operation on the same head is refused as a conflict`() =
        kotlinx.coroutines.test.runTest {
            withH2Database(AccountsTable, HostedPlcOperationsTable, IdentityPublicationAttemptsTable) {
                val repository = PostgreSQLHostedPlcOperationRepository()
                val accountId = Uuid.random()
                val did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"
                transaction {
                    AccountsTable.insert {
                        it[id] = accountId.toJavaUUID()
                        it[username] = "plc-fork"
                        it[displayName] = "PLC Fork"
                        it[createdAt] = Clock.System.now()
                        it[isActive] = true
                        it[preferences] = "{}"
                    }
                }

                fun update(cid: String) =
                    StoredHostedPlcOperation(
                        id = Uuid.random(),
                        accountId = accountId,
                        did = did,
                        cid = cid,
                        prevCid = "cid-genesis",
                        operationType = "plc_operation",
                        operationJson = """{"sig":"$cid"}""",
                    )

                kotlinx.coroutines.runBlocking {
                    repository.save(update("cid-first"))
                    val conflict = assertFailsWith<HostedPlcOperationConflictException> { repository.save(update("cid-second")) }

                    assertEquals("cid-genesis", conflict.prevCid)
                    assertEquals(listOf("cid-first"), repository.listByDid(did).map(StoredHostedPlcOperation::cid))
                }
            }
        }
}
