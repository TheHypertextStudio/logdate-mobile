package app.logdate.server.database

import app.logdate.server.database.PostgreSqlRepositoryTestSupport.sampleAccount
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.samplePasskeyInfo
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.withPasskeyTables
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StoredPasskeyRepositoryTest {
    @Test
    fun `passkey repository store catches transaction failures when no database is initialized`() {
        val repository = PostgreSQLPasskeyRepository()
        val info = samplePasskeyInfo("cred-no-db")

        val stored =
            runBlocking {
                repository.storePasskey(
                    userId = Uuid.random(),
                    credentialId = "cred-no-db",
                    publicKey = byteArrayOf(1),
                    signCount = 0L,
                    info = info,
                )
            }

        assertFalse(stored)
    }

    @Test
    fun `passkey repository supports storage lookup updates and utility methods`() {
        withPasskeyTables {
            val accountId = Uuid.random()
            val repository = PostgreSQLPasskeyRepository()
            PostgreSQLAccountRepository().save(sampleAccount(accountId, username = "passkey-owner"))
            val info = samplePasskeyInfo("cred-a")

            assertTrue(
                repository.storePasskey(
                    userId = accountId,
                    credentialId = "cred-a",
                    publicKey = byteArrayOf(1, 2, 3),
                    signCount = 1,
                    info = info,
                ),
            )

            assertTrue(repository.credentialExists("cred-a"))
            assertFalse(repository.credentialExists("missing"))
            assertTrue(repository.credentialBelongsToUser("cred-a", accountId))
            assertFalse(repository.credentialBelongsToUser("cred-a", Uuid.random()))

            val byCredential = repository.getPasskeyByCredentialId("cred-a")
            assertNotNull(byCredential)
            assertEquals(accountId, byCredential.first)
            assertEquals(1L, byCredential.second.signCount)

            assertEquals(1, repository.getPasskeysForUser(accountId).size)
            assertEquals(listOf("cred-a"), repository.getCredentialIdsForUser(accountId))

            assertTrue(repository.updateSignCount("cred-a", 7))
            assertFalse(repository.updateSignCount("missing", 1))

            assertNotNull(repository.findByCredentialId("cred-a"))
            assertNotNull(repository.findById(info.id))
            assertEquals(1, repository.findByAccountId(accountId).size)
            assertEquals(1, repository.findActiveByAccountId(accountId).size)
            assertEquals(listOf("cred-a"), repository.getCredentialIdsForAccount(accountId))
            assertTrue(repository.updateLastUsed("cred-a"))

            // Cover decode fallback path by storing non-base64 public key.
            val plainInfo = samplePasskeyInfo("cred-b")
            repository.saveWithWebAuthnData(
                accountId = accountId,
                passkey = plainInfo,
                publicKey = "@@not-base64@@",
                signCount = 5,
                webauthnData = "{\"uv\":true}",
            )
            repository.saveWithWebAuthnData(
                accountId = accountId,
                passkey = samplePasskeyInfo("cred-c"),
                publicKey = "@@also-not-base64@@",
                signCount = 3,
            )
            val plainStored = repository.getPasskeyByCredentialId("cred-b")
            assertNotNull(plainStored)
            assertTrue(plainStored.second.publicKey.contentEquals("@@not-base64@@".toByteArray()))

            val webAuthnData = repository.getWebAuthnData("cred-b")
            assertNotNull(webAuthnData)
            assertEquals("@@not-base64@@", webAuthnData.first)
            assertEquals(5L, webAuthnData.second)

            assertFalse(repository.deactivatePasskey("cred-a", Uuid.random()))
            assertTrue(repository.deactivatePasskey("cred-a", accountId))
            assertNull(repository.getPasskeyByCredentialId("cred-a"))

            assertTrue(repository.deletePasskey(plainInfo.id))
            assertFalse(repository.deletePasskey(plainInfo.id))
        }
    }
}
