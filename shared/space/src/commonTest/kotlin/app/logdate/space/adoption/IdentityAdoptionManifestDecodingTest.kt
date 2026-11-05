package app.logdate.space.adoption

import app.logdate.space.SealedConformanceFixtures
import app.logdate.space.hexToBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentityAdoptionManifestDecodingTest {
    private fun knownBytes(): ByteArray =
        Json
            .parseToJsonElement(SealedConformanceFixtures.text("adoption/manifest-v1.json"))
            .jsonObject
            .getValue("expected")
            .jsonObject
            .getValue("manifestCborHex")
            .jsonPrimitive.content
            .hexToBytes()

    @Test fun canonicalNativeKnownAnswerDecodesWithoutChangingItsBindingOrCommitments() {
        val bytes = knownBytes()
        val manifest = identityAdoptionManifestFromCanonicalBytes(bytes)
        assertContentEquals(bytes, manifest.canonicalBytes())
        assertEquals("synthetic-device", manifest.binding.deviceId)
        assertEquals(300L, manifest.binding.sourceSequence)
        assertEquals(2L, manifest.records.count)
        assertEquals(1L, manifest.blobs.count)
    }

    @Test fun changingOnlyTheEncodingOrAddingTrailingDataIsRefused() {
        val bytes = knownBytes()
        val nonminimal = byteArrayOf(0xb8.toByte(), 13) + bytes.drop(1).toByteArray()
        for (input in listOf(bytes + byteArrayOf(0), nonminimal, byteArrayOf(0x9a.toByte(), 0, 1, 0, 0))) {
            assertFailsWith<IdentityAdoptionException> { identityAdoptionManifestFromCanonicalBytes(input) }
        }
    }

    @Test fun unknownVersionsAndAdditionalManifestFieldsCannotBeDowngraded() {
        val root = DagCborCodec.decode(knownBytes()) as JsonObject
        val future = DagCborCodec.encode(JsonObject(root + ("version" to JsonPrimitive(2))))
        val extended = DagCborCodec.encode(JsonObject(root + ("extra" to JsonPrimitive("preserve or refuse"))))
        for (bytes in listOf(future, extended)) {
            assertFailsWith<IdentityAdoptionException> { identityAdoptionManifestFromCanonicalBytes(bytes) }
        }
    }

    @Test fun oversizedAndStructurallyInvalidManifestInputsAreRefused() {
        for (bytes in listOf(ByteArray(16385), byteArrayOf(0x80.toByte()), byteArrayOf())) {
            assertFailsWith<IdentityAdoptionException> { identityAdoptionManifestFromCanonicalBytes(bytes) }
        }
    }
}
