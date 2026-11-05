package app.logdate.server.database

import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountLinkEvent
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.sampleAccount
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.withAccountIdentityTables
import app.logdate.server.database.support.withH2Database
import app.logdate.server.identity.EncryptedPrivateKey
import app.logdate.server.identity.StoredSigningKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StoredAccountIdentityRepositoryTest {
    @Test
    fun `account identity repository supports upsert and query operations`() {
        withAccountIdentityTables {
            val repository = PostgreSQLAccountIdentityRepository()
            val now = Clock.System.now()
            val accountId = Uuid.random()
            val linkedAccountId = Uuid.random()
            PostgreSQLAccountRepository().save(sampleAccount(accountId, username = "identity-owner"))
            PostgreSQLAccountRepository().save(sampleAccount(linkedAccountId, username = "identity-owner-linked"))

            val identity =
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = accountId,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = "google-subject",
                    email = "identity@example.com",
                    emailVerified = true,
                    createdAt = now,
                    metadataJson = "{}",
                )

            val saved = repository.save(identity)
            assertEquals(identity.id, saved.id)

            val updated = repository.save(identity.copy(accountId = linkedAccountId, metadataJson = "{\"v\":2}"))
            assertEquals(saved.id, updated.id)

            val byProvider = repository.findByProviderSubject(IdentityProvider.GOOGLE, "google-subject")
            assertNotNull(byProvider)
            assertEquals("google-subject", byProvider.providerSubject)

            assertEquals(1, repository.findByAccountId(updated.accountId).size)
            assertEquals(1, repository.findByVerifiedEmail("identity@example.com").size)
            assertTrue(repository.touchLastSignIn(updated.id))
            assertFalse(repository.touchLastSignIn(Uuid.random()))

            val linkEvent =
                AccountLinkEvent(
                    id = Uuid.random(),
                    accountId = updated.accountId,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = "google-subject",
                    reason = "implicit-link",
                    createdAt = now,
                    metadataJson = "{}",
                )
            val persistedEvent = repository.saveLinkEvent(linkEvent)
            assertEquals(linkEvent.id, persistedEvent.id)
        }
    }

    @Test
    fun `signing key repository supports save lookup and revoke`() {
        withH2Database(AccountsTable, SigningKeysTable) {
            val accountId = Uuid.random()
            val accountRepository = PostgreSQLAccountRepository()
            val repository = PostgreSQLSigningKeyRepository()

            val stored =
                StoredSigningKey(
                    id = Uuid.random(),
                    accountId = accountId,
                    publicKeyMultibase = "zSigningKey",
                    privateKeyEncrypted = "encrypted",
                    createdAt = Clock.System.now(),
                )

            runBlocking {
                accountRepository.save(sampleAccount(accountId, username = "signing-owner"))
                repository.save(stored)
                assertEquals(stored.publicKeyMultibase, repository.findActiveByAccountId(accountId)?.publicKeyMultibase)
                assertEquals(1, repository.revokeActiveKeys(accountId))
                assertNull(repository.findActiveByAccountId(accountId))
            }
        }
    }

    @Test
    fun `signing key repository lists and replaces encrypted private keys only while unchanged`() {
        withH2Database(AccountsTable, SigningKeysTable) {
            val accountId = Uuid.random()
            val repository = PostgreSQLSigningKeyRepository()
            val stored =
                StoredSigningKey(
                    id = Uuid.random(),
                    accountId = accountId,
                    publicKeyMultibase = "zSigningKey",
                    privateKeyEncrypted = "encrypted-old",
                    createdAt = Clock.System.now(),
                )

            runBlocking {
                PostgreSQLAccountRepository().save(sampleAccount(accountId, username = "rewrap-owner"))
                repository.save(stored)

                assertEquals(
                    listOf(EncryptedPrivateKey(stored.id.toString(), "encrypted-old")),
                    repository.encryptedPrivateKeys(),
                )
                assertFalse(repository.replaceEncryptedPrivateKey(stored.id.toString(), "stale", "encrypted-new"))
                assertTrue(repository.replaceEncryptedPrivateKey(stored.id.toString(), "encrypted-old", "encrypted-new"))
                assertEquals("encrypted-new", repository.findActiveByAccountId(accountId)?.privateKeyEncrypted)

                repository.revokeActiveKeys(accountId)
                assertEquals(emptyList(), repository.encryptedPrivateKeys())
            }
        }
    }
}
