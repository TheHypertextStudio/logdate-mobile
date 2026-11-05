package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.database.support.withH2Database
import app.logdate.server.identity.adoption.withAccountWriteOrigin
import kotlinx.coroutines.runBlocking
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoCommit
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.SignedRepoCommit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Integration tests for [PostgreSQLRepoBlockStore] focused on AT Protocol repository storage.
 *
 * This test suite validates the persistence layer for:
 * - Repository heads (tracking the latest commit and revision).
 * - Content-addressed blocks (IPLD data).
 * - Repository commits and their cryptographic links.
 *
 * It also ensures that repository isolation is maintained during data cleanup operations,
 * confirming that clearing one repository does not affect others.
 */
@OptIn(ExperimentalUuidApi::class)
class PostgreSQLRepoBlockStoreTest {
    @Test
    fun `cold batch reads preserve order duplicates and retry missing blocks`() {
        withRepoDatabase(AtprotoRepoBlocksTable, AtprotoRepoBlockLinksTable) {
            val writer = PostgreSQLRepoBlockStore()
            val reader = PostgreSQLRepoBlockStore()
            val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
            val blocks =
                listOf("first", "second", "later").map { value ->
                    val bytes = value.encodeToByteArray()
                    RepoBlock(Cid.sha256(DAG_CBOR_CODEC_VALUE, bytes), bytes)
                }
            runBlocking {
                blocks.take(2).forEach { withRepoOrigin(repo) { writer.writeBlock(repo, it) }.getOrThrow() }
                val requested = listOf(blocks[1].cid, blocks[2].cid, blocks[0].cid, blocks[1].cid)
                val loaded = reader.readBlocks(requested).getOrThrow()
                assertEquals(listOf(blocks[1].cid, null, blocks[0].cid, blocks[1].cid), loaded.map { it?.cid })
                loaded[0]!!.bytes[0] = 0
                assertRepoBlockEquals(blocks[1], loaded[3]!!)
                assertRepoBlockEquals(blocks[1], reader.readBlock(blocks[1].cid).getOrThrow()!!)
                withRepoOrigin(repo) { writer.writeBlock(repo, blocks[2]) }.getOrThrow()
                assertRepoBlockEquals(blocks[2], reader.readBlocks(listOf(blocks[2].cid)).getOrThrow().single()!!)
                assertTrue(reader.readBlocks(emptyList()).getOrThrow().isEmpty())
            }
        }
    }

    @Test
    fun `compare and swap head only advances the expected revision`() {
        withRepoDatabase(AtprotoRepoHeadsTable) {
            val store = PostgreSQLRepoBlockStore()
            val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
            val firstHead =
                RepoHead(
                    repo = repo,
                    root = Cid.sha256(DAG_CBOR_CODEC_VALUE, "root-1".encodeToByteArray()),
                    commitCid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "commit-1".encodeToByteArray()),
                    revision = 1L,
                )
            val secondHead =
                RepoHead(
                    repo = repo,
                    root = Cid.sha256(DAG_CBOR_CODEC_VALUE, "root-2".encodeToByteArray()),
                    commitCid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "commit-2".encodeToByteArray()),
                    revision = 2L,
                )

