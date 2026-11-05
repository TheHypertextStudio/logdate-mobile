package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class HostedIdentityWriteConflictTest {
    @Test
    fun rotationCannotReportSuccessAfterConditionalAuthorityWriteLoses() =
        runTest {
            val fixture = Fixture()
            val owner = fixture.provision("owner")
            fixture.accounts.refuseWrites = true
            assertFailsWith<IdentityLifecycleConflictException> { fixture.service.rotateSigningKey(owner) }
            assertEquals(owner, fixture.accounts.findById(owner.id))
            assertEquals(0, fixture.notices)
        }

    @Test
    fun importCannotReportSuccessAfterConditionalAuthorityWriteLoses() =
        runTest {
            val fixture = Fixture()
            val owner = fixture.provision("owner")
            val donor = fixture.provision("donor")
            val exported = fixture.keys.exportActiveKey(donor.id, "synthetic-passphrase")
            fixture.accounts.refuseWrites = true
            assertFailsWith<IdentityLifecycleConflictException> {
                fixture.service.importSigningKey(owner, exported, "synthetic-passphrase")
            }
            assertEquals(owner, fixture.accounts.findById(owner.id))
            assertEquals(0, fixture.notices)
        }

    private class RefusingAccounts(
        private val delegate: AccountRepository,
    ) : AccountRepository by delegate {
        var refuseWrites = false

        override suspend fun updateIdentityIfUnchanged(
            expected: Account,
            updated: Account,
        ): Account? = if (refuseWrites) null else delegate.updateIdentityIfUnchanged(expected, updated)
    }

    private class Fixture {
        val accounts = RefusingAccounts(InMemoryAccountRepository())
        val keys = SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-kek")
        val service =
            AtprotoIdentityService(
                accounts,
                keys,
                AtprotoIdentityConfig(
                    handleDomain = "logdate.test",
                    pdsServiceEndpoint = "https://logdate.test",
                    hostedAccountDidMethod = HostedAccountDidMethod.WEB,
                ),
            )
        var notices = 0

        suspend fun provision(username: String): Account {
            val account = accounts.save(Account(Uuid.random(), username, username, createdAt = Clock.System.now()))
            val provisioned = service.ensureIdentity(account)
            service.setIdentityEvents(IdentityEvents { notices++ })
            notices = 0
            return provisioned
        }
    }
}
