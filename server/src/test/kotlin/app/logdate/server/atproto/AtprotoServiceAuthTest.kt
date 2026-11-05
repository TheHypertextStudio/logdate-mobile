package app.logdate.server.atproto

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.crypto.EcKeySupport
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AtprotoServiceAuthTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)
    private val clock =
        object : Clock {
            override fun now(): Instant = this@AtprotoServiceAuthTest.now
        }

    @Test
    fun `issues a short lived method bound token verifiable by the account signing key`() =
        runBlocking {
            val fixture = fixture()
            val token = fixture.service.issue(fixture.account, AUDIENCE, METHOD)
            val (header, payload, signature) = token.split('.')
            val decoder = Base64.getUrlDecoder()
            val claims = Json.parseToJsonElement(decoder.decode(payload).decodeToString()).jsonObject
            val key = EcKeySupport.decodePublicKey(requireNotNull(fixture.account.signingKeyPublic))
            val jwtHeader = Json.parseToJsonElement(decoder.decode(header).decodeToString()).jsonObject
            assertEquals("JWT", jwtHeader["typ"]?.jsonPrimitive?.content)
            assertEquals(key.curve.jwsAlgorithm, jwtHeader["alg"]?.jsonPrimitive?.content)
            assertEquals(fixture.account.did, claims["iss"]?.jsonPrimitive?.content)
            assertEquals(AUDIENCE, claims["aud"]?.jsonPrimitive?.content)
            assertEquals(METHOD, claims["lxm"]?.jsonPrimitive?.content)
            assertEquals((now.epochSeconds + 60).toString(), claims["exp"]?.jsonPrimitive?.content)
            val validSignature =
                EcKeySupport.verifySha256(key.publicKey, key.curve, "$header.$payload".encodeToByteArray(), decoder.decode(signature))
            assertTrue(validSignature)
            assertNotEquals(token, fixture.service.issue(fixture.account, AUDIENCE, METHOD))
        }

    @Test
    fun `refuses private or stale identities and rejects invalid bounds`() =
        runBlocking {
            val fixture = fixture()
            for (expiration in listOf(now.epochSeconds, now.epochSeconds + 61)) {
                assertEquals(
                    "BadExpiration",
                    assertFailsWith<ServiceAuthException> {
                        fixture.service.issue(fixture.account, AUDIENCE, METHOD, expiration)
                    }.code,
                )
            }
            assertFailsWith<ServiceAuthException> { fixture.service.issue(fixture.account, "https://example.com", METHOD) }
            assertFailsWith<ServiceAuthException> { fixture.service.issue(fixture.account, AUDIENCE, "not a method") }
            val unpublished = fixture(published = false)
            assertFailsWith<ServiceAuthException> { unpublished.service.issue(unpublished.account, AUDIENCE, METHOD) }
            fixture.accounts.save(fixture.account.copy(did = "did:web:replacement.example"))
            assertFailsWith<ServiceAuthException> { fixture.service.issue(fixture.account, AUDIENCE, METHOD) }
            Unit
        }

    private suspend fun fixture(published: Boolean = true): Fixture {
        val accounts = InMemoryAccountRepository()
        val keys = SigningKeyService(InMemorySigningKeyRepository(), "service-auth-test")
        val id = Uuid.random()
        val key = keys.ensureActiveKey(id)
        val account =
            Account(
                id = id,
                username = "alice",
                displayName = "Alice",
                createdAt = now,
                did = "did:web:alice.example",
                handle = "alice.example",
                signingKeyPublic = key.publicKeyMultibase,
            )
        accounts.save(account)
        if (published) check(accounts.recordPublicIdentity(id, requireNotNull(account.did)))
        return Fixture(account, accounts, AtprotoServiceAuth(accounts, keys, clock))
    }

    private data class Fixture(
        val account: Account,
        val accounts: InMemoryAccountRepository,
        val service: AtprotoServiceAuth,
    )

    private companion object {
        private const val AUDIENCE = "did:web:api.bsky.app#bsky_appview"
        private const val METHOD = "app.bsky.actor.getProfile"
    }
}
