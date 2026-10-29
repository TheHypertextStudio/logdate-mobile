@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountConnectionCleanup
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.bluesky.connection.AtprotoConnectionProvider
import app.logdate.server.bluesky.connection.InProcessAccountCredentialMutationLock
import app.logdate.server.di.installServerKoin
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import org.koin.ktor.ext.getKoin
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.InProcessOAuthSessionLock
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthConditionalRevocation
import studio.hypertext.atproto.oauth.client.OAuthRevocationException
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class AtprotoAccountDeletionPostgresTest {
    @Test
    fun `database application binds deletion cleanup while external sign in is disabled`() =
        withBlueskyOAuthDatabase { _ ->
            testApplication {
                application {
                    installServerKoin(true) { null }
                    routing {
                        post("/cleanup-binding") {
                            kotlin.test.assertNotNull(getKoin().getOrNull<AccountConnectionCleanup>())
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
                kotlin.test.assertEquals(HttpStatusCode.NoContent, client.post("/cleanup-binding").status)
            }
        }

    @Test
    fun `account deletion removes retained credentials and evidence when external sign in is disabled`() =
        checkRemoval(remoteFailure = false)

    @Test
    fun `unconfirmed remote revocation cannot retain a deleted account or provider credentials`() = checkRemoval(remoteFailure = true)

    private fun checkRemoval(remoteFailure: Boolean) =
        withBlueskyOAuthDatabase { source ->
            val accounts = PostgreSQLAccountRepository()
            val did = AtprotoDid.require("did:plc:" + "a".repeat(24))
            val account =
                Account(
                    Uuid.random(),
                    "deletion_owner",
                    "Deletion Owner",
                    did = did.toString(),
                    createdAt = Clock.System.now(),
                    identityKind = AccountIdentityKind.EXTERNAL,
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                )
            assertTrue(accounts.create(account))
            val tokens = PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("synthetic-account-cleanup-key"))
            tokens.save(
                OAuthStoredSession(
                    did,
                    "https://issuer.example.com",
                    "https://pds.example.com",
                    "atproto",
                    "access",
                    "refresh",
                    Clock.System.now() + 5.minutes,
                    JvmOAuthCrypto.generateEs256Key(),
                    "client-key",
                ),
            )
            val lock = InProcessOAuthSessionLock()
            val provider =
                if (remoteFailure) {
                    object : AtprotoConnectionProvider {
                        override suspend fun restore(did: AtprotoDid): Boolean = error("Deletion must not renew provider authorization")

                        override suspend fun revokeIf(
                            did: AtprotoDid,
                            beforeRevoke: suspend () -> Boolean,
                            removeLocal: suspend () -> Unit,
                        ): OAuthConditionalRevocation =
                            lock.withLock(did) {
                                if (!beforeRevoke()) return@withLock OAuthConditionalRevocation.SKIPPED
                                removeLocal()
                                throw OAuthRevocationException(did, IllegalStateException("synthetic remote failure"))
                            }
                    }
                } else {
                    null
                }
            val cleanup = PostgreSQLAtprotoAccountCleanup(accounts, source, InProcessAccountCredentialMutationLock(), lock, provider)
            val service = AccountDeletionService(accounts, mockk(relaxed = true), mockk(relaxed = true), null, connectionCleanup = cleanup)
            assertTrue(service.deleteAccount(account.id).accountRowDeleted)
            assertNull(accounts.findById(account.id))
            assertNull(tokens.get(did))
            assertNull(PostgreSQLAtprotoConnectionRepository(source).snapshot(did))
        }
}
