@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class PostgreSQLAtprotoReconnectTest {
    @Test
    fun `reconnect proof is isolated from sign in and account substitution across restart`() =
        withBlueskyOAuthDatabase { source ->
            val owner = account()
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val one = PostgreSQLBlueskySignInAttemptStore(source, cipher)
            one.createReconnect(id, challenge, Clock.System.now() + 5.minutes, owner.id, did)
            assertTrue(one.complete(id, did))
            val two = PostgreSQLBlueskySignInAttemptStore(source, cipher)
            assertNull(two.consume(id, verifier), "Reconnect must never enter enrollment")
            assertNull(two.consumeReconnect(id, verifier, Uuid.random(), did))
            assertNull(two.consumeReconnect(id, verifier, owner.id, otherDid))
            assertNull(two.consumeReconnect(id, "w".repeat(64), owner.id, did))
            assertEquals(did, assertNotNull(two.consumeReconnect(id, verifier, owner.id, did)).did)
            assertNull(one.consumeReconnect(id, verifier, owner.id, did))
        }

    @Test
    fun `callback rejects the wrong DID before accepting a reconnect identity`() =
        withBlueskyOAuthDatabase { source ->
            val owner = account()
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher)
            store.createReconnect(id, challenge, Clock.System.now() + 5.minutes, owner.id, did)
            assertFalse(store.complete(id, otherDid))
            assertTrue(store.complete(id, did))
        }

    @Test
    fun `removed account rejects callback and completion without consuming other purposes`() =
        withBlueskyOAuthDatabase { source ->
            val owner = account()
            val accounts = PostgreSQLAccountRepository()
            assertTrue(accounts.create(owner))
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher)
            store.createReconnect(id, challenge, Clock.System.now() + 5.minutes, owner.id, did)
            accounts.deactivateAccount(owner.id)
            assertFalse(store.complete(id, did))
            assertNull(store.consumeReconnect(id, verifier, owner.id, did))
        }

    @Test
    fun `ordinary sign in cannot be consumed as a reconnect and stays usable for sign in`() =
        withBlueskyOAuthDatabase { source ->
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher)
            store.create(id, challenge, Clock.System.now() + 5.minutes)
            assertTrue(store.complete(id, did))
            assertNull(store.consumeReconnect(id, verifier, Uuid.random(), did))
            assertEquals(did, assertNotNull(store.consume(id, verifier)).did)
        }

    @Test
    fun `account removed after callback cannot complete a reconnect`() =
        withBlueskyOAuthDatabase { source ->
            val owner = account()
            val accounts = PostgreSQLAccountRepository()
            assertTrue(accounts.create(owner))
            val store = PostgreSQLBlueskySignInAttemptStore(source, cipher)
            store.createReconnect(id, challenge, Clock.System.now() + 5.minutes, owner.id, did)
            assertTrue(store.complete(id, did))
            accounts.deactivateAccount(owner.id)
            assertNull(store.consumeReconnect(id, verifier, owner.id, did))
        }

    private fun account() =
        Account(
            Uuid.random(),
            "reconnect_owner",
            "Reconnect Owner",
            did = did.toString(),
            createdAt = Clock.System.now(),
            identityKind = AccountIdentityKind.EXTERNAL,
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
        )

    private companion object {
        val did = AtprotoDid.require("did:plc:" + "a".repeat(24))
        val otherDid = AtprotoDid.require("did:plc:" + "b".repeat(24))
        val id = "a".repeat(43)
        val verifier = "v".repeat(64)
        val challenge =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
            )
        val cipher = AtRestKeyCipher("synthetic-reconnect-encryption-key")
    }
}
