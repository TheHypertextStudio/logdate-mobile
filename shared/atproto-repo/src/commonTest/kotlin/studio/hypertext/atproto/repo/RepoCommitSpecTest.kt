package studio.hypertext.atproto.repo

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Checks commits against the AT Protocol repository specification: revisions are time-based
 * TIDs that always increase, and commit objects have the exact version 3 shape.
 */
class RepoCommitSpecTest {
    private val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    private val collection = Nsid.require("studio.hypertext.logdate.entry")

    @Test
    fun `commit revision is the TID for the time of the commit`() =
        runSuspend {
            val now = Instant.parse("2026-10-18T12:00:00.123456Z")
            val engine = DefaultRepoEngine(InMemoryRepoBlockStore(), MutableClock(now))

            engine.putRecord(recordId("entry-1"), entry("one")).getOrThrow()

            val head = assertNotNull(engine.loadHead(repo).getOrThrow())
            assertEquals(Tid.fromInstant(now), Tid.fromLong(head.revision))
        }

    @Test
    fun `revisions always increase even when the clock stands still or goes backwards`() =
        runSuspend {
            val clock = MutableClock(Instant.parse("2026-10-18T12:00:00Z"))
            val skewEvents = mutableListOf<Pair<AtprotoDid, Long>>()
            val engine =
                DefaultRepoEngine(
                    blockStore = InMemoryRepoBlockStore(),
                    clock = clock,
                    onClockSkew = { skewedRepo, aheadMicros -> skewEvents += skewedRepo to aheadMicros },
                )

            engine.putRecord(recordId("entry-1"), entry("one")).getOrThrow()
            engine.putRecord(recordId("entry-2"), entry("two")).getOrThrow()
            assertTrue(skewEvents.isEmpty(), "Same-timestamp commits should not report clock skew")
            clock.now = clock.now - 10.minutes
            engine.deleteRecord(recordId("entry-1")).getOrThrow()
            assertEquals(1, skewEvents.size)
            assertEquals(repo, skewEvents.single().first)
            assertTrue(skewEvents.single().second >= 10.minutes.inWholeMicroseconds)

            val revisions =
                engine
                    .listCommits(repo)
                    .getOrThrow()
                    .reversed()
                    .map { it.commit.revision }
            assertEquals(3, revisions.size)
            assertEquals(revisions.sorted(), revisions)
            assertEquals(revisions.distinct(), revisions)
            assertEquals(revisions.map { Tid.fromLong(it).value }.sorted(), revisions.map { Tid.fromLong(it).value })
        }

    @Test
    fun `a repository written with counter revisions continues with TIDs after them`() =
        runSuspend {
            val blockStore = InMemoryRepoBlockStore()
            // Revisions used to be a counter starting at 1. A clock at the Unix epoch produces
            // the same small numbers, which stands in for a repository written before TIDs.
            val legacyEngine = DefaultRepoEngine(blockStore, MutableClock(Instant.fromEpochSeconds(0)))
            repeat(3) { index -> legacyEngine.putRecord(recordId("legacy-$index"), entry("legacy")).getOrThrow() }
            val legacyHead = assertNotNull(blockStore.readHead(repo).getOrThrow())
            assertTrue(legacyHead.revision < 10)

            val now = Clock.System.now()
            DefaultRepoEngine(blockStore, MutableClock(now)).putRecord(recordId("current"), entry("current")).getOrThrow()

            val head = assertNotNull(blockStore.readHead(repo).getOrThrow())
            assertTrue(head.revision > legacyHead.revision)
            assertEquals(Tid.fromInstant(now), Tid.fromLong(head.revision))
        }

    @Test
    fun `commit block has exactly the version 3 fields with a null prev on the first commit`() =
        runSuspend {
            val blockStore = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(blockStore)

            engine.putRecord(recordId("entry-1"), entry("one")).getOrThrow()

            val head = assertNotNull(blockStore.readHead(repo).getOrThrow())
            val commitBlock = assertNotNull(blockStore.readBlock(head.commitCid).getOrThrow())
            val commit = DagCborCodec.decode(commitBlock.bytes).jsonObject
            assertEquals(setOf("data", "did", "prev", "rev", "sig", "version"), commit.keys)
            assertEquals(3, commit.getValue("version").jsonPrimitive.int)
            assertEquals(repo.toString(), commit.getValue("did").jsonPrimitive.content)
            assertEquals(head.root, DagCborCodec.linkOrNull(commit.getValue("data")))
            assertEquals(JsonNull, commit.getValue("prev"))
            assertEquals(Tid.fromLong(head.revision).value, commit.getValue("rev").jsonPrimitive.content)
            assertNotNull(DagCborCodec.bytesOrNull(commit.getValue("sig")))
            assertEquals(head.commitCid, Cid.sha256(DAG_CBOR_CODEC, commitBlock.bytes))
        }