            runBlocking {
                assertTrue(withRepoOrigin(repo) { store.compareAndSwapHead(firstHead, expectedRevision = null) }.getOrThrow())
                assertFalse(withRepoOrigin(repo) { store.compareAndSwapHead(secondHead, expectedRevision = null) }.getOrThrow())
                assertFalse(withRepoOrigin(repo) { store.compareAndSwapHead(secondHead, expectedRevision = 0L) }.getOrThrow())
                assertEquals(firstHead, store.readHead(repo).getOrThrow())

                assertTrue(withRepoOrigin(repo) { store.compareAndSwapHead(secondHead, expectedRevision = 1L) }.getOrThrow())
                assertEquals(secondHead, store.readHead(repo).getOrThrow())
            }
        }
    }

    @Test
    fun `repo block store persists heads blocks and commits by repo`() {
        withRepoDatabase(
            AtprotoRepoHeadsTable,
            AtprotoRepoBlocksTable,
            AtprotoRepoBlockLinksTable,
            AtprotoRepoCommitsTable,
        ) {
            val store = PostgreSQLRepoBlockStore()
            val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
            val otherRepo = AtprotoDid.require("did:plc:7wbylgv44bzg5gkq4h6w3lby")
            val rootCid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "root".encodeToByteArray())
            val commitCid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "commit".encodeToByteArray())
            val repoBlock = RepoBlock(cid = rootCid, bytes = "root".encodeToByteArray())
            val otherBlock =
                RepoBlock(
                    cid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "other".encodeToByteArray()),
                    bytes = "other".encodeToByteArray(),
                )
            val commit =
                SignedRepoCommit(
                    cid = commitCid,
                    commit =
                        RepoCommit(
                            repo = repo,
                            root = rootCid,
                            revision = 1L,
                            createdAtEpochMillis = 100L,
                            recordCount = 1,
                        ),
                    signature = "sig-1",
                )
            val head = RepoHead(repo = repo, root = rootCid, commitCid = commitCid, revision = 1L)

            runBlocking {
                withRepoOrigin(repo) { store.writeBlock(repo, repoBlock) }.getOrThrow()
                withRepoOrigin(otherRepo) { store.writeBlock(otherRepo, otherBlock) }.getOrThrow()
                withRepoOrigin(repo) { store.appendCommit(repo, commit) }.getOrThrow()
                withRepoOrigin(repo) { store.writeHead(head) }.getOrThrow()
            }

            runBlocking {
                assertEquals(head, store.readHead(repo).getOrThrow())
                val listedBlocks = store.listBlocks(repo).getOrThrow()
                assertEquals(listOf(commit), store.listCommits(repo).getOrThrow())
                val loadedOtherBlock = store.readBlock(otherBlock.cid).getOrThrow()

                assertEquals(1, listedBlocks.size)
                assertRepoBlockEquals(repoBlock, listedBlocks.single())
                assertNotNull(loadedOtherBlock)
                assertRepoBlockEquals(otherBlock, loadedOtherBlock)
            }
        }
    }

    @Test
    fun `clearing a repo removes only repo scoped rows`() {
        withRepoDatabase(
            AtprotoRepoHeadsTable,
            AtprotoRepoBlocksTable,
            AtprotoRepoBlockLinksTable,
            AtprotoRepoCommitsTable,
        ) {
            val store = PostgreSQLRepoBlockStore()
            val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
            val otherRepo = AtprotoDid.require("did:plc:7wbylgv44bzg5gkq4h6w3lby")
            val sharedBlockCid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "shared".encodeToByteArray())
            val sharedBlock = RepoBlock(cid = sharedBlockCid, bytes = "shared".encodeToByteArray())
            val repoCommitCid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "repo-commit".encodeToByteArray())
            val otherCommitCid = Cid.sha256(DAG_CBOR_CODEC_VALUE, "other-commit".encodeToByteArray())

            runBlocking {
                withRepoOrigin(repo) { store.writeBlock(repo, sharedBlock) }.getOrThrow()
                withRepoOrigin(otherRepo) { store.writeBlock(otherRepo, sharedBlock) }.getOrThrow()
                withRepoOrigin(repo) {
                    store
                        .appendCommit(
                            repo,
                            SignedRepoCommit(
                                cid = repoCommitCid,
                                commit =
                                    RepoCommit(
                                        repo = repo,
                                        root = sharedBlockCid,
                                        revision = 1L,
                                        createdAtEpochMillis = 1L,
                                        recordCount = 1,
                                    ),
                                signature = "sig-repo",
                            ),
                        )
                }.getOrThrow()
                withRepoOrigin(otherRepo) {
                    store
                        .appendCommit(
                            otherRepo,
                            SignedRepoCommit(
                                cid = otherCommitCid,
                                commit =
                                    RepoCommit(
                                        repo = otherRepo,
                                        root = sharedBlockCid,
                                        revision = 1L,
                                        createdAtEpochMillis = 2L,
                                        recordCount = 1,
                                    ),
                                signature = "sig-other",
                            ),
                        )
                }.getOrThrow()
                withRepoOrigin(repo) { store.writeHead(RepoHead(repo, sharedBlockCid, repoCommitCid, 1L)) }.getOrThrow()
                withRepoOrigin(otherRepo) { store.writeHead(RepoHead(otherRepo, sharedBlockCid, otherCommitCid, 1L)) }.getOrThrow()

                withRepoOrigin(repo) { store.clearRepo(repo) }.getOrThrow()

                assertNull(store.readHead(repo).getOrThrow())
                assertTrue(store.listBlocks(repo).getOrThrow().isEmpty())
                assertTrue(store.listCommits(repo).getOrThrow().isEmpty())
                assertNotNull(store.readBlock(sharedBlockCid).getOrThrow())
                assertEquals(RepoHead(otherRepo, sharedBlockCid, otherCommitCid, 1L), store.readHead(otherRepo).getOrThrow())
                assertEquals(1, store.listBlocks(otherRepo).getOrThrow().size)
                assertEquals(1, store.listCommits(otherRepo).getOrThrow().size)
            }
        }
    }

    private fun withRepoDatabase(
        vararg tables: org.jetbrains.exposed.v1.core.Table,
        block: () -> Unit,
    ) = withH2Database(AccountsTable, LogDateCollectionStatesTable, *tables) {
        createTestAccount(firstOwner, FIRST_DID)
        createTestAccount(secondOwner, SECOND_DID)
        block()
    }

    private suspend fun <T> withRepoOrigin(
        repo: AtprotoDid,
        action: suspend () -> T,
    ): T {
        val owner =
            when (repo.toString()) {
                FIRST_DID -> firstOwner
                SECOND_DID -> secondOwner
                else -> error("Missing explicit test owner")
            }
        return withAccountWriteOrigin(Uuid.parse(owner.toString())) { action() }
    }

    private companion object {
        const val FIRST_DID = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"
        const val SECOND_DID = "did:plc:7wbylgv44bzg5gkq4h6w3lby"
        val firstOwner: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val secondOwner: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}

private const val DAG_CBOR_CODEC_VALUE = 0x71

private fun assertRepoBlockEquals(
    expected: RepoBlock,
    actual: RepoBlock,
) {
    assertEquals(expected.cid, actual.cid)
    assertContentEquals(expected.bytes, actual.bytes)
}
