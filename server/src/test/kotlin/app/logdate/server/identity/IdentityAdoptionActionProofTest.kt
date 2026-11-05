@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionActionProof
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

class IdentityAdoptionActionProofTest {
    @Test
    fun theDeviceSignsTheExactActionAndRequestBody() {
        val signed = sign()
        assertTrue(IdentityAdoptionActionProof.verify(operation, challenge, signed.first, signed.second))
        for (other in listOf(
            challenge.copy(id = Uuid.random()),
            challenge.copy(nonce = "b".repeat(43)),
            challenge.copy(action = IdentityAdoptionAction.CANCEL),
            challenge.copy(bodyDigest = "b".repeat(64)),
        )) {
            assertFalse(IdentityAdoptionActionProof.verify(operation, other, signed.first, signed.second))
        }
    }

    @Test
    fun everyAuthorityFieldAndCurrentRevisionRemainBound() {
        val signed = sign()
        val b = operation.binding
        for (other in listOf(
            operation.copy(id = Uuid.random().toString()),
            operation.copy(revision = 2),
            operation.copy(binding = b.copy(accountId = Uuid.random())),
            operation.copy(binding = b.copy(sourceDid = "did:web:other.example")),
            operation.copy(binding = b.copy(targetDid = "did:web:other.example")),
            operation.copy(binding = b.copy(sourceEpoch = 1)),
            operation.copy(binding = b.copy(deviceId = "other-device")),
        )) {
            assertFalse(IdentityAdoptionActionProof.verify(other, challenge, signed.first, signed.second))
        }
        assertFalse(
            IdentityAdoptionActionProof.verify(operation, challenge, signed.first.copy(origin = "https://other.example"), signed.second),
        )
    }

    @Test
    fun confirmationSignaturesCannotAuthorizeAnAction() {
        val pair = EcKeySupport.generateKeyPair(EcCurve.P256)
        val key = key(pair.public as ECPublicKey)
        val confirmation = assertNotNull(IdentityAdoptionDeviceProof.message(operation, challenge.id, challenge.nonce, key))
        val signature = encode(EcKeySupport.signSha256(pair.private, EcCurve.P256, confirmation))
        assertFalse(IdentityAdoptionActionProof.verify(operation, challenge, key, signature))
        val payload = assertNotNull(IdentityAdoptionActionProof.message(operation, challenge, key))
        assertFalse(
            IdentityAdoptionDeviceProof.verify(
                operation,
                challenge.id,
                challenge.nonce,
                key,
                encode(EcKeySupport.signSha256(pair.private, EcCurve.P256, payload)),
            ),
        )
    }

    @Test
    fun actionPhasesAndDigestEncodingAreStrict() {
        val key = sign().first
        for (other in listOf(
            challenge.copy(bodyDigest = "A".repeat(64)),
            challenge.copy(bodyDigest = "a".repeat(63)),
            challenge.copy(nonce = "+".repeat(43)),
        )) {
            assertNull(IdentityAdoptionActionProof.message(operation, other, key))
        }
        assertNull(IdentityAdoptionActionProof.message(operation, challenge.copy(action = IdentityAdoptionAction.COMMIT), key))
        assertNull(IdentityAdoptionActionProof.message(operation.copy(phase = IdentityAdoptionPhase.COMMITTED), challenge, key))
        val held = operation.copy(phase = IdentityAdoptionPhase.FENCED)
        for (action in listOf(
            IdentityAdoptionAction.RENEW,
            IdentityAdoptionAction.STAGE_RECORDS,
            IdentityAdoptionAction.BEGIN_BLOB,
            IdentityAdoptionAction.FINALIZE_BLOB,
            IdentityAdoptionAction.VERIFY,
        )) {
            assertNotNull(IdentityAdoptionActionProof.message(held, challenge.copy(action = action), key))
            if (action == IdentityAdoptionAction.STAGE_RECORDS ||
                action == IdentityAdoptionAction.BEGIN_BLOB ||
                action == IdentityAdoptionAction.FINALIZE_BLOB
            ) {
                assertNotNull(IdentityAdoptionActionProof.message(operation, challenge.copy(action = action), key))
            } else {
                assertNull(IdentityAdoptionActionProof.message(operation, challenge.copy(action = action), key))
            }
        }
        val read = challenge.copy(action = IdentityAdoptionAction.READ_STAGED)
        assertNotNull(IdentityAdoptionActionProof.message(held, read, key))
        assertNotNull(IdentityAdoptionActionProof.message(operation, read, key))
        val verified = operation.copy(phase = IdentityAdoptionPhase.VERIFIED)
        assertNotNull(IdentityAdoptionActionProof.message(verified, read, key))
        assertNotNull(IdentityAdoptionActionProof.message(verified, challenge.copy(action = IdentityAdoptionAction.COMMIT), key))
        assertNotNull(IdentityAdoptionActionProof.message(verified, challenge.copy(action = IdentityAdoptionAction.CANCEL), key))
        assertNull(IdentityAdoptionActionProof.message(verified, challenge.copy(action = IdentityAdoptionAction.STAGE_RECORDS), key))
    }

    @Test
    fun acceptsTheNativeCryptoKitActionContract() {
        val key =
            IdentityAdoptionDeviceKey(
                "https://cloud.example",
                "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU",
            )
        val fixed = challenge.copy(id = Uuid.parse("00000000-0000-0000-0000-000000000003"))
        val payload = assertNotNull(IdentityAdoptionActionProof.message(operation, fixed, key))
        assertEquals(522, payload.size)
        assertEquals(
            "e5cf336ec26381a062d4d74ff4c6b511be8b33e4257429978f42f5076199a394",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)),
        )
        val signature = "F6Q56KuT2W12eHWZDd4GHy6-qXZxzvy8JI3C7VBY2RcDyCJLnWYgqC0Ec1zR8sP0yUgPrauzBhgLKBIJAvWjNw"
        assertTrue(IdentityAdoptionActionProof.verify(operation, fixed, key, signature))
        assertFalse(IdentityAdoptionDeviceProof.verify(operation, fixed.id, fixed.nonce, key, signature))
    }

    private fun sign(): Pair<IdentityAdoptionDeviceKey, String> {
        val pair = EcKeySupport.generateKeyPair(EcCurve.P256)
        val key = key(pair.public as ECPublicKey)
        val payload = assertNotNull(IdentityAdoptionActionProof.message(operation, challenge, key))
        return key to encode(EcKeySupport.signSha256(pair.private, EcCurve.P256, payload))
    }

    private fun key(point: ECPublicKey): IdentityAdoptionDeviceKey {
        fun fixed(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray().takeLast(32).toByteArray()
            return ByteArray(32 - bytes.size) + bytes
        }
        return IdentityAdoptionDeviceKey("https://cloud.example", encode(byteArrayOf(4) + fixed(point.w.affineX) + fixed(point.w.affineY)))
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        private val challenge = IdentityAdoptionActionChallenge(Uuid.random(), "a".repeat(43), IdentityAdoptionAction.HOLD, "a".repeat(64))
        private val operation =
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
