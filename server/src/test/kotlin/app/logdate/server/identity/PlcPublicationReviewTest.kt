package app.logdate.server.identity

import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.plc.PlcEncoding
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcOperations
import studio.hypertext.atproto.plc.PlcUnsignedOperation
import java.math.BigInteger
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlcPublicationReviewTest {
    private val signer = EcKeySupport.generateKeyPair(EcCurve.P256)
    private val recovery = EcKeySupport.generateKeyPair(EcCurve.P256)
    private val serverRotation = EcKeySupport.generateKeyPair(EcCurve.P256)
    private val signingKey = didKey(signer)
    private val recoveryKey = didKey(recovery)
    private val rotationKey = didKey(serverRotation)
    private val genesis = sign(PlcOperations.atprotoGenesis(HANDLE, PDS, signingKey), signer)
    private val did = PlcEncoding.deriveDid(genesis)
    private val transition = update(listOf(recoveryKey, rotationKey))

    @Test
    fun `accepts a legacy chain with a dedicated final rotation key and only resumes an exact prefix`() {
        val review = prepare(listOf(transition, genesis))

        assertEquals(listOf(PlcEncoding.operationCid(genesis), PlcEncoding.operationCid(transition)), review.operationCids)
        assertEquals(PlcPublicationProgress.Next(genesis), review.reconcile(emptyList()))
        assertEquals(PlcPublicationProgress.Next(transition), review.reconcile(listOf(index(genesis))))
        assertEquals(PlcPublicationProgress.Confirmed, review.reconcile(listOf(index(genesis), index(transition))))
        assertEquals(PlcPublicationProgress.Conflict, review.reconcile(listOf(index(genesis).copy(nullified = true))))
        assertEquals(PlcPublicationProgress.Conflict, review.reconcile(listOf(index(transition))))
        assertEquals(PlcPublicationProgress.Conflict, review.reconcile(listOf(index(genesis), index(update(listOf(rotationKey))))))
    }

    @Test
    fun `discloses recovery changes from the exact chain and no private key material`() {
        assertEquals(PlcPublicationHistory(1, signingChanges = 0, recoveryChanges = 1), prepare(listOf(transition, genesis)).history)
    }

    @Test
    fun `reports no earlier changes for an account created with its final keys`() {
        val first = sign(PlcOperations.atprotoGenesis(HANDLE, PDS, signingKey, listOf(recoveryKey, rotationKey)), recovery)
        val review =
            PlcPublicationReview.prepare(
                PlcEncoding.deriveDid(first),
                HANDLE,
                PDS,
                signingKey,
                recoveryKey,
                rotationKey,
                listOf(first),
            )
        assertEquals(PlcPublicationHistory(0, signingChanges = 0, recoveryChanges = 0), review.history)
    }

    @Test
    fun `rejects an unreviewed handle key order or signer and a fork in the private log`() {
        assertFailsWith<IllegalArgumentException> { prepare(listOf(genesis)) }
        assertFailsWith<IllegalArgumentException> { prepare(listOf(genesis, update(listOf(rotationKey, recoveryKey)))) }
        assertFailsWith<IllegalArgumentException> { prepare(listOf(genesis, update(listOf(recoveryKey, signingKey)))) }
        assertFailsWith<IllegalArgumentException> { prepare(listOf(genesis, update(listOf(recoveryKey, rotationKey), recovery))) }
        assertFailsWith<IllegalArgumentException> {
            prepare(listOf(genesis, update(listOf(recoveryKey, rotationKey), handle = "other.logdate.app")))
        }
        assertFailsWith<IllegalArgumentException> { prepare(listOf(genesis, transition, update(listOf(rotationKey)))) }
    }

    @Test
    fun `rejects a noncanonical signature before asking for publication consent`() {
        val signature = Base64.getUrlDecoder().decode(requireNotNull(transition.sig))
        val componentSize = signature.size / 2
        val order = (signer.public as ECPublicKey).params.order
        val lowS = BigInteger(1, signature.copyOfRange(componentSize, signature.size))
        val highS =
            order
                .subtract(lowS)
                .toByteArray()
                .takeLast(componentSize)
                .toByteArray()
        val noncanonical = signature.copyOfRange(0, componentSize) + ByteArray(componentSize - highS.size) + highS
        val operation = transition.copy(sig = Base64.getUrlEncoder().withoutPadding().encodeToString(noncanonical))

        assertFailsWith<IllegalArgumentException> { prepare(listOf(genesis, operation)) }
    }

    private fun prepare(operations: List<PlcOperation>) =
        PlcPublicationReview.prepare(did, HANDLE, PDS, signingKey, recoveryKey, rotationKey, operations)

    private fun update(
        keys: List<String>,
        key: KeyPair = signer,
        handle: String = HANDLE,
    ): PlcOperation = sign(PlcOperations.atprotoUpdate(PlcEncoding.operationCid(genesis), handle, PDS, signingKey, keys), key)

    private fun index(operation: PlcOperation) =
        PlcIndexedOperation(did, operation, PlcEncoding.operationCid(operation), false, "2026-11-05T00:00:00Z")

    private fun didKey(pair: KeyPair): String = "did:key:" + EcKeySupport.encodePublicKeyMultibase(pair.public as ECPublicKey, EcCurve.P256)

    private fun sign(
        operation: PlcUnsignedOperation,
        pair: KeyPair,
    ): PlcOperation =
        operation.signed(
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                EcKeySupport.signSha256(pair.private, EcCurve.P256, PlcEncoding.encodeUnsigned(operation)),
            ),
        )

    private companion object {
        const val HANDLE = "alex.logdate.app"
        const val PDS = "https://cloud.logdate.app"
    }
}
