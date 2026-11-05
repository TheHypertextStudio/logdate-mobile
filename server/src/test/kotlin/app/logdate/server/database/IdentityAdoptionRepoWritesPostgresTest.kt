package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.identity.adoption.withAccountWriteOrigin
import app.logdate.shared.model.AccountStorageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoCommit
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.SignedRepoCommit
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionRepoWritesPostgresTest {
    @Test
    fun heldHeadCannotChange() = heldMutation { store.writeHead(head.copy(revision = 2)) }

    @Test
    fun heldHeadCannotAdvanceByCas() = heldMutation { store.compareAndSwapHead(head.copy(revision = 2), 1) }

    @Test
    fun heldRepoCannotAcquireAnotherBlock() =
        heldMutation {
            val bytes = "next".encodeToByteArray()
            store.writeBlock(repo, RepoBlock(Cid.sha256(0x71, bytes), bytes))
        }

    @Test
    fun heldRepoCannotAppendACommit() =
        heldMutation {
            store.appendCommit(repo, commit.copy(commit = commit.commit.copy(revision = 2)))
        }

    @Test
    fun heldRepoCannotBeCleared() = heldMutation { store.clearRepo(repo) }

    @Test
    fun heldOwnedTransactionCannotEnterItsBody() =
        withFixture {
            hold()
            var entered = 0
            assertEquals(
                IdentityFenceOutcome.HELD,
                assertFailsWith<IdentityWriteRefusedException> {
                    store.write(id) {
                        entered++
                        store.writeHead(head.copy(revision = 2)).getOrThrow()
                    }
                }.outcome,
            )
            assertEquals(0, entered)
            assertOriginal()
        }

    @Test
    fun aDIDCannotSubstituteForTheOriginatingAccount() =
        withFixture {
            val otherId = Uuid.random()
            assertTrue(
                PostgreSQLAccountRepository().create(
                    Account(otherId, otherId.toHexString(), "Other owner", createdAt = Clock.System.now()),
                ),
            )
            val result = withAccountWriteOrigin(otherId) { store.writeHead(head.copy(revision = 2)) }
            assertEquals(IdentityFenceOutcome.CHANGED, assertIs<IdentityWriteRefusedException>(result.exceptionOrNull()).outcome)
            assertOriginal()
        }

    @Test
    fun anUnboundMutationCannotInferAuthorityFromTheCurrentDidOwner() =
        withFixture {
            val result = store.writeHead(head.copy(revision = 2))
            assertIs<IdentityWriteRefusedException>(result.exceptionOrNull())
            assertOriginal()
        }

    @Test
    fun staleOriginEpochCannotWriteAndAnExplicitCurrentEpochCan() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val stale = withAccountWriteOrigin(owner.id) { store.writeHead(head.copy(revision = 2)) }
            assertEquals(IdentityFenceOutcome.CHANGED, assertIs<IdentityWriteRefusedException>(stale.exceptionOrNull()).outcome)
            assertOriginal()
            store.write(id, identityEpoch = 1) { store.writeHead(head.copy(revision = 2)).getOrThrow() }
            assertEquals(2L, store.readHead(repo).getOrThrow()?.revision)
        }

    @Test
    fun deletedOriginCannotRunARepositoryMutation() =
        withFixture {
            assertTrue(PostgreSQLAccountRepository().deleteAccount(owner.id))
            var entered = 0
            assertEquals(
                IdentityFenceOutcome.DELETED,
                assertFailsWith<IdentityWriteRefusedException> {
                    store.write(id) {
                        entered++
                        store.writeHead(head.copy(revision = 2)).getOrThrow()
                    }
                }.outcome,
            )
            assertEquals(0, entered)
            assertNull(store.readHead(repo).getOrThrow())
        }

    @Test
    fun nestedWriteCannotReplaceItsAccountOrigin() =
        withFixture {
            val otherId = Uuid.random()
            assertTrue(
                PostgreSQLAccountRepository().create(
                    Account(otherId, otherId.toHexString(), "Other owner", createdAt = Clock.System.now()),
                ),
            )
            assertFailsWith<IdentityWriteRefusedException> {
                store.write(id) { store.write(otherId.toJavaUUID()) { store.writeHead(head.copy(revision = 2)).getOrThrow() } }
            }
            assertOriginal()
        }

    @Test
    fun forgedBlockCannotPoisonTheWarmCacheOrStoredBytes() =
        withFixture {
            assertContentEquals(block.bytes, assertNotNull(store.readBlock(block.cid).getOrThrow()).bytes)
            val forged = store.write(id) { store.writeBlock(repo, RepoBlock(block.cid, "forged".encodeToByteArray())) }
            assertTrue(forged.isFailure)
            assertContentEquals(block.bytes, assertNotNull(store.readBlock(block.cid).getOrThrow()).bytes)
            assertContentEquals(block.bytes, assertNotNull(PostgreSQLRepoBlockStore().readBlock(block.cid).getOrThrow()).bytes)
        }

    @Test
    fun accountLockPrecedesHeadRowMutation() =
        withFixture {
            coroutineScope {
                source.connection.use { control ->
                    control.autoCommit = false
                    control.prepareStatement("SELECT repo_did FROM atproto_repo_heads WHERE repo_did = ? FOR UPDATE").use {
                        it.setString(1, repo.toString())
                        it.executeQuery().close()
                    }
                    val writer =
                        async(Dispatchers.IO) {
                            runCatching { store.write(id) { store.writeHead(head.copy(revision = 2)).getOrThrow() } }
                        }
                    try {
                        withTimeout(5_000) { while (waitingOnHead() == 0) delay(20) }
                        val failure =
                            assertFailsWith<SQLException> {
                                source.blueskyTransaction {
                                    prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT").use {
                                        it.setObject(1, id)
                                        it.executeQuery().close()
                                    }
                                }
                            }
                        assertEquals("55P03", failure.sqlState)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            control.rollback()
                            writer.await()
                        }
                    }
                    writer.await().getOrThrow()
                    assertEquals(2L, store.readHead(repo).getOrThrow()?.revision)
                }
            }
        }

    @Test
    fun anExistingCommitCannotBeReplacedByConflictingHistory() =
        withFixture {
            val changed = commit.copy(signature = "different-signature")
            val result = store.write(id) { store.appendCommit(repo, changed) }
            assertTrue(result.isFailure)
            assertOriginal()
        }

    @Test
    fun commitHeaderMustNameTheRepositoryItIsStoredUnder() =
        withFixture {
            val foreign = AtprotoDid.require("did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
            val changed = commit.copy(commit = commit.commit.copy(repo = foreign, revision = 2))
            val result = store.write(id) { store.appendCommit(repo, changed) }
            assertTrue(result.isFailure)
            assertOriginal()
        }

    @Test
    fun externalIdentityCannotAcquireHostedRepositoryAuthority() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_kind = 'external', storage_mode = ? WHERE id = ?").use {
                    it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                    it.setObject(2, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val result = withAccountWriteOrigin(owner.id) { store.writeHead(head.copy(revision = 2)) }
            assertEquals(IdentityFenceOutcome.CHANGED, assertIs<IdentityWriteRefusedException>(result.exceptionOrNull()).outcome)
            assertOriginal()
        }

    @Test
    fun retiredRepositoryCannotRegainWriteAuthority() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, " +
                        "identity_epoch, retired_at) VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, repo.toString())
                    it.setObject(2, id)
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val result = withAccountWriteOrigin(owner.id) { store.clearRepo(repo) }
            assertEquals(IdentityFenceOutcome.CHANGED, assertIs<IdentityWriteRefusedException>(result.exceptionOrNull()).outcome)
            assertOriginal()
        }

    private fun heldMutation(action: suspend Fixture.() -> Result<*>) =
        withFixture {
            hold()
            val result = withAccountWriteOrigin(owner.id) { action() }
            assertEquals(IdentityFenceOutcome.HELD, assertIs<IdentityWriteRefusedException>(result.exceptionOrNull()).outcome)
            assertOriginal()
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val repo = AtprotoDid.require(requireNotNull(owner.did))
        val store = PostgreSQLRepoBlockStore()
        val block = "seed".encodeToByteArray().let { RepoBlock(Cid.sha256(0x71, it), it) }
        val commit =
            SignedRepoCommit(
                Cid.sha256(0x71, "commit".encodeToByteArray()),
                RepoCommit(repo, block.cid, null, 1, 1, 1),
                "synthetic-signature",
            )
        val head = RepoHead(repo, block.cid, commit.cid, 1)

        suspend fun seed() =
            store.write(id) {
                store.writeBlock(repo, block).getOrThrow()
                store.appendCommit(repo, commit).getOrThrow()
                store.writeHead(head).getOrThrow()
            }

        suspend fun assertOriginal() {
            assertEquals(head, store.readHead(repo).getOrThrow())
            assertEquals(listOf(commit), store.listCommits(repo).getOrThrow())
            assertEquals(listOf(block.cid), store.listBlocks(repo).getOrThrow().map { it.cid })
            assertContentEquals(block.bytes, assertNotNull(store.readBlock(block.cid).getOrThrow()).bytes)
        }

        suspend fun hold() {
            val adoption = PostgreSQLIdentityAdoptionRepository(source)
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    adoption.begin(
                        IdentityAdoptionBinding(owner.id, repo.toString(), "did:web:target.test", "device", 0),
                        Clock.System.now(),
                    ),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now()),
            )
        }

        suspend fun waitingOnHead(): Int =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND wait_event_type = 'Lock' AND query LIKE '%atproto_repo_heads%' AND query NOT LIKE '%pg_stat_activity%'",
                ).use {
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val accountId = Uuid.random()
            val owner =
                Account(
                    accountId,
                    accountId.toHexString(),
                    "Repo owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            Fixture(source, assertNotNull(PostgreSQLAccountRepository().findById(owner.id))).apply { seed() }.block()
        }
}
