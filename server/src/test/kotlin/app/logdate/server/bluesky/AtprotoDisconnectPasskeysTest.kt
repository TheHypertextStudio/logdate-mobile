@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky

import app.logdate.server.bluesky.connection.AtprotoDisconnectPasskeys
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AtprotoDisconnectPasskeysTest {
    @Test
    fun `an account can authorize disconnect once with a fresh registered passkey`() {
        val service = service()
        val owner = Uuid.random()
        val key = PasskeyTestAuthenticator()
        key.registerWith(service, owner, "owner")
        val disconnect = AtprotoDisconnectPasskeys(service)
        val challenge = disconnect.begin(owner).challenge
        val proof = PasskeyStepUpProof(challenge, key.assert(challenge))
        assertTrue(disconnect.verify(owner, proof))
        assertFalse(disconnect.verify(owner, proof))
    }

    @Test
    fun `disconnect lists only the current accounts registered credentials`() {
        val service = service()
        val owner = Uuid.random()
        val key = PasskeyTestAuthenticator()
        key.registerWith(service, owner, "owner")
        PasskeyTestAuthenticator().registerWith(service, Uuid.random(), "other")
        assertEquals(listOf(key.credentialIdB64), AtprotoDisconnectPasskeys(service).begin(owner).allowCredentials)
    }

    @Test
    fun `ordinary sign in and ordinary step up challenges cannot authorize disconnect`() {
        val service = service()
        val owner = Uuid.random()
        val key = PasskeyTestAuthenticator()
        key.registerWith(service, owner, "owner")
        val disconnect = AtprotoDisconnectPasskeys(service)
        for (challenge in listOf(service.generateAuthenticationOptions(owner).challenge, service.generateStepUpOptions(owner).challenge)) {
            assertFalse(disconnect.verify(owner, PasskeyStepUpProof(challenge, key.assert(challenge))))
        }
    }

    @Test
    fun `disconnect challenges cannot be spent through ordinary step up`() {
        val service = service()
        val owner = Uuid.random()
        val key = PasskeyTestAuthenticator()
        key.registerWith(service, owner, "owner")
        val challenge = AtprotoDisconnectPasskeys(service).begin(owner).challenge
        assertFalse(
            app.logdate.server.passkeys
                .WebAuthnPasskeyStepUp(service)
                .verify(owner, PasskeyStepUpProof(challenge, key.assert(challenge))),
        )
    }

    @Test
    fun `a registered passkey from another account cannot answer the owners action challenge`() {
        val service = service()
        val owner = Uuid.random()
        PasskeyTestAuthenticator().registerWith(service, owner, "owner")
        val foreignKey = PasskeyTestAuthenticator()
        foreignKey.registerWith(service, Uuid.random(), "other")
        val disconnect = AtprotoDisconnectPasskeys(service)
        val challenge = disconnect.begin(owner).challenge
        assertFalse(disconnect.verify(owner, PasskeyStepUpProof(challenge, foreignKey.assert(challenge))))
    }

    @Test
    fun `a tampered action assertion is refused and consumed`() {
        val service = service()
        val owner = Uuid.random()
        val key = PasskeyTestAuthenticator()
        key.registerWith(service, owner, "owner")
        val disconnect = AtprotoDisconnectPasskeys(service)
        val challenge = disconnect.begin(owner).challenge
        assertFalse(disconnect.verify(owner, PasskeyStepUpProof(challenge, key.assert(challenge, tamperSignature = true))))
        assertFalse(disconnect.verify(owner, PasskeyStepUpProof(challenge, key.assert(challenge))))
    }

    private fun service() =
        WebAuthnPasskeyService(
            relyingPartyId = "logdate.app",
            origins = setOf("https://logdate.app"),
            strictVerificationEnabled = true,
        )
}
