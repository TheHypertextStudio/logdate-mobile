@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.space.adoption

import app.logdate.space.SealedConformanceFixtures
import app.logdate.space.SealingKeys
import app.logdate.space.SpaceRef
import app.logdate.space.hexToBytes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.repo.Cid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class IdentityAdoptionManifestTest {
    private val root = Json.parseToJsonElement(SealedConformanceFixtures.text("adoption/manifest-v1.json")).jsonObject
    private val expected = root.getValue("expected").jsonObject

    @Test
    fun `manifest and inventories match the shared native known answer`() =
        runTest {
            assertEquals(1L, root.number("suiteVersion"))
            val value = manifest()
            assertContentEquals(expected.text("manifestCborHex").hexToBytes(), value.canonicalBytes())
            assertEquals(expected.bytes("manifestSha256Hex"), value.digest())
            assertEquals(expected.bytes("recordDigestHex"), value.records.digest)
            assertEquals(expected.bytes("blobDigestHex"), value.blobs.digest)
            assertEquals(2L, value.records.count)
            assertEquals(1L, value.records.live)
            assertEquals(1L, value.records.tombstones)
            assertEquals(1L, value.blobs.count)
            assertTrue("Synthetic private note" !in value.canonicalBytes().decodeToString())
        }

    @Test
    fun `private snapshot commitments match the shared native known answer`() =
        runTest {
            val keys = SealingKeys.personal(root.text("identityKeyHex").hexToBytes())
            val local = commitments(keys)
            assertEquals(expected.bytes("indexKeyHex"), Bytes(keys.indexKey))
            assertEquals(expected.bytes("pendingCommitmentHex"), local.pending)
            assertEquals(expected.bytes("conflictCommitmentHex"), local.conflicts)
            assertNotEquals(local.pending, local.conflicts)
            val other = commitments(SealingKeys.personal(ByteArray(32) { 8 }))
            assertNotEquals(other.pending, local.pending)
        }

    @Test
    fun `every authority binding changes the manifest digest`() =
        runTest {
            val base = manifest()
            val otherId = Uuid.parse("00000000-0000-4000-8000-000000000009")
            val variants =
                listOf(
                    base.binding.copy(operationId = otherId),
                    base.binding.copy(accountId = otherId),
                    base.binding.copy(backendOrigin = "https://other.example"),
                    base.binding.copy(deviceId = "other-device"),
                    base.binding.copy(sourceEpoch = 1),
                    base.binding.copy(sourceSequence = 301),
                    base.binding.copy(target = SpaceRef.personal("did:web:other.example")),
                )
            for (binding in variants) assertNotEquals(base.digest(), base.copy(binding = binding).digest())
        }

    @Test
    fun `invalid authority and summaries cannot produce a manifest`() =
        runTest {
            val base = manifest()
            val invalid =
                listOf(
                    base.copy(binding = base.binding.copy(sourceEpoch = -1)),
                    base.copy(binding = base.binding.copy(sourceEpoch = Long.MAX_VALUE)),
                    base.copy(binding = base.binding.copy(sourceSequence = -1)),
                    base.copy(binding = base.binding.copy(deviceId = "")),
                    base.copy(binding = base.binding.copy(deviceId = "device\n")),
                    base.copy(binding = base.binding.copy(target = base.binding.source)),
                    base.copy(records = base.records.copy(count = 9)),
                    base.copy(records = base.records.copy(live = Long.MAX_VALUE, tombstones = 1)),
                    base.copy(blobs = base.blobs.copy(bytes = 0)),
                    base.copy(pendingCommitment = Bytes(ByteArray(31))),
                ) +
                    listOf(
                        "http://cloud.example",
                        "https://name:secret@cloud.example",
                        "https://cloud.example/path",
                        "https://cloud.example?",
                        "https://cloud.example#",
                        "https://cloud.example:0",
                    ).map {
                        base.copy(binding = base.binding.copy(backendOrigin = it))
                    }
            for (value in invalid) assertFailsWith<IdentityAdoptionException> { value.canonicalBytes() }
        }

    @Test
    fun `rejected evidence cannot change accepted counts or the digest`() {
        val rows = rows()
        IdentityAdoptionRecordInventory().use { inventory ->
            inventory.append(rows[0])
            assertFailsWith<IdentityAdoptionException> { inventory.append(rows[0]) }
            assertFailsWith<IdentityAdoptionException> { inventory.append(rows[1].copy(target = rows[0].target)) }
            assertFailsWith<IdentityAdoptionException> { inventory.append(rows[1].copy(sourceVersion = -1)) }
            assertFailsWith<IdentityAdoptionException> {
                inventory.append(rows[1].copy(source = rows[1].source.copy(recordKey = "b\n")))
            }
            inventory.append(rows[1])
            assertEquals(expected.bytes("recordDigestHex"), inventory.finish().digest)
            assertEquals(2L, inventory.finish().count)
            assertFailsWith<IdentityAdoptionException> { inventory.append(rows[1]) }
        }
    }

    @Test
    fun `one source keeps distinct ordered outputs with identical provenance`() {
        val first = rows()[0]
        val second = first.copy(target = first.target.copy(recordKey = first.target.recordKey + "-transcript"))
        IdentityAdoptionRecordInventory().use { inventory ->
            inventory.append(first)
            inventory.append(second)
            assertEquals(2L, inventory.finish().count)
        }
        for (invalid in listOf(second.copy(sourceVersion = first.sourceVersion + 1), second.copy(sourceDigest = Bytes(ByteArray(32))))) {
            IdentityAdoptionRecordInventory().use { inventory ->
                inventory.append(first)
                assertFailsWith<IdentityAdoptionException> { inventory.append(invalid) }
                assertEquals(1L, inventory.finish().count)
            }
        }
        IdentityAdoptionRecordInventory().use { inventory ->
            inventory.append(second)
            assertFailsWith<IdentityAdoptionException> { inventory.append(first) }
            assertEquals(1L, inventory.finish().count)
        }
    }

    @Test
    fun `blob evidence refuses duplicates mismatches and overflow without advancing`() {
        val blob =
            root
                .getValue("blobs")
                .jsonArray
                .single()
                .jsonObject
        IdentityAdoptionBlobInventory().use { inventory ->
            inventory.append(blob.text("cid"), blob.number("size"), blob.bytes("ciphertextDigest"))
            assertFailsWith<IdentityAdoptionException> {
                inventory.append(blob.text("cid"), blob.number("size"), blob.bytes("ciphertextDigest"))
            }
            val different = Cid.fromBytes(byteArrayOf(1, 0x55, 0x12, 0x20) + ByteArray(32) { 0xff.toByte() }).value
            assertFailsWith<IdentityAdoptionException> { inventory.append(different, 1, blob.bytes("ciphertextDigest")) }
            assertFailsWith<IdentityAdoptionException> { inventory.append(different, Long.MAX_VALUE, Bytes(ByteArray(32) { -1 })) }
            assertEquals(expected.bytes("blobDigestHex"), inventory.finish().digest)
            assertEquals(blob.number("size"), inventory.finish().bytes)
        }
    }

    private suspend fun manifest(): IdentityAdoptionManifest {
        val fields = root.getValue("binding").jsonObject
        val binding =
            IdentityAdoptionBinding(
                Uuid.parse(fields.text("operationId")),
                Uuid.parse(fields.text("accountId")),
                fields.text("backendOrigin"),
                fields.text("deviceId"),
                requireNotNull(SpaceRef.parse(fields.text("sourceSpace"))),
                requireNotNull(SpaceRef.parse(fields.text("targetSpace"))),
                fields.number("sourceEpoch"),
                fields.number("sourceSequence"),
            )
        val records =
            IdentityAdoptionRecordInventory().use { inventory ->
                rows().forEach(inventory::append)
                inventory.finish()
            }
        val blobs =
            IdentityAdoptionBlobInventory().use { inventory ->
                root.getValue("blobs").jsonArray.forEach { item ->
                    val blob = item.jsonObject
                    inventory.append(blob.text("cid"), blob.number("size"), blob.bytes("ciphertextDigest"))
                }
                inventory.finish()
            }
        val local = commitments(SealingKeys.personal(root.text("identityKeyHex").hexToBytes()))
        return IdentityAdoptionManifest(binding, records, blobs, local.pending, local.conflicts)
    }

    private suspend fun commitments(keys: SealingKeys): IdentityAdoptionCommitments =
        IdentityAdoptionLocalCommitments.make(
            keys,
            root.getValue("pending").jsonArray,
            root.getValue("conflicts").jsonArray,
        )

    private fun rows(): List<IdentityAdoptionRecordEvidence> =
        root.getValue("records").jsonArray.map { element ->
            val row = element.jsonObject
            IdentityAdoptionRecordEvidence(
                IdentityAdoptionWireKey(row.text("sourceCollection"), row.text("sourceRkey")),
                IdentityAdoptionWireKey(row.text("targetCollection"), row.text("targetRkey")),
                row.number("sourceVersion"),
                row.bytes("sourceDigest"),
                row.bytes("targetDigest"),
                row.getValue("deleted").jsonPrimitive.boolean,
            )
        }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.bytes(key: String): Bytes = Bytes(text(key).hexToBytes())
}
