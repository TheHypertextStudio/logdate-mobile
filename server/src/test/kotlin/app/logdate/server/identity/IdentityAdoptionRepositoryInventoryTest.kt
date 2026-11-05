package app.logdate.server.identity

import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryAuditor
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryRole
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DAG_CBOR_CODEC
import studio.hypertext.atproto.repo.DagCborCodec
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoCommit
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.SignedRepoCommit
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentityAdoptionRepositoryInventoryTest {
    @Test fun currentUnknownCollectionsHistoryAndUnreferencedBytesAreAllAccountedFor() =
        runBlocking {
            val f = fixture()
            val result = f.inventory()
            assertEquals(listOf("future.example.record", "studio.hypertext.logdate.content"), result.records.map { it.collection })
            assertEquals(f.blocks.keys, result.blocks.map { it.cid }.toSet())
            val byCid = result.blocks.associate { it.cid to it.roles }
            assertTrue(IdentityAdoptionRepositoryRole.HISTORICAL_RECORD in byCid.getValue(f.oldRecord))
            assertTrue(IdentityAdoptionRepositoryRole.CURRENT_RECORD in byCid.getValue(f.newRecord))
            assertEquals(setOf(IdentityAdoptionRepositoryRole.UNREFERENCED), byCid.getValue(f.unreferenced))
            assertEquals(setOf(IdentityAdoptionRepositoryRole.UNREFERENCED, IdentityAdoptionRepositoryRole.RAW), byCid.getValue(f.raw))
            assertTrue(result.blocks.any { IdentityAdoptionRepositoryRole.CURRENT_NODE in it.roles })
            assertTrue(result.blocks.any { IdentityAdoptionRepositoryRole.HISTORICAL_COMMIT in it.roles })
        }

    @Test fun emptyAndHeadlessHistoryAreExplicitRatherThanDroppingOwnedBlocks() =
        runBlocking {
            val f = fixture()
            val history = f.inventory(head = null)
            assertTrue(history.records.isEmpty())
            assertEquals(f.blocks.size, history.blocks.size)
            assertTrue(history.blocks.any { IdentityAdoptionRepositoryRole.HISTORICAL_RECORD in it.roles })
            val empty = IdentityAdoptionRepositoryAuditor.inventory(DID, null, emptyList(), emptyList()) { error("No block to load") }
            assertTrue(empty.records.isEmpty() && empty.blocks.isEmpty())
        }

    @Test fun anUnindexedCurrentRecordIsStillInTheLogicalInventory() =
        runBlocking {
            val f = fixture()
            val result = f.inventory()
            assertTrue(result.records.any { it.collection == "future.example.record" && it.rkey == "unknown" })
        }

    @Test fun corruptUnreferencedBlocksCannotBeIgnoredAsHistory() =
        runBlocking {
            val f = fixture()
            f.blocks[f.unreferenced] = byteArrayOf(1, 2, 3)
            refused { f.inventory() }
        }

    @Test fun missingAndForeignOwnedReferencesCannotBeReadFromTheGlobalBlockPool() =
        runBlocking {
            val f = fixture()
            val owned = f.blocks.keys - f.newRecord
            refused { f.inventory(cids = owned.toList()) }
            f.blocks.remove(f.head.root.toString())
            refused { f.inventory() }
        }

    @Test fun matchingCidWithNoncanonicalOrTrailingCborStillRefusesTheCensus() =
        runBlocking {
            val f = fixture()
            val bytes = DagCborCodec.encode(buildJsonObject { put("preserved", true) }) + byteArrayOf(0)
            val cid = Cid.sha256(DAG_CBOR_CODEC, bytes).value
            f.blocks[cid] = bytes
            refused { f.inventory() }
        }

    @Test fun headMetadataMustMatchItsOwnedCommitAndSourceDid() =
        runBlocking {
            val f = fixture()
            refused { f.inventory(head = f.head.copy(revision = f.head.revision + 1)) }
            refused { f.inventory(head = f.head.copy(root = Cid.require(f.newRecord))) }
            refused {
                IdentityAdoptionRepositoryAuditor.inventory(
                    "did:web:other.example",
                    f.head,
                    f.commits,
                    f.blocks.keys.toList(),
                    f.blocks::get,
                )
            }
            refused { f.inventory(commits = f.commits.filterNot { it.cid == f.head.commitCid }) }
        }

    @Test fun everyCommitMetadataRowAndItsRecordCountAreChecked() =
        runBlocking {
            val f = fixture()
            val first = f.commits.first()
            refused {
                f.inventory(
                    commits = listOf(first.copy(commit = first.commit.copy(root = Cid.require(f.newRecord)))) + f.commits.drop(1),
                )
            }
            refused { f.inventory(commits = listOf(first.copy(commit = first.commit.copy(recordCount = 999))) + f.commits.drop(1)) }
            refused { f.inventory(commits = listOf(first.copy(signature = "tampered")) + f.commits.drop(1)) }
        }

    @Test fun duplicateOrOutOfOrderTreeKeysAndInvalidPrefixLengthsAreRefused() {
        for (keys in listOf(
            listOf("test.example.record/b", "test.example.record/a"),
            listOf("test.example.record/a", "test.example.record/a"),
        )) {
            val f = tree(keys)
            refused { f.inventory() }
        }
        val f = tree(listOf("test.example.record/a"), firstPrefix = 1)
        refused { f.inventory() }
    }

    @Test fun aTreeLeafMustReferenceACanonicalObjectAndNotAnUnownedOrRawBody() {
        val raw = byteArrayOf(1, 2, 3)
        val f = tree(listOf("test.example.record/a"), body = raw, bodyCid = Cid.rawSha256(raw))
        refused { f.inventory() }
    }

    @Test fun sharedNativeRepositoryFixtureHasTheSameLogicalInventory() {
        val text = checkNotNull(javaClass.getResource("/identity-adoption/repository-inventory-v1.json")).readText()
        val value =
            kotlinx.serialization.json.Json
                .parseToJsonElement(text)
                .jsonObject
        assertEquals(DID, value.getValue("sourceDid").jsonPrimitive.content)
        val rawHead = value.getValue("head").jsonObject
        val head =
            RepoHead(
                AtprotoDid.require(DID),
                Cid.require(rawHead.getValue("root").jsonPrimitive.content),
                Cid.require(rawHead.getValue("commit").jsonPrimitive.content),
                rawHead.getValue("revision").jsonPrimitive.long,
            )
        val commits =
            value.getValue("commits").jsonArray.map { entry ->
                val row = entry.jsonObject
                val previous =
                    row
                        .getValue("prev")
                        .takeUnless { it == JsonNull }
                        ?.jsonPrimitive
                        ?.content
                        ?.let(Cid::require)
                SignedRepoCommit(
                    Cid.require(row.getValue("cid").jsonPrimitive.content),
                    RepoCommit(
                        AtprotoDid.require(DID),
                        Cid.require(row.getValue("root").jsonPrimitive.content),
                        previous,
                        row.getValue("revision").jsonPrimitive.long,
                        row.getValue("createdAt").jsonPrimitive.long,
                        row.getValue("count").jsonPrimitive.int,
                    ),
                    row.getValue("signature").jsonPrimitive.content,
                )
            }
        val blocks =
            value.getValue("blocks").jsonArray.associate { entry ->
                val row = entry.jsonObject
                row.getValue("cid").jsonPrimitive.content to Base64.getUrlDecoder().decode(row.getValue("bytes").jsonPrimitive.content)
            }
        val actual = IdentityAdoptionRepositoryAuditor.inventory(DID, head, commits, blocks.keys.toList(), blocks::get)
        val encoded =
            buildJsonObject {
                put(
                    "records",
                    buildJsonArray {
                        actual.records.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("collection", row.collection)
                                    put("rkey", row.rkey)
                                    put("cid", row.cid)
                                },
                            )
                        }
                    },
                )
                put(
                    "blocks",
                    buildJsonArray {
                        actual.blocks.forEach { row ->
                            add(
                                buildJsonObject {
                                    put("cid", row.cid)
                                    put(
                                        "roles",
                                        buildJsonArray {
                                            row.roles
                                                .map { it.name }
                                                .sorted()
                                                .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        assertEquals(value.getValue("expected"), encoded)
    }

    private fun refused(block: () -> Unit) {
        assertEquals(
            IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
            assertFailsWith<IdentityAdoptionStagingException>(block = block).reason,
        )
    }

    private suspend fun fixture(): Fixture {
        val store = InMemoryRepoBlockStore()
        val did = AtprotoDid.require(DID)
        val engine = DefaultRepoEngine(store)
        val record = RepoRecordId(did, Nsid.require("studio.hypertext.logdate.content"), RecordKey.require("note"))
        val old = engine.putRecord(record, buildJsonObject { put("preserved", "old") }).getOrThrow().cid
        val newer = engine.putRecord(record, buildJsonObject { put("preserved", "new") }).getOrThrow().cid
        engine
            .createRecord(
                did,
                Nsid.require("future.example.record"),
                buildJsonObject {
                    put("future", "kept")
                },
                RecordKey.require("unknown"),
            ).getOrThrow()
        val orphanBytes = DagCborCodec.encode(buildJsonObject { put("orphan", true) })
        val orphan = Cid.sha256(DAG_CBOR_CODEC, orphanBytes)
        val raw = byteArrayOf(9, 8, 7)
        store.writeBlock(did, RepoBlock(orphan, orphanBytes)).getOrThrow()
        store.writeBlock(did, RepoBlock(Cid.rawSha256(raw), raw)).getOrThrow()
        val blocks =
            store
                .listBlocks(did)
                .getOrThrow()
                .associate { it.cid.value to it.bytes }
                .toMutableMap()
        return Fixture(
            store.readHead(did).getOrThrow()!!,
            store.listCommits(did).getOrThrow(),
            blocks,
            old,
            newer,
            orphan.value,
            Cid.rawSha256(raw).value,
        )
    }

    private fun tree(
        keys: List<String>,
        firstPrefix: Int = 0,
        body: ByteArray = DagCborCodec.encode(buildJsonObject { put("record", true) }),
        bodyCid: Cid = Cid.sha256(DAG_CBOR_CODEC, body),
    ): Fixture {
        val rootBytes =
            DagCborCodec.encode(
                buildJsonObject {
                    put("l", JsonNull)
                    put(
                        "e",
                        buildJsonArray {
                            keys.forEachIndexed { i, key ->
                                add(
                                    buildJsonObject {
                                        put("p", if (i == 0) firstPrefix else 0)
                                        put("k", binary(key.encodeToByteArray()))
                                        put("v", link(bodyCid))
                                        put("t", JsonNull)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        val root = Cid.sha256(DAG_CBOR_CODEC, rootBytes)
        val sig = ByteArray(32) { 1 }
        val commitBytes =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 3)
                    put("did", DID)
                    put("data", link(root))
                    put("prev", JsonNull)
                    put("rev", Tid.fromLong(1).toString())
                    put("sig", binary(sig))
                },
            )
        val cid = Cid.sha256(DAG_CBOR_CODEC, commitBytes)
        val commit =
            SignedRepoCommit(
                cid = cid,
                commit = RepoCommit(AtprotoDid.require(DID), root, null, 1, 0, keys.size),
                signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig),
            )
        return Fixture(
            RepoHead(AtprotoDid.require(DID), root, cid, 1),
            listOf(commit),
            mutableMapOf(root.value to rootBytes, cid.value to commitBytes, bodyCid.value to body),
        )
    }

    private class Fixture(
        val head: RepoHead,
        val commits: List<SignedRepoCommit>,
        val blocks: MutableMap<String, ByteArray>,
        val oldRecord: String = "",
        val newRecord: String = "",
        val unreferenced: String = "",
        val raw: String = "",
    ) {
        fun inventory(
            head: RepoHead? = this.head,
            commits: List<SignedRepoCommit> = this.commits,
            cids: List<String> = blocks.keys.toList(),
        ) = IdentityAdoptionRepositoryAuditor.inventory(DID, head, commits, cids, blocks::get)
    }

    private fun binary(bytes: ByteArray) =
        buildJsonObject {
            put("\$bytes", Base64.getEncoder().withoutPadding().encodeToString(bytes))
        }

    private fun link(cid: Cid) = buildJsonObject { put("\$link", cid.value) }

    private companion object {
        const val DID = "did:web:source.example"
    }
}
