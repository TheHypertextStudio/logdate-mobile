package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.PasskeyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLBlueskyAccountEnrollmentRepositoryTest {
    @Test
    fun `enrollment stores exactly one external account and verified passkey without hosted keys`() =
        withBlueskyOAuthDatabase { source ->
            val account = account("alice")
            val passkey = passkey("alice-passkey")
            assertTrue(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(account, passkey))
            val stored = assertNotNull(PostgreSQLAccountRepository().findById(account.id))
            assertEquals(account.did, stored.did)
            assertEquals(AccountIdentityKind.EXTERNAL, stored.identityKind)
            assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, stored.storageMode)
            assertNull(stored.signingKeyPublic)
            assertNull(stored.plcRecoveryDidKey)
            assertNull(stored.publicIdentityDid)
            val (owner, key) = assertNotNull(PostgreSQLPasskeyRepository().getPasskeyByCredentialId(passkey.credentialId))
            assertEquals(account.id, owner)
            assertContentEquals(passkey.publicKey, key.publicKey)
            assertEquals(passkey.passkey.nickname, key.info.nickname)
            assertEquals(
                account.id,
                PostgreSQLAccountIdentityRepository().findByProviderSubject(IdentityProvider.PASSKEY, passkey.credentialId)?.accountId,
            )
            source.connection.use { connection ->
                for (table in listOf("signing_keys", "hosted_plc_operations", "atproto_repo_heads")) {
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
        }

    @Test
    fun `concurrent enrollments cannot claim the same DID twice`() =
        withBlueskyOAuthDatabase { source ->
            val results =
                coroutineScope {
                    listOf("alice", "bob")
                        .map { name ->
                            async(
                                Dispatchers.IO,
                            ) { PostgreSQLBlueskyAccountEnrollmentRepository(source).create(account(name), passkey(name)) }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it })
            assertEquals(1, PostgreSQLAccountRepository().getAllAccounts().size)
        }

    @Test
    fun `a passkey collision rolls back the new account and preserves the original owner`() =
        withBlueskyOAuthDatabase { source ->
            val original = account("original").copy(did = "did:web:original.example.com")
            assertTrue(PostgreSQLAccountRepository().create(original))
            val key = passkey("same-credential")
            assertTrue(PostgreSQLPasskeyRepository().storePasskey(original.id, key.credentialId, key.publicKey, key.signCount, key.passkey))
            val incoming = account("incoming")
            assertFalse(
                PostgreSQLBlueskyAccountEnrollmentRepository(
                    source,
                ).create(incoming, key.copy(passkey = key.passkey.copy(id = Uuid.random()))),
            )
            assertNull(PostgreSQLAccountRepository().findById(incoming.id))
            assertEquals(original.id, PostgreSQLPasskeyRepository().getPasskeyByCredentialId(key.credentialId)?.first)
            assertNull(PostgreSQLAccountIdentityRepository().findByProviderSubject(IdentityProvider.PASSKEY, key.credentialId))
        }

    @Test
    fun `an identity collision rolls back both the account and passkey without relinking`() =
        withBlueskyOAuthDatabase { source ->
            val original = account("original").copy(did = "did:web:original.example.com")
            assertTrue(PostgreSQLAccountRepository().create(original))
            val key = passkey("claimed-identity")
            PostgreSQLAccountIdentityRepository().save(
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = original.id,
                    provider = IdentityProvider.PASSKEY,
                    providerSubject = key.credentialId,
                    createdAt = now,
                ),
            )
            val incoming = account("incoming")
            assertFalse(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(incoming, key))
            assertNull(PostgreSQLAccountRepository().findById(incoming.id))
            assertNull(PostgreSQLPasskeyRepository().getPasskeyByCredentialId(key.credentialId))
            assertEquals(
                original.id,
                PostgreSQLAccountIdentityRepository().findByProviderSubject(IdentityProvider.PASSKEY, key.credentialId)?.accountId,
            )
        }

    @Test
    fun `hosted or inconsistent enrollment data is rejected before writing`() =
        withBlueskyOAuthDatabase { source ->
            val repository = PostgreSQLBlueskyAccountEnrollmentRepository(source)
            val key = passkey("credential")
            assertFailsWith<IllegalArgumentException> {
                repository.create(
                    account("alice").copy(identityKind = AccountIdentityKind.HOSTED),
                    key,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.create(
                    account("alice").copy(storageMode = AccountStorageMode.LEGACY),
                    key,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.create(account("alice"), key.copy(passkey = key.passkey.copy(credentialId = "other")))
            }
            assertTrue(PostgreSQLAccountRepository().getAllAccounts().isEmpty())
        }

    @Test
    fun `a full size passkey credential remains a usable sign-in identity`() =
        withBlueskyOAuthDatabase { source ->
            val credential =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(ByteArray(1023) { 1 })
            val account = account("longcredential")
            assertTrue(PostgreSQLBlueskyAccountEnrollmentRepository(source).create(account, passkey(credential)))
            assertEquals(
                account.id,
                PostgreSQLAccountIdentityRepository().findByProviderSubject(IdentityProvider.PASSKEY, credential)?.accountId,
            )
        }

    private fun account(name: String) =
        Account(
            id = Uuid.random(),
            username = name,
            displayName = name,
            createdAt = now,
            lastSignInAt = now,
            did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
            identityKind = AccountIdentityKind.EXTERNAL,
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
        )

    private fun passkey(credential: String) =
        WebAuthnPasskeyService.VerifiedRegistration(
            credentialId = credential,
            publicKey = byteArrayOf(1, 2, 3),
            signCount = 0,
            passkey = PasskeyInfo(Uuid.random(), credential, "Test device", "platform", now, null),
        )

    private companion object {
        val now = Instant.parse("2026-10-25T00:00:00Z")
    }
}