    @Test
    fun `signed payload is the commit without its signature`() =
        runSuspend {
            val payloads = mutableListOf<ByteArray>()
            val signer =
                object : RepoCommitSigner {
                    override suspend fun sign(
                        commit: RepoCommit,
                        payload: ByteArray,
                    ): String {
                        payloads += payload
                        return DigestRepoCommitSigner.sign(commit, payload)
                    }

                    override suspend fun verify(
                        commit: RepoCommit,
                        payload: ByteArray,
                        signature: String,
                    ): Boolean = DigestRepoCommitSigner.verify(commit, payload, signature)
                }
            val blockStore = InMemoryRepoBlockStore()
            DefaultRepoEngine(blockStore, signer = signer).putRecord(recordId("entry-1"), entry("one")).getOrThrow()

            val head = assertNotNull(blockStore.readHead(repo).getOrThrow())
            val signed = DagCborCodec.decode(assertNotNull(blockStore.readBlock(head.commitCid).getOrThrow()).bytes).jsonObject
            val unsigned = DagCborCodec.encode(JsonObject(signed - "sig"))
            assertContentEquals(unsigned, payloads.single())
        }

    @Test
    fun `signature bytes are the decoded signer output whatever letter it starts with`() =
        runSuspend {
            // A base64url signature can start with "b", like a CID string does. It must still be
            // stored as the raw signature bytes, not as the text of the signature.
            val signatureBytes = "bafy-raw-signature-bytes".encodeToByteArray()
            val signer =
                object : RepoCommitSigner {
                    override suspend fun sign(
                        commit: RepoCommit,
                        payload: ByteArray,
                    ): String = "bWFkZS11cC1zaWduYXR1cmU"

                    override suspend fun verify(
                        commit: RepoCommit,
                        payload: ByteArray,
                        signature: String,
                    ): Boolean = true
                }
            val blockStore = InMemoryRepoBlockStore()
            DefaultRepoEngine(blockStore, signer = signer).putRecord(recordId("entry-1"), entry("one")).getOrThrow()

            val head = assertNotNull(blockStore.readHead(repo).getOrThrow())
            val commit = DagCborCodec.decode(assertNotNull(blockStore.readBlock(head.commitCid).getOrThrow()).bytes).jsonObject
            assertEquals("made-up-signature", assertNotNull(DagCborCodec.bytesOrNull(commit.getValue("sig"))).decodeToString())

            val rawBlock = rawCommitBlock(signatureBytes, head)
            val restored = CarCodec.read(CarCodec.writeCar(roots = listOf(rawBlock.cid), blocks = listOf(rawBlock)))
            assertContentEquals(signatureBytes, encodeCommitSignature(restored.commits.single().signature))
        }

