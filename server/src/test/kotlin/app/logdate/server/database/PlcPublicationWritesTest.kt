package app.logdate.server.database

import app.logdate.server.atproto.AtprotoSession
import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withOwnPostgresDatabase
import app.logdate.server.identity.PlcPublicationOutcome
import app.logdate.server.identity.PlcPublicationReview
import app.logdate.server.identity.PlcPublicationWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcOperation
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PlcPublicationWritesTest {
    @Test
    fun `stale reviews cannot send and confirmation atomically marks only the reviewed account public`() =
        verifyReview(appendPreparedHead = false)

    @Test
    fun `the reviewed transition is appended only after directory confirmation`() = verifyReview(appendPreparedHead = true)

    private fun verifyReview(appendPreparedHead: Boolean) {
        withOwnPostgresDatabase("plc_publication_writes_") { url ->
            localTestPostgresDataSource(url).use { source ->
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                val account = Uuid.random()
                val other = Uuid.random()
                val reviewId = Uuid.random()
                val did = AtprotoDid.require("did:plc:abcdefghijklmnopqrstuvwx")
                val operation =
                    PlcOperation(
                        sig = "fixture",
                        services = emptyMap(),
                        alsoKnownAs = emptyList(),
                        rotationKeys = emptyList(),
                        verificationMethods = emptyMap(),
                    )
                val operationJson = Json { encodeDefaults = true }
                val review = mockk<PlcPublicationReview>()
                every { review.did } returns did
                every { review.signingKeyMultibase } returns "signing-key"
                every { review.recoveryDidKey } returns "did:key:recovery-key"
                val preparedCid = "prepared-head"
                val reviewedOperations =
                    if (appendPreparedHead) {
                        listOf(operation, operation.copy(prev = "private-head", sig = "transition"))
                    } else {
                        listOf(operation)
                    }
                every { review.operations() } returns reviewedOperations
                every { review.operationCids } returns listOf("private-head", preparedCid)
                val handle = "reviewer.logdate.app"
                source.connection.use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO accounts(id, username, display_name, created_at, " +
                                "did, handle, signing_key_public, plc_recovery_did_key) " +
                                "VALUES ('$account', 'reviewer', 'Reviewer', now(), '$did', '$handle', " +
                                "'signing-key', 'did:key:recovery-key')",
                        )
                        statement.execute(
                            "INSERT INTO accounts(id, username, display_name, created_at) VALUES ('$other', 'other', 'Other', now())",
                        )
                    }
                    connection
                        .prepareStatement(
                            "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, created_at) " +
                                "VALUES (?::uuid, ?::uuid, ?, 'plc_operation', ?, now())",
                        ).use {
                            it.setString(1, Uuid.random().toString())
                            it.setString(2, account.toString())
                            it.setString(3, did.toString())
                            it.setString(4, operationJson.encodeToString(operation))
                            it.executeUpdate()
                        }
                }
                val writes = PlcPublicationWrites(source)
                val appPasswords = AtprotoAppPasswordStore(source)
                runTest {
                    assertNull(appPasswords.issue(account, did.toString(), "Fixture app"))
                    assertFalse(writes.saveReview(other, reviewId, handle, review, appendPreparedHead))
                    assertTrue(writes.saveReview(account, reviewId, handle, review, appendPreparedHead))
                    assertFalse(writes.saveReview(account, reviewId, handle, review, appendPreparedHead))
                    assertTrue(writes.readReview(other, reviewId) == null)
                    assertTrue(writes.readReview(account, reviewId)?.operations == reviewedOperations)
                    assertFalse(writes.markAttempt(account, reviewId, review))
                    assertFalse(writes.authorize(other, reviewId, review))
                    assertTrue(writes.authorize(account, reviewId, review))
                    assertEquals(PlcPublicationState.AUTHORIZED, writes.readActiveState(account, did.toString()))
                    val pending = writes.dueReviews(limit = 1).single()
                    assertEquals(reviewId, pending.reviewId)
                    assertEquals(account, pending.accountId)
                    assertEquals(reviewedOperations, pending.review.operations)
                    val worker = mockk<PlcPublicationWorker>()
                    coEvery { worker.advance(review, any(), any()) } returns PlcPublicationOutcome.Pending
                    assertEquals(PlcPublicationOutcome.Pending, writes.advance(account, reviewId, review, worker))
                    assertTrue(writes.dueReviews(limit = 1).isEmpty())
                    assertEquals(PlcPublicationOutcome.Pending, writes.advance(account, reviewId, review, worker))
                    coVerify(exactly = 1) { worker.advance(review, any(), any()) }
                    source.connection.use { connection ->
                        connection.autoCommit = true
                        connection.createStatement().use {
                            it.execute("UPDATE accounts SET is_active = false WHERE id = '$account'")
                        }
                    }
                    assertFalse(writes.markAttempt(account, reviewId, review))
                    source.connection.use { connection ->
                        connection.autoCommit = true
                        connection.createStatement().use {
                            it.execute("UPDATE accounts SET is_active = true WHERE id = '$account'")
                        }
                    }
                    assertTrue(writes.markAttempt(account, reviewId, review))
                    assertEquals(PlcPublicationState.UNCERTAIN, writes.readActiveState(account, did.toString()))
                    source.connection.use { connection ->
                        connection.autoCommit = true
                        connection.createStatement().use { statement ->
                            val changedHandle =
                                assertFailsWith<SQLException> {
                                    statement.executeUpdate("UPDATE accounts SET handle = 'different.logdate.app' WHERE id = '$account'")
                                }
                            assertEquals("23514", changedHandle.sqlState)
                            val removedHistory =
                                assertFailsWith<SQLException> {
                                    statement.executeUpdate("DELETE FROM hosted_plc_operations WHERE account_id = '$account'")
                                }
                            assertEquals("23514", removedHistory.sqlState)
                        }
                    }
                    assertFalse(hasPreparedHead(source, preparedCid))
                    assertFalse(writes.confirm(other, reviewId, review))
                    assertTrue(writes.confirm(account, reviewId, review))
                    assertEquals(appendPreparedHead, hasPreparedHead(source, preparedCid))
                    assertNull(appPasswords.issue(other, did.toString(), "Fixture app"))
                    val password = assertNotNull(appPasswords.issue(account, did.toString(), "Fixture app"))
                    assertNull(appPasswords.issue(account, did.toString(), "Fixture app"))
                    assertNull(appPasswords.match("other.logdate.app", password.value))
                    val match = assertNotNull(appPasswords.match(handle, password.value))
                    assertEquals(account, match.accountId)
                    assertEquals(did.toString(), match.did)
                    val session =
                        AtprotoSession(
                            id = Uuid.random().toString(),
                            accountId = account,
                            createdAtEpochMillis = 1_000L,
                            refreshExpiresAtEpochMillis = 2_000L,
                            appPasswordId = match.passwordId,
                        )
                    assertTrue(appPasswords.startSession(session, match.did))
                    assertFalse(appPasswords.revoke(other, match.passwordId))
                    assertTrue(appPasswords.revoke(account, match.passwordId))
                    assertNull(appPasswords.match(handle, password.value))
                    assertFalse(appPasswords.startSession(session.copy(id = Uuid.random().toString()), match.did))
                    source.connection.use { connection ->
                        connection.autoCommit = true
                        connection.prepareStatement("SELECT revoked_at FROM atproto_sessions WHERE id = ?").use { statement ->
                            statement.setString(1, session.id)
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                assertNotNull(rows.getTimestamp("revoked_at"))
                            }
                        }
                    }
                    assertNull(writes.readActiveState(account, did.toString()))
                    assertFalse(writes.markAttempt(account, reviewId, review))
                }
                source.connection.use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                "SELECT 1 FROM accounts WHERE id = '$account' AND public_identity_did = '$did'",
                            ).use { assertTrue(it.next()) }
                    }
                }
            }
        }
    }

    private fun hasPreparedHead(
        source: javax.sql.DataSource,
        cid: String,
    ): Boolean =
        source.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement("SELECT 1 FROM hosted_plc_operations WHERE cid = ?").use { statement ->
                statement.setString(1, cid)
                statement.executeQuery().use { it.next() }
            }
        }
}
