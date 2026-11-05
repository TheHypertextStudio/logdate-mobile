package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.database.support.withH2Database
import app.logdate.shared.model.AccountIdentityKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class HostedProfileAuthorityTest {
    @Test
    fun memoryProfilesPreserveHostedAuthority() = runBlocking { verifyProfiles(InMemoryAccountRepository()) }

    @Test
    fun databaseProfilesPreserveHostedAuthority() =
        withH2Database(AccountsTable) {
            runBlocking { verifyProfiles(PostgreSQLAccountRepository()) }
        }

    private suspend fun verifyProfiles(accounts: AccountRepository) {
        for (provisioned in listOf(false, true)) {
            val id = Uuid.random()
            val original =
                Account(
                    id,
                    id.toHexString(),
                    "Profile owner",
                    createdAt = Clock.System.now(),
                    did = if (provisioned) "did:web:${id.toHexString()}.example.com" else null,
                    handle = if (provisioned) "${id.toHexString()}.example.com" else null,
                    signingKeyPublic = if (provisioned) "zOriginal" else null,
                    plcRecoveryDidKey = if (provisioned) "did:key:zRecovery" else null,
                )
            assertTrue(accounts.create(original))
            val stored = assertNotNull(accounts.findById(id))
            val claimed =
                stored.copy(
                    displayName = "Renamed",
                    did = "did:web:forged.example.com",
                    handle = "forged.example.com",
                    signingKeyPublic = "zForged",
                    plcRecoveryDidKey = "did:key:zForged",
                    identityKind = AccountIdentityKind.EXTERNAL,
                    identityEpoch = 99,
                )
            assertEquals(stored.copy(displayName = "Renamed"), accounts.save(claimed))
            assertEquals(stored.copy(displayName = "Renamed"), accounts.findById(id))
            assertNull(accounts.findByDid("did:web:forged.example.com"))
            assertNull(accounts.findByHandle("forged.example.com"))
            // Conditional authority writes remain usable after ordinary save stops writing identity fields.
            val updated = assertNotNull(accounts.updateIdentityIfUnchanged(stored, stored.copy(signingKeyPublic = "zConditional")))
            assertEquals("zConditional", updated.signingKeyPublic)
            assertEquals("Renamed", updated.displayName)
        }
    }
}
