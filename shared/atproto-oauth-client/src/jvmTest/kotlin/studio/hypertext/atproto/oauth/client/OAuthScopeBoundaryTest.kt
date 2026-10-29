package studio.hypertext.atproto.oauth.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class OAuthScopeBoundaryTest {
    @Test
    fun `a serialized legacy pending attempt uses the configured permission limit`() =
        runTest {
            val harness = OAuthTestHarness()
            val atmosphere = harness.atmosphere
            try {
                val redirect = harness.client.authorize(atmosphere.alice.handle)
                val pending = assertNotNull(harness.stateStore.take(redirect.state))
                val fields = Json.encodeToJsonElement(OAuthAuthorizationState.serializer(), pending).jsonObject - "requestedScope"
                val legacy = Json.decodeFromJsonElement(OAuthAuthorizationState.serializer(), JsonObject(fields))
                assertNull(legacy.requestedScope)
                harness.stateStore.save(legacy, atmosphere.clock.now() + 1.minutes)
                val session = harness.client.callback(atmosphere.approve(redirect.url)).session
                assertEquals(harness.config.scope, session.tokenInfo().scope)
            } finally {
                harness.client.close()
                atmosphere.httpClient.close()
            }
        }

    @Test
    fun `a restarted callback refuses permissions beyond its individual request and revokes the new grant`() =
        runTest {
            val harness = OAuthTestHarness()
            val atmosphere = harness.atmosphere
            val redirect = harness.client.authorize(atmosphere.alice.handle, OAuthAuthorizeOptions(scope = "atproto"))
            atmosphere.grantedScopeOverride = "atproto transition:generic"
            val second = harness.newClientInstance()
            try {
                assertFailsWith<OAuthInvalidTokenResponseException> { second.callback(atmosphere.approve(redirect.url)) }
                assertNull(harness.sessionStore.get(AtprotoDid.require(atmosphere.alice.did)))
                assertTrue(atmosphere.grantsFor(atmosphere.alice.did).single().revoked)
            } finally {
                second.close()
                harness.client.close()
                atmosphere.httpClient.close()
            }
        }

    @Test
    fun `rejecting an expanded new grant preserves an existing session`() =
        runTest {
            val harness = OAuthTestHarness()
            val atmosphere = harness.atmosphere
            try {
                val did = harness.signIn().session.did
                val original = harness.sessionStore.get(did)
                val redirect = harness.client.authorize(atmosphere.alice.handle, OAuthAuthorizeOptions(scope = "atproto"))
                atmosphere.grantedScopeOverride = "atproto transition:generic"
                assertFailsWith<OAuthInvalidTokenResponseException> { harness.client.callback(atmosphere.approve(redirect.url)) }
                assertEquals(original, harness.sessionStore.get(did))
                assertEquals(listOf(false, true), atmosphere.grantsFor(did.toString()).map { it.revoked })
            } finally {
                harness.client.close()
                atmosphere.httpClient.close()
            }
        }

    @Test
    fun `refresh cannot expand a session beyond its previously granted permissions`() =
        runTest {
            val harness = OAuthTestHarness()
            val atmosphere = harness.atmosphere
            try {
                val did = harness.signIn(options = OAuthAuthorizeOptions(scope = "atproto")).session.did
                atmosphere.grantedScopeOverride = "atproto transition:generic"
                atmosphere.clock.advance(6.minutes)
                assertFailsWith<OAuthSessionRevokedException> { harness.client.restore(did) }
                assertNull(harness.sessionStore.get(did))
                assertTrue(atmosphere.grantsFor(did.toString()).single().revoked)
            } finally {
                harness.client.close()
                atmosphere.httpClient.close()
            }
        }

    @Test
    fun `the provider may grant fewer permissions than requested`() =
        runTest {
            val harness = OAuthTestHarness()
            val atmosphere = harness.atmosphere
            atmosphere.grantedScopeOverride = "atproto"
            try {
                val session = harness.signIn().session
                assertEquals("atproto", session.tokenInfo().scope)
                atmosphere.clock.advance(6.minutes)
                assertEquals(
                    "atproto",
                    harness.client
                        .restore(session.did)
                        .tokenInfo()
                        .scope,
                )
            } finally {
                harness.client.close()
                atmosphere.httpClient.close()
            }
        }
}