    @Test
    fun `export carries stored commit blocks byte for byte`() =
        runSuspend {
            val blockStore = InMemoryRepoBlockStore()
            // Commits written before `prev` was always present left it out of the first commit.
            // Re-encoding that commit today would produce different bytes from the ones its CID
            // names, so the export has to carry the stored block.
            val root = MerkleSearchTree.empty().toBlockGraph()
            root.blocks.forEach { blockStore.writeBlock(repo, it).getOrThrow() }
            val legacyBytes =
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 3)
                        put("did", repo.toString())
                        put("data", DagCborCodec.link(root.root))
                        put("rev", Tid.fromLong(1).value)
                        put("sig", DagCborCodec.bytes("legacy".encodeToByteArray()))
                    },
                )
            val legacyCid = Cid.sha256(DAG_CBOR_CODEC, legacyBytes)
            blockStore.writeBlock(repo, RepoBlock(legacyCid, legacyBytes)).getOrThrow()
            blockStore
                .appendCommit(
                    repo,
                    SignedRepoCommit(
                        cid = legacyCid,
                        commit = RepoCommit(repo = repo, root = root.root, revision = 1, createdAtEpochMillis = 0, recordCount = 0),
                        signature = "bGVnYWN5",
                    ),
                ).getOrThrow()
            blockStore.writeHead(RepoHead(repo = repo, root = root.root, commitCid = legacyCid, revision = 1)).getOrThrow()
            val engine = DefaultRepoEngine(blockStore)
            engine.putRecord(recordId("entry-1"), entry("one")).getOrThrow()

            val archive = CarCodec.readCar(CarCodec.write(engine.export(repo).getOrThrow()))

            archive.blocks.forEach { block ->
                val expected = if (block.cid.toBytes()[1].toInt() == RAW_CODEC) RAW_CODEC else DAG_CBOR_CODEC
                assertEquals(block.cid, Cid.sha256(expected, block.bytes), "block ${block.cid} does not hash to its CID")
            }
            assertContentEquals(legacyBytes, archive.blocks.first { it.cid == legacyCid }.bytes)
        }

    @Test
    fun `writing a CAR fails when a commit block is missing and cannot be rebuilt`() {
        val root = MerkleSearchTree.empty().toBlockGraph()
        val unrelatedCid = Cid.sha256(DAG_CBOR_CODEC, "not this commit".encodeToByteArray())
        val export =
            RepoExport(
                repo = repo,
                head = RepoHead(repo = repo, root = root.root, commitCid = unrelatedCid, revision = 1),
                commits =
                    listOf(
                        SignedRepoCommit(
                            cid = unrelatedCid,
                            commit = RepoCommit(repo = repo, root = root.root, revision = 1, createdAtEpochMillis = 0, recordCount = 0),
                            signature = "c2ln",
                        ),
                    ),
                blocks = root.blocks,
            )

        val failure = assertFailsWith<IllegalArgumentException> { CarCodec.write(export) }
        assertTrue(failure.message.orEmpty().contains(unrelatedCid.toString()))
    }

    @Test
    fun `tree entries sort by the full key the way the specification compares them`() {
        val cid = Cid.sha256(DAG_CBOR_CODEC, "value".encodeToByteArray())
        val tree =
            MerkleSearchTree
                .empty()
                .put(Nsid.require("com.example.app"), RecordKey.require("x"), cid)
                .put(Nsid.require("com.example.app.two"), RecordKey.require("y"), cid)
                .put(Nsid.require("com.example.app-three.records"), RecordKey.require("z"), cid)

        assertEquals(
            listOf("com.example.app-three.records/z", "com.example.app.two/y", "com.example.app/x"),
            tree.entries().map { "${it.collection}/${it.recordKey}" },
        )
    }

    @Test
    fun `import rejects a record tree that is not in canonical order`() =
        runSuspend {
            val recordBytes = DagCborCodec.encode(entry("one"))
            val recordCid = Cid.sha256(DAG_CBOR_CODEC, recordBytes)
            // One node listing its keys out of order. The specification requires keys sorted
            // within a node, and a reader that trusted this tree would miss "entry-a" on lookup.
            val nodeBytes =
                DagCborCodec.encode(
                    buildJsonObject {
                        put("l", JsonNull)
                        put(
                            "e",
                            buildJsonArray {
                                add(treeEntry(prefix = 0, suffix = "$collection/entry-b", value = recordCid))
                                add(treeEntry(prefix = collection.toString().length + 7, suffix = "a", value = recordCid))
                            },
                        )
                    },
                )
            val nodeCid = Cid.sha256(DAG_CBOR_CODEC, nodeBytes)
            val commit = RepoCommit(repo = repo, root = nodeCid, revision = 1, createdAtEpochMillis = 0, recordCount = 2)
            val signature = DigestRepoCommitSigner.sign(commit, encodeCommitPayload(commit))
            val commitBytes = encodeSignedCommitPayload(commit, signature)
            val commitCid = Cid.sha256(DAG_CBOR_CODEC, commitBytes)
            val head = RepoHead(repo = repo, root = nodeCid, commitCid = commitCid, revision = 1)
            val export =
                RepoExport(
                    repo = repo,
                    head = head,
                    commits = listOf(SignedRepoCommit(cid = commitCid, commit = commit, signature = signature)),
                    blocks =
                        listOf(RepoBlock(recordCid, recordBytes), RepoBlock(nodeCid, nodeBytes), RepoBlock(commitCid, commitBytes)),
                )
            val blockStore = InMemoryRepoBlockStore()

            val failure = DefaultRepoEngine(blockStore).import(export).exceptionOrNull()

            assertTrue(failure is IllegalArgumentException, "expected a rejection, got $failure")
            assertTrue(failure.message.orEmpty().contains("canonical"), failure.message)
            assertEquals(null, blockStore.readHead(repo).getOrThrow())
            assertTrue(blockStore.listBlocks(repo).getOrThrow().isEmpty(), "A rejected import must not leave its blocks behind")
        }

    private fun treeEntry(
        prefix: Int,
        suffix: String,
        value: Cid,
    ) = buildJsonObject {
        put("p", prefix)
        put("k", DagCborCodec.bytes(suffix.encodeToByteArray()))
        put("v", DagCborCodec.link(value))
        put("t", JsonNull)
    }

    private fun rawCommitBlock(
        signature: ByteArray,
        head: RepoHead,
    ): RepoBlock {
        val bytes =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 3)
                    put("did", repo.toString())
                    put("data", DagCborCodec.link(head.root))
                    put("rev", Tid.fromLong(head.revision).value)
                    put("prev", JsonNull)
                    put("sig", DagCborCodec.bytes(signature))
                },
            )
        return RepoBlock(Cid.sha256(DAG_CBOR_CODEC, bytes), bytes)
    }

    private fun recordId(key: String): RepoRecordId = RepoRecordId(repo = repo, collection = collection, recordKey = RecordKey.require(key))

    private fun entry(text: String) =
        buildJsonObject {
            put("\$type", collection.toString())
            put("text", text)
        }

    private fun runSuspend(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    outcome = result
                }
            },
        )
        assertNotNull(outcome, "The in-memory engine never suspends, so the test body must finish synchronously").getOrThrow()
    }

    private class MutableClock(
        var now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }
}
