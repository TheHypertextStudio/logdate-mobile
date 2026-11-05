@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity

import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceProof
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionDeviceProofTest {
    @Test
    fun aFreshOperationKeySignsTheExactConfirmation() {
        val pair = EcKeySupport.generateKeyPair(EcCurve.P256)
        val key = IdentityAdoptionDeviceKey(ORIGIN, encode(publicBytes(pair.public as ECPublicKey)))
        val bytes = assertNotNull(IdentityAdoptionDeviceProof.message(OPERATION, DECISION, CHALLENGE, key))
        val signature = encode(EcKeySupport.signSha256(pair.private, EcCurve.P256, bytes))
        assertTrue(IdentityAdoptionDeviceProof.verify(OPERATION, DECISION, CHALLENGE, key, signature))
        assertFalse(IdentityAdoptionDeviceProof.verify(OPERATION, Uuid.random(), CHALLENGE, key, signature))
        assertFalse(IdentityAdoptionDeviceProof.verify(OPERATION, DECISION, "b".repeat(43), key, signature))
        assertFalse(IdentityAdoptionDeviceProof.verify(OPERATION.copy(revision = 2), DECISION, CHALLENGE, key, signature))
        assertFalse(
            IdentityAdoptionDeviceProof.verify(OPERATION, DECISION, CHALLENGE, key.copy(origin = "https://other.example"), signature),
        )
    }

    @Test
    fun everyAccountAndAuthorityFieldIsCoveredByTheSignature() {
        val pair = EcKeySupport.generateKeyPair(EcCurve.P256)
        val key = IdentityAdoptionDeviceKey(ORIGIN, encode(publicBytes(pair.public as ECPublicKey)))
        val bytes = assertNotNull(IdentityAdoptionDeviceProof.message(OPERATION, DECISION, CHALLENGE, key))
        val signature = encode(EcKeySupport.signSha256(pair.private, EcCurve.P256, bytes))
        val b = OPERATION.binding
        val alternatives =
            listOf(
                OPERATION.copy(id = Uuid.random().toString()),
                OPERATION.copy(binding = b.copy(accountId = Uuid.random())),
                OPERATION.copy(binding = b.copy(sourceDid = "did:web:other.example")),
                OPERATION.copy(binding = b.copy(targetDid = "did:web:other.example")),
                OPERATION.copy(binding = b.copy(sourceEpoch = 1)),
                OPERATION.copy(binding = b.copy(deviceId = "other-device")),
            )
        for (other in alternatives) assertFalse(IdentityAdoptionDeviceProof.verify(other, DECISION, CHALLENGE, key, signature))
        val otherKey =
            IdentityAdoptionDeviceKey(ORIGIN, encode(publicBytes(EcKeySupport.generateKeyPair(EcCurve.P256).public as ECPublicKey)))
        assertFalse(IdentityAdoptionDeviceProof.verify(OPERATION, DECISION, CHALLENGE, otherKey, signature))
    }

    @Test
    fun invalidKeysProofsAndOriginsFailClosed() {
        val generator = HexFormat.of().parseHex(GENERATOR_HEX)
        val good = IdentityAdoptionDeviceKey(ORIGIN, encode(generator))
        for (key in listOf(
            good.copy(publicKey = ""),
            good.copy(publicKey = encode(ByteArray(65))),
            good.copy(publicKey = encode(generator) + "="),
            good.copy(publicKey = encode(generator.copyOf(64))),
            good.copy(origin = "http://cloud.example"),
            good.copy(origin = "https://cloud.example/route"),
            good.copy(origin = "https://user@cloud.example"),
            good.copy(origin = "https://cloud.example?query"),
        )) {
            assertNull(IdentityAdoptionDeviceProof.message(OPERATION, DECISION, CHALLENGE, key))
        }
        for (value in listOf("", "a".repeat(42), "a".repeat(44), "+".repeat(43))) {
            assertNull(IdentityAdoptionDeviceProof.message(OPERATION, DECISION, value, good))
        }
        assertNull(IdentityAdoptionDeviceProof.message(OPERATION.copy(revision = 0), DECISION, CHALLENGE, good))
        for (signature in listOf("", "a".repeat(85), "a".repeat(87), "a".repeat(86), "+".repeat(86))) {
            assertFalse(IdentityAdoptionDeviceProof.verify(OPERATION, DECISION, CHALLENGE, good, signature))
        }
    }

    @Test
    fun verifiesTheNativeCryptoKitSyntheticConfirmation() {
        val key = IdentityAdoptionDeviceKey(ORIGIN, encode(HexFormat.of().parseHex(GENERATOR_HEX)))
        val signature = "ziuRRZWQUG2OeaUxulvZg46TJ2sdg90x0A2K34WYze2ULe0mi4CyoOmNb7XRDqL038GI6-AYaNCWsItCDT_k0A"
        assertTrue(IdentityAdoptionDeviceProof.verify(OPERATION, DECISION, CHALLENGE, key, signature))
    }

    @Test
    fun confirmationEncodingMatchesTheNativeContract() {
        val key = IdentityAdoptionDeviceKey(ORIGIN, encode(HexFormat.of().parseHex(GENERATOR_HEX)))
        val bytes = assertNotNull(IdentityAdoptionDeviceProof.message(OPERATION, DECISION, CHALLENGE, key))
        assertEquals(390, bytes.size)
        assertEquals(
            "475a16ce4eb68d8b69dfabc9a3ea9188bde203de42584a2b7b3e1bdcbdfac802",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        )
    }

    private fun publicBytes(key: ECPublicKey): ByteArray {
        fun fixed(value: java.math.BigInteger): ByteArray =
            value.toByteArray().takeLast(32).toByteArray().let {
                ByteArray(32 - it.size) +
                    it
            }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    companion object {
        private const val ORIGIN = "https://cloud.example"
        private val DECISION = Uuid.parse("00000000-0000-0000-0000-000000000003")
        private val CHALLENGE = "a".repeat(43)
        private const val GENERATOR_HEX =
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        private val OPERATION =
            IdentityAdoptionOperation(
                "00000000-0000-0000-0000-000000000001",
                IdentityAdoptionBinding(
                    Uuid.parse("00000000-0000-0000-0000-000000000002"),
                    "did:web:source.example",
                    "did:web:target.example",
                    "synthetic-device",
                    0,
                ),
                1,
                IdentityAdoptionPhase.PREPARING,
                Instant.parse("2026-10-30T00:00:00Z"),
            )
    }
}
