@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity

import app.logdate.server.bluesky.connection.AtprotoDisconnectPasskeys
import app.logdate.server.identity.adoption.IdentityAdoptionPasskeys
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.passkeys.WebAuthnPasskeyStepUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IdentityAdoptionPasskeysTest {
    @Test
    fun freshRegisteredPasskeyAuthorizesTheExactDecisionOnlyOnce() {
        val web = service()
        val account = Uuid.random()
        val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
        val action = IdentityAdoptionPasskeys(web)
        val decision = Uuid.random()
        val options = action.begin(account, decision)
        assertEquals(listOf(key.credentialIdB64), options.allowCredentials)
        val proof = PasskeyStepUpProof(options.challenge, key.assert(options.challenge))
        assertTrue(action.verify(account, decision, proof))
        assertFalse(action.verify(account, decision, proof))
    }

    @Test
    fun anotherDecisionCannotConsumeOrBorrowTheChallenge() {
        val web = service()
        val account = Uuid.random()
        val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
        val action = IdentityAdoptionPasskeys(web)
        val decision = Uuid.random()
        val challenge = action.begin(account, decision).challenge
        val proof = PasskeyStepUpProof(challenge, key.assert(challenge))
        assertFalse(action.verify(account, Uuid.random(), proof))
        assertTrue(action.verify(account, decision, proof))
    }

    @Test
    fun ordinarySignInStepUpAndDisconnectCannotAuthorizeAdoption() {
        val web = service()
        val account = Uuid.random()
        val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
        val action = IdentityAdoptionPasskeys(web)
        val challenges =
            listOf(
                web.generateAuthenticationOptions(account).challenge,
                web.generateStepUpOptions(account).challenge,
                AtprotoDisconnectPasskeys(web).begin(account).challenge,
                web.issueStepUpGrant(account),
            )
        for (challenge in challenges) {
            assertFalse(action.verify(account, Uuid.random(), PasskeyStepUpProof(challenge, key.assert(challenge))))
        }
    }

    @Test
    fun adoptionProofCannotBeSpentForSignInRecoveryOrDisconnect() {
        val web = service()
        val account = Uuid.random()
        val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
        val action = IdentityAdoptionPasskeys(web)
        val decision = Uuid.random()
        val challenge = action.begin(account, decision).challenge
        val proof = PasskeyStepUpProof(challenge, key.assert(challenge))
        assertFalse(WebAuthnPasskeyStepUp(web).verify(account, proof))
        assertFalse(AtprotoDisconnectPasskeys(web).verify(account, proof))
        assertFalse(web.redeemStepUpGrant(account, challenge))
        assertTrue(action.verify(account, decision, proof))
    }

    @Test
    fun anotherAccountsRegisteredPasskeyIsRefusedAndTheChallengeIsSpent() {
        val web = service()
        val account = Uuid.random()
        val ownerKey = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
        val foreignKey = PasskeyTestAuthenticator().also { it.registerWith(web, Uuid.random(), "other") }
        val action = IdentityAdoptionPasskeys(web)
        val decision = Uuid.random()
        val challenge = action.begin(account, decision).challenge
        assertFalse(action.verify(account, decision, PasskeyStepUpProof(challenge, foreignKey.assert(challenge))))
        assertFalse(action.verify(account, decision, PasskeyStepUpProof(challenge, ownerKey.assert(challenge))))
    }

    @Test
    fun tamperedAssertionIsRefusedAndSpent() {
        val web = service()
        val account = Uuid.random()
        val key = PasskeyTestAuthenticator().also { it.registerWith(web, account, "synthetic") }
        val action = IdentityAdoptionPasskeys(web)
        val decision = Uuid.random()
        val challenge = action.begin(account, decision).challenge
        assertFalse(action.verify(account, decision, PasskeyStepUpProof(challenge, key.assert(challenge, tamperSignature = true))))
        assertFalse(action.verify(account, decision, PasskeyStepUpProof(challenge, key.assert(challenge))))
    }

    @Test
    fun anAccountWithoutARegisteredPasskeyCannotStartTheCeremony() {
        assertFailsWith<IllegalStateException> { IdentityAdoptionPasskeys(service()).begin(Uuid.random(), Uuid.random()) }
    }

    @Test
    fun lightweightVerificationCannotBeUsedForIdentityAdoption() {
        val web =
            WebAuthnPasskeyService(
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = false,
            )
        assertFailsWith<IllegalStateException> { IdentityAdoptionPasskeys(web) }
    }

    private fun service() =
        WebAuthnPasskeyService(
            relyingPartyId = "logdate.app",
            origins = setOf("https://logdate.app"),
            strictVerificationEnabled = true,
        )
}
