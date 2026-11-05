@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionPasskeys
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.InMemoryPasskeyRepository
import app.logdate.server.passkeys.PasskeyRepository
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IdentityAdoptionPasskeysPostgresTest {
    @Test
    fun aDecisionChallengeSurvivesAnInstanceAndRemainsSingleUse() =
        withBlueskyOAuthDatabase {
            val keys = InMemoryPasskeyRepository()
            val web = service(keys)
            val account = Uuid.random()
            val decision = Uuid.random()
            val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
            val challenge = IdentityAdoptionPasskeys(web).begin(account, decision).challenge
            val proof = PasskeyStepUpProof(challenge, key.assert(challenge))
            assertTrue(IdentityAdoptionPasskeys(service(keys)).verify(account, decision, proof))
            assertFalse(IdentityAdoptionPasskeys(web).verify(account, decision, proof))
        }

    @Test
    fun anExpiredDecisionChallengeCannotBeAuthorized() =
        withBlueskyOAuthDatabase { source ->
            val web = service(InMemoryPasskeyRepository())
            val account = Uuid.random()
            val decision = Uuid.random()
            val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
            val action = IdentityAdoptionPasskeys(web)
            val challenge = action.begin(account, decision).challenge
            source.blueskyTransaction {
                prepareStatement(
                    "UPDATE webauthn_challenges SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE challenge = ?",
                ).use {
                    it.setString(1, "passkey:$challenge")
                    check(it.executeUpdate() == 1)
                }
            }
            assertFalse(action.verify(account, decision, PasskeyStepUpProof(challenge, key.assert(challenge))))
        }

    @Test
    fun aStaleDecisionCannotConsumeAReplacementDecision() =
        withBlueskyOAuthDatabase {
            val web = service(InMemoryPasskeyRepository())
            val account = Uuid.random()
            val decision = Uuid.random()
            val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
            val action = IdentityAdoptionPasskeys(web)
            val challenge = action.begin(account, decision).challenge
            val proof = PasskeyStepUpProof(challenge, key.assert(challenge))
            assertFalse(IdentityAdoptionPasskeys(service(InMemoryPasskeyRepository())).verify(account, Uuid.random(), proof))
            assertTrue(action.verify(account, decision, proof))
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
