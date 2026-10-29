@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.bluesky.connection.AtprotoDisconnectPasskeys
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.InMemoryPasskeyRepository
import app.logdate.server.passkeys.PasskeyRepository
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PostgreSQLAtprotoDisconnectPasskeysTest {
    @Test
    fun `action challenges survive a server instance and remain single use`() =
        withBlueskyOAuthDatabase {
            val keys = InMemoryPasskeyRepository()
            val one = service(keys)
            val owner = Uuid.random()
            val key = PasskeyTestAuthenticator()
            key.registerWith(one, owner, "owner")
            val challenge = AtprotoDisconnectPasskeys(one).begin(owner).challenge
            val proof = PasskeyStepUpProof(challenge, key.assert(challenge))
            assertTrue(AtprotoDisconnectPasskeys(service(keys)).verify(owner, proof))
            assertFalse(AtprotoDisconnectPasskeys(one).verify(owner, proof))
        }

    @Test
    fun `persisted expired action challenges cannot authorize disconnect`() =
        withBlueskyOAuthDatabase { source ->
            val one = service(InMemoryPasskeyRepository())
            val owner = Uuid.random()
            val key = PasskeyTestAuthenticator()
            key.registerWith(one, owner, "owner")
            val disconnect = AtprotoDisconnectPasskeys(one)
            val challenge = disconnect.begin(owner).challenge
            source.blueskyTransaction {
                prepareStatement(
                    "UPDATE webauthn_challenges SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE challenge = ?",
                ).use {
                    it.setString(1, "passkey:$challenge")
                    it.executeUpdate()
                }
            }
            assertFalse(disconnect.verify(owner, PasskeyStepUpProof(challenge, key.assert(challenge))))
        }

    @Test
    fun `persisted action challenges cannot be substituted for another account`() =
        withBlueskyOAuthDatabase {
            val one = service(InMemoryPasskeyRepository())
            val owner = Uuid.random()
            val key = PasskeyTestAuthenticator()
            key.registerWith(one, owner, "owner")
            val disconnect = AtprotoDisconnectPasskeys(one)
            val challenge = disconnect.begin(owner).challenge
            assertFalse(disconnect.verify(Uuid.random(), PasskeyStepUpProof(challenge, key.assert(challenge))))
            assertTrue(disconnect.verify(owner, PasskeyStepUpProof(challenge, key.assert(challenge))))
        }

    private fun service(keys: PasskeyRepository) =
        WebAuthnPasskeyService(
            passkeyRepository = keys,
            relyingPartyId = "logdate.app",
            origins = setOf("https://logdate.app"),
            strictVerificationEnabled = true,
            challengeRepository = PostgreSQLPasskeyChallengeRepository(),
        )
}
