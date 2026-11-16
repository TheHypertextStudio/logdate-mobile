package app.logdate.client.device.restore

import app.logdate.client.device.crypto.ExistingIdentityKeySnapshot
import app.logdate.client.device.crypto.ExistingMediaKeySource
import app.logdate.client.device.crypto.RootlessIdentityKeyObservation
import app.logdate.client.device.identity.ExistingCanonicalOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PrivateOsCustodyRecordTest {
    private val ownerId = "B930BF49-D1E9-4A65-B84B-8E74AF45012B"
    private val transferId = Uuid.parse("c930bf49-d1e9-4a65-b84b-8e74af45012b")
    private val generation = ByteArray(32)
    private val root = ByteArray(32)
    private val media = ByteArray(32) { 1 }

    @Test
    fun `nonconfidential or non-ios transports never read encryption material`() =
        runTest {
            for (flags in listOf(0, 1, 2, 3, 4, 8, 10, 12)) {
                assertNull(prepare(flags = flags) { error("Keys must not be read") })
            }
        }

    @Test
    fun `confidential ios transport emits an archive-bound existing cloud identity`() =
        runTest {
            val record = assertNotNull(prepare())
            val document = record.withEncodedBytes { Json.parseToJsonElement(it.decodeToString()).jsonObject }
            assertEquals("1", document.getValue("version").jsonPrimitive.content)
            assertEquals("logdate-os-private-custody", document.getValue("purpose").jsonPrimitive.content)
            assertEquals(transferId.toString(), document.getValue("transferID").jsonPrimitive.content)
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", document.getValue("sourceGeneration").jsonPrimitive.content)
            assertEquals("existingRoot", document.getValue("sourceClassification").jsonPrimitive.content)
            val owner = document.getValue("owner").jsonObject
            assertEquals("cloudAccount", owner.getValue("kind").jsonPrimitive.content)
            assertEquals(ownerId, owner.getValue("accountIDRaw").jsonPrimitive.content)
            assertEquals("existingRoot", owner.getValue("rootState").jsonPrimitive.content)
            assertEquals(
                "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925",
                owner.getValue("identityKeySHA256").jsonPrimitive.content,
            )
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", document.getValue("identityKey").jsonPrimitive.content)
            assertEquals("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE", document.getValue("mediaKey").jsonPrimitive.content)
            assertEquals("cached", document.getValue("mediaSelection").jsonPrimitive.content)
            assertEquals(
                "a".repeat(64),
                document
                    .getValue("archive")
                    .jsonObject
                    .getValue("checksumsFileSHA256")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "b".repeat(64),
                document
                    .getValue("archive")
                    .jsonObject
                    .getValue("manifestSHA256")
                    .jsonPrimitive.content,
            )
            assertFalse("canonicalOwnerIDRaw" in owner)
            assertEquals(
                setOf(
                    "version",
                    "purpose",
                    "transferID",
                    "sourceGeneration",
                    "owner",
                    "sourceClassification",
                    "archive",
                    "identityKey",
                    "mediaKey",
                    "mediaSelection",
                ),
                document.keys,
            )
            assertTrue(record.withEncodedBytes { it.size <= 4096 })
        }

    @Test
    fun `equivalent secure account spelling keeps the exact canonical record binding`() =
        runTest {
            val record = assertNotNull(prepare { snapshot(account = ownerId.lowercase()) })
            val owner =
                record.withEncodedBytes {
                    Json
                        .parseToJsonElement(it.decodeToString())
                        .jsonObject
                        .getValue("owner")
                        .jsonObject
                }
            assertEquals(ownerId, owner.getValue("accountIDRaw").jsonPrimitive.content)
        }

    @Test
    fun `existing local owner remains distinct from a cloud account`() =
        runTest {
            val local = ExistingCanonicalOwner(ownerId, false)
            val record = assertNotNull(prepare(owner = local) { snapshot(account = null) })
            val owner =
                record.withEncodedBytes {
                    Json
                        .parseToJsonElement(it.decodeToString())
                        .jsonObject
                        .getValue("owner")
                        .jsonObject
                }
            assertEquals("localCanonical", owner.getValue("kind").jsonPrimitive.content)
            assertEquals(ownerId, owner.getValue("canonicalOwnerIDRaw").jsonPrimitive.content)
            assertFalse("accountIDRaw" in owner)
        }

    @Test
    fun `verified text only local journal carries no invented identity or media key`() =
        runTest {
            val record =
                assertNotNull(
                    PrivateOsCustodyRecord.prepareRootlessPlaintext(
                        9,
                        transferId,
                        generation,
                        ExistingCanonicalOwner(ownerId, false),
                        "a".repeat(64),
                        "b".repeat(64),
                    ) { RootlessIdentityKeyObservation(null, false) },
                )
            val document = record.withEncodedBytes { Json.parseToJsonElement(it.decodeToString()).jsonObject }
            assertEquals("verifiedRootlessPlaintext", document.getValue("sourceClassification").jsonPrimitive.content)
            assertEquals(
                "absentUnencrypted",
                document
                    .getValue("owner")
                    .jsonObject
                    .getValue("rootState")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(
                setOf("version", "purpose", "transferID", "sourceGeneration", "owner", "sourceClassification", "archive"),
                document.keys,
            )
            assertNull(
                PrivateOsCustodyRecord.prepareRootlessPlaintext(
                    8,
                    transferId,
                    generation,
                    ExistingCanonicalOwner(ownerId, false),
                    "a".repeat(64),
                    "b".repeat(64),
                ) { error("Rootless observation must not run") },
            )
        }

    @Test
    fun `rootless media material cannot be mislabeled as plaintext`() =
        runTest {
            val observations =
                listOf(
                    RootlessIdentityKeyObservation(ByteArray(32) { 7 }, false),
                    RootlessIdentityKeyObservation(null, true),
                )
            for (observation in observations) {
                assertNull(
                    PrivateOsCustodyRecord.prepareRootlessPlaintext(
                        9,
                        transferId,
                        generation,
                        ExistingCanonicalOwner(ownerId, false),
                        "a".repeat(64),
                        "b".repeat(64),
                    ) { observation },
                )
            }
        }

    @Test
    fun `media provenance preserves cached retained and derived existing-root selections`() =
        runTest {
            val selections =
                listOf(
                    ExistingMediaKeySource.CACHED to "cached",
                    ExistingMediaKeySource.RETAINED to "retainedLegacy",
                    ExistingMediaKeySource.DERIVED_EXISTING_ROOT to "derivedExistingRoot",
                )
            for ((source, expected) in selections) {
                val record = assertNotNull(prepare { snapshot(source = source) })
                val selection =
                    record.withEncodedBytes {
                        Json
                            .parseToJsonElement(it.decodeToString())
                            .jsonObject
                            .getValue("mediaSelection")
                            .jsonPrimitive.content
                    }
                assertEquals(expected, selection)
            }
        }

    @Test
    fun `missing existing keys never become rootless permission`() = runTest { assertNull(prepare { null }) }

    @Test
    fun `mismatched secure account refuses a private record`() =
        runTest {
            assertFailsWith<PrivateOsCustodyUnavailable> { prepare { snapshot(account = "other") } }
            assertFailsWith<PrivateOsCustodyUnavailable> {
                prepare(owner = ExistingCanonicalOwner(ownerId, false)) { snapshot() }
            }
        }

    @Test
    fun `invalid public bindings refuse before reading keys`() =
        runTest {
            var reads = 0
            val forbiddenRead: suspend () -> ExistingIdentityKeySnapshot? = {
                reads += 1
                error("Unexpected key read")
            }
            for (digest in listOf("", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65))) {
                assertFailsWith<PrivateOsCustodyUnavailable> { prepare(checksums = digest, keys = forbiddenRead) }
                assertFailsWith<PrivateOsCustodyUnavailable> { prepare(manifest = digest, keys = forbiddenRead) }
            }
            for (size in listOf(0, 31, 33)) {
                assertFailsWith<PrivateOsCustodyUnavailable> { prepare(generation = ByteArray(size), keys = forbiddenRead) }
            }
            assertFailsWith<PrivateOsCustodyUnavailable> {
                prepare(owner = ExistingCanonicalOwner("not-an-owner", true), keys = forbiddenRead)
            }
            assertEquals(0, reads, "Invalid archive bindings must be refused before reading keys")
        }

    @Test
    fun `source generation is captured before a suspending key reader`() =
        runTest {
            val mutableGeneration = ByteArray(32)
            val record =
                assertNotNull(
                    prepare(generation = mutableGeneration) {
                        mutableGeneration.fill(2)
                        snapshot()
                    },
                )
            val encoded =
                record.withEncodedBytes {
                    Json
                        .parseToJsonElement(it.decodeToString())
                        .jsonObject
                        .getValue("sourceGeneration")
                        .jsonPrimitive.content
                }
            assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", encoded)
        }

    @Test
    fun `malformed existing keys refuse without revealing their contents`() =
        runTest {
            for (size in listOf(0, 31, 33)) {
                assertFailsWith<PrivateOsCustodyUnavailable> { prepare { snapshot(root = ByteArray(size)) } }
                assertFailsWith<PrivateOsCustodyUnavailable> { prepare { snapshot(media = ByteArray(size)) } }
            }
        }

    @Test
    fun `key read failures are redacted without retaining their cause`() =
        runTest {
            val failure = assertFailsWith<PrivateOsCustodyUnavailable> { prepare { error("private-value-that-must-not-escape") } }
            assertFalse(failure.toString().contains("private-value"))
            assertNull(failure.cause)
        }

    @Test
    fun `cancellation remains cancellation`() =
        runTest { assertFailsWith<CancellationException> { prepare { throw CancellationException("cancelled") } } }

    @Test
    fun `record diagnostics are redacted and callers cannot mutate retained bytes`() =
        runTest {
            val record = assertNotNull(prepare())
            assertFalse(record.toString().contains("AQEBAQE"))
            assertFalse(record.toString().contains(ownerId))
            val original = record.withEncodedBytes { it.decodeToString() }
            record.withEncodedBytes { it.fill(0) }
            assertEquals(original, record.withEncodedBytes { it.decodeToString() })
        }

    private suspend fun prepare(
        flags: Int = 9,
        owner: ExistingCanonicalOwner = ExistingCanonicalOwner(ownerId, true),
        generation: ByteArray = this.generation,
        checksums: String = "a".repeat(64),
        manifest: String = "b".repeat(64),
        keys: suspend () -> ExistingIdentityKeySnapshot? = { snapshot() },
    ): PrivateOsCustodyRecord? = PrivateOsCustodyRecord.prepare(flags, transferId, generation, owner, checksums, manifest, keys)

    private fun snapshot(
        account: String? = ownerId,
        source: ExistingMediaKeySource = ExistingMediaKeySource.CACHED,
        root: ByteArray = this.root,
        media: ByteArray = this.media,
    ) = ExistingIdentityKeySnapshot(account, root, media, source)
}
