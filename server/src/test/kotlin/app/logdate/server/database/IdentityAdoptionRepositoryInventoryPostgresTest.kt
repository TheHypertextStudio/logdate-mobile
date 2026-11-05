@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryRole
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DAG_CBOR_CODEC
import studio.hypertext.atproto.repo.DagCborCodec
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.SignedRepoCommit
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class IdentityAdoptionRepositoryInventoryPostgresTest {
    @Test fun anAuthorizedReaderGetsCurrentUnknownRecordsAndAllOwnedHistory() =
        withIdentityAdoptionAuthorization {
            val repo = seed()
            authorize()
            val view = assertNotNull(PostgreSQLIdentityAdoptionRepositoryInventory(source).read(operation, clock.now()))
            assertEquals(operation.id, view.operationId)
            assertEquals(operation.revision, view.revision)
            assertEquals(0L, view.generation)
            assertFalse(view.fenced)
            assertEquals(
                setOf("future.example.record", "studio.hypertext.logdate.content"),
                view.inventory.records
                    .map { it.collection }
                    .toSet(),
            )
            assertEquals(repo.blocks.size, view.inventory.blocks.size)
            assertTrue(view.inventory.blocks.any { IdentityAdoptionRepositoryRole.HISTORICAL_RECORD in it.roles })
        }

    @Test fun accountDeviceRevisionAndFenceAreRevalidatedForTheWholeGraph() =
        withIdentityAdoptionAuthorization {
            seed()
            val reader = PostgreSQLIdentityAdoptionRepositoryInventory(source)
            assertNull(reader.read(operation, clock.now()))
            authorize()
            assertNull(reader.read(operation.copy(binding = operation.binding.copy(deviceId = "another-device")), clock.now()))
            assertNull(reader.read(operation.copy(revision = operation.revision + 1), clock.now()))
            val held =
                (
                    operations.transition(
                        operation.binding.accountId,
                        operation.id,
                        operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
            assertNull(reader.read(operation, clock.now()))
            val view = assertNotNull(reader.read(held, clock.now()))
            assertTrue(view.fenced)
            assertEquals(held.revision, view.generation)
            clock.time += 121.seconds
            assertNull(reader.read(held, clock.now()))
        }

    @Test fun aReferencedGlobalBlockWithoutSourceOwnershipIsNotAccessible() =
        withIdentityAdoptionAuthorization {
            val repo = seed()
            authorize()
            source.blueskyTransaction {
                prepareStatement("DELETE FROM atproto_repo_block_links WHERE repo_did = ? AND cid = ?").use {
                    it.setString(1, operation.binding.sourceDid)
                    it.setString(2, repo.currentRecord)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val failure =
                assertFailsWith<IdentityAdoptionStagingException> {
                    PostgreSQLIdentityAdoptionRepositoryInventory(source).read(operation, clock.now())
                }
            assertEquals(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE, failure.reason)
        }

    @Test fun unrelatedGlobalBlocksAreExcludedButCorruptOwnedOrphansAreNeverSkipped() =
        withIdentityAdoptionAuthorization {
            val repo = seed()
            authorize()
            source.blueskyTransaction {
                prepareStatement("INSERT INTO atproto_repo_blocks(cid,bytes) VALUES ('b-unrelated',?)").use {
                    it.setBytes(1, byteArrayOf(1))
                    it.executeUpdate()
                }
            }
            val reader = PostgreSQLIdentityAdoptionRepositoryInventory(source)
            val view = assertNotNull(reader.read(operation, clock.now()))
            assertEquals(repo.blocks.size, view.inventory.blocks.size)
            source.blueskyTransaction {
                prepareStatement("UPDATE atproto_repo_blocks SET bytes = ? WHERE cid = ?").use {
                    it.setBytes(1, byteArrayOf(0))
                    it.setString(2, repo.orphan)
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertFailsWith<IdentityAdoptionStagingException> { reader.read(operation, clock.now()) }
        }

    private suspend fun IdentityAdoptionAuthorizedFixture.seed(): Seed {
        val did = AtprotoDid.require(operation.binding.sourceDid)
        val memory = InMemoryRepoBlockStore()
        val engine = DefaultRepoEngine(memory)
        val id = RepoRecordId(did, Nsid.require("studio.hypertext.logdate.content"), RecordKey.require("note"))
        engine.putRecord(id, buildJsonObject { put("preserved", "old") }).getOrThrow()
        val currentRecord = engine.putRecord(id, buildJsonObject { put("preserved", "new") }).getOrThrow().cid
        engine
            .createRecord(
                did,
                Nsid.require("future.example.record"),
                buildJsonObject {
                    put("future", true)
                },
                RecordKey.require("future"),
            ).getOrThrow()
        val orphanBody = DagCborCodec.encode(buildJsonObject { put("unindexed", "preserve") })
        val orphan = Cid.sha256(DAG_CBOR_CODEC, orphanBody)
        memory.writeBlock(did, RepoBlock(orphan, orphanBody)).getOrThrow()
        val head = memory.readHead(did).getOrThrow()!!
        val commits = memory.listCommits(did).getOrThrow()
        val blocks = memory.listBlocks(did).getOrThrow()
        source.blueskyTransaction {
            prepareStatement("INSERT INTO atproto_repo_heads(repo_did,root_cid,commit_cid,revision,updated_at) VALUES (?,?,?,?,?)").use {
                it.setString(1, did.toString())
                it.setString(2, head.root.value)
                it.setString(3, head.commitCid.value)
                it.setLong(4, head.revision)
                it.setTimestamp(5, clock.now().timestamp())
                it.executeUpdate()
            }
            for (block in blocks) {
                prepareStatement("INSERT INTO atproto_repo_blocks(cid,bytes) VALUES (?,?) ON CONFLICT DO NOTHING").use {
                    it.setString(1, block.cid.value)
                    it.setBytes(2, block.bytes)
                    it.executeUpdate()
                }
                prepareStatement("INSERT INTO atproto_repo_block_links(repo_did,cid) VALUES (?,?)").use {
                    it.setString(1, did.toString())
                    it.setString(2, block.cid.value)
                    it.executeUpdate()
                }
            }
            for (commit in commits) {
                prepareStatement(
                    "INSERT INTO atproto_repo_commits(repo_did,revision,cid,root_cid,prev_cid,created_at_epoch_millis," +
                        "record_count,signature) VALUES (?,?,?,?,?,?,?,?)",
                ).use {
                    it.setString(1, did.toString())
                    it.setLong(2, commit.commit.revision)
                    it.setString(3, commit.cid.value)
                    it.setString(4, commit.commit.root.value)
                    it.setString(5, commit.commit.prev?.value)
                    it.setLong(6, commit.commit.createdAtEpochMillis)
                    it.setInt(7, commit.commit.recordCount)
                    it.setString(8, commit.signature)
                    it.executeUpdate()
                }
            }
        }
        return Seed(head, commits, blocks, currentRecord, orphan.value)
    }

    private data class Seed(
        val head: RepoHead,
        val commits: List<SignedRepoCommit>,
        val blocks: List<RepoBlock>,
        val currentRecord: String,
        val orphan: String,
    )
}
