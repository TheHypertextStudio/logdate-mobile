package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.AtprotoIdentityTestSupport.newAccount
import app.logdate.server.identity.AtprotoIdentityTestSupport.plcService
import app.logdate.server.logging.IdentityNotices
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class AtprotoIdentityConcurrencyTest {
    @Test
    fun `concurrent ensureIdentity calls provision one identity`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(Account(id = Uuid.random(), username = "race", displayName = "R", createdAt = Clock.System.now()))

            val results =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    (1..16).map { async { service.ensureIdentity(account) } }.awaitAll()
                }

            assertEquals(1, results.map { it.did }.toSet().size)
            assertEquals(results.first().did, accountRepository.findById(account.id)?.did)
        }

    @Test
    fun `ensureIdentity never brings back a deleted account`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service = plcService(accountRepository)
            val stale =
                accountRepository.save(newAccount("gone"))
            accountRepository.deleteAccount(stale.id)

            assertFailsWith<IdentityAccountMissingException> { service.ensureIdentity(stale) }
            assertNull(accountRepository.findById(stale.id))
            assertNull(accountRepository.findByHandle("gone.logdate.app"))
        }

    @Test
    fun `ensureIdentity provisions from the stored account rather than the caller's copy`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service = plcService(accountRepository)
            val stale =
                accountRepository.save(newAccount("renamed"))
            accountRepository.save(stale.copy(displayName = "New", bio = "Written after the caller read the account"))

            val ensured = service.ensureIdentity(stale)

            assertEquals("New", ensured.displayName)
            assertEquals("Written after the caller read the account", accountRepository.findById(stale.id)?.bio)
            assertTrue(accountRepository.findById(stale.id)?.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `ensureIdentity keeps the identity another writer assigned first`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val account =
                delegate.save(newAccount("contested"))
            val winner =
                account.copy(
                    handle = "contested.logdate.app",
                    did = "did:plc:winnerwinnerwinnerwinner",
                    signingKeyPublic = "zWinner",
                )
            val repository =
                object : app.logdate.server.auth.AccountRepository by delegate {
                    override suspend fun updateIdentityIfUnchanged(
                        expected: Account,
                        updated: Account,
                    ): Account? {
                        // Another server instance assigns its identity between this one's read and write.
                        assertNotNull(delegate.updateIdentityIfUnchanged(account, winner))
                        return delegate.updateIdentityIfUnchanged(expected, updated)
                    }
                }

            val ensured = plcService(repository).ensureIdentity(account)

            assertEquals(winner.did, ensured.did)
            assertEquals(winner.did, delegate.findById(account.id)?.did)
        }

    @Test
    fun `backfill skips an account deleted after the listing`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val kept = delegate.save(newAccount("kept"))
            val deleted = delegate.save(newAccount("deleted"))
            val repository =
                object : app.logdate.server.auth.AccountRepository by delegate {
                    override suspend fun findAccountsMissingIdentity(): List<Account> {
                        val listed = delegate.findAccountsMissingIdentity()
                        delegate.deleteAccount(deleted.id)
                        return listed
                    }
                }

            val result = plcService(repository).backfillMissingIdentities()

            assertEquals(IdentityBackfillResult(provisioned = 1, skipped = 1, failed = 0), result)
            assertNull(delegate.findById(deleted.id))
            assertNull(delegate.findByHandle("deleted.logdate.app"))
            assertTrue(delegate.findById(kept.id)?.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `backfill logs each account that fails and keeps going`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val broken = delegate.save(newAccount("broken"))
            val healthy = delegate.save(newAccount("healthy"))
            val failure = IllegalStateException("database write failed")
            val repository =
                object : app.logdate.server.auth.AccountRepository by delegate {
                    override suspend fun updateIdentityIfUnchanged(
                        expected: Account,
                        updated: Account,
                    ): Account? {
                        if (expected.id == broken.id) throw failure
                        return delegate.updateIdentityIfUnchanged(expected, updated)
                    }
                }
            val logged = mutableListOf<Triple<LogLevel, String?, Throwable?>>()
            val antilog =
                object : Antilog() {
                    override fun performLog(
                        priority: LogLevel,
                        tag: String?,
                        throwable: Throwable?,
                        message: String?,
                    ) {
                        logged += Triple(priority, message, throwable)
                    }
                }

            Napier.base(antilog)
            val result =
                try {
                    plcService(repository).backfillMissingIdentities()
                } finally {
                    Napier.takeLogarithm(antilog)
                }

            assertEquals(IdentityBackfillResult(provisioned = 1, skipped = 0, failed = 1), result)
            assertEquals<List<Triple<LogLevel, String?, Throwable?>>>(
                listOf(Triple(LogLevel.ERROR, IdentityNotices.BACKFILL_ACCOUNT_FAILED, failure)),
                logged,
            )
            assertTrue(delegate.findById(healthy.id)?.did?.startsWith("did:plc:") == true)
            assertNull(delegate.findById(broken.id)?.did)
        }

    @Test
    fun `the conditional identity write refuses a changed or deleted account`() =
        kotlinx.coroutines.test.runTest {
            val repository = InMemoryAccountRepository()
            val account =
                repository.save(newAccount("cas").copy(bio = "bio"))
            val identity = account.copy(handle = "cas.logdate.app", did = "did:plc:casaaaaaaaaaaaaaaaaaaaaa", signingKeyPublic = "zKey")

            val written = assertNotNull(repository.updateIdentityIfUnchanged(expected = account, updated = identity.copy(bio = "ignored")))
            assertEquals("cas.logdate.app", written.handle)
            assertEquals("bio", written.bio)
            assertNull(repository.updateIdentityIfUnchanged(expected = account, updated = identity.copy(did = "did:plc:other")))
            repository.deleteAccount(account.id)
            assertNull(repository.updateIdentityIfUnchanged(expected = written, updated = identity))
            assertNull(repository.findById(account.id))
        }
}
