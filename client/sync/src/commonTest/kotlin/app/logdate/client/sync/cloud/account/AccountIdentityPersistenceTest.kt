package app.logdate.client.sync.cloud.account

import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.InMemoryKeyValueStorage
import app.logdate.client.sync.cloud.LogDateCloudApiClient
import app.logdate.client.sync.test.FakeSessionStorage
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationResponse
import app.logdate.shared.model.LogDateAccount
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class AccountIdentityPersistenceTest {
    @Test
    fun `sync account refresh preserves explicit kinds and refuses to infer missing or future kinds`() =
        runTest {
            for ((field, expected) in listOf(
                "\"identityKind\":\"external\"," to AccountIdentityKind.EXTERNAL,
                "\"identityKind\":\"hosted\"," to AccountIdentityKind.HOSTED,
                "\"identityKind\":\"future\"," to AccountIdentityKind.UNRECOGNIZED,
                "" to AccountIdentityKind.UNRECOGNIZED,
            )) {
                val transport =
                    HttpClient(
                        MockEngine {
                            respond(
                                """{"success":true,"data":{"account":{
                    "id":"$ID","username":"alice","displayName":"Alice",$field
                    "createdAt":"2026-03-05T00:00:00Z","updatedAt":"2026-03-05T00:00:00Z"
                },"tokens":{"accessToken":"access","refreshToken":"refresh"}}}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    ) { install(ContentNegotiation) { json() } }
                try {
                    val api = LogDateCloudApiClient(DefaultLogDateConfigRepository(ORIGIN), transport)
                    assertEquals(expected, api.getAccountInfo("access").getOrThrow().identityKind)
                } finally {
                    transport.close()
                }
            }
        }

    @Test
    fun `external identity survives both server reported and account cache round trips`() =
        runTest {
            val account =
                LogDateAccount(
                    id = Uuid.parse(ID),
                    username = "alice",
                    displayName = "Alice",
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                    identityKind = AccountIdentityKind.EXTERNAL,
                )
            val metadata = InMemoryKeyValueStorage()
            val reported = ServerAccountStore(metadata)
            reported.write(ServerReportedAccount.of(ORIGIN, account))
            assertEquals(AccountIdentityKind.EXTERNAL, reported.read()?.toCloudAccount()?.identityKind)
            val config = DefaultLogDateConfigRepository(ORIGIN)
            val sessions =
                FakeSessionStorage().apply {
                    currentOrigin = ORIGIN
                    saveSession(UserSession("access", "refresh", ID))
                }
            val created = CompleteAccountCreationResponse(true, CompleteAccountCreationData(account, AccountTokens("access", "refresh")))
            val api = FakeCloudApiClient(completeAccountCreationResult = created)

            fun repository() = DefaultCloudAccountRepository(api, metadata, config, backgroundScope, sessions)
            val original = repository()
            original.completeAccountCreation("session", "cred", "client", "attestation")
            assertEquals(AccountIdentityKind.EXTERNAL, original.getCurrentAccount()?.identityKind)
            val reloaded = repository()
            runCurrent()
            assertEquals(AccountIdentityKind.EXTERNAL, reloaded.getCurrentAccount()?.identityKind)
            assertNull(metadata.getString(cloudAccountScopedKey("cloud_account_identity_kind", "https://other.logdate.app")))
            metadata.remove(cloudAccountScopedKey("cloud_account_identity_kind", ORIGIN))
            val oldCache = repository()
            runCurrent()
            assertEquals(AccountIdentityKind.UNRECOGNIZED, oldCache.getCurrentAccount()?.identityKind)
            oldCache.signOut()
            assertNull(metadata.getString(cloudAccountScopedKey("cloud_account_identity_kind", ORIGIN)))
        }

    private companion object {
        const val ORIGIN = "https://cloud.logdate.app"
        const val ID = "550e8400-e29b-41d4-a716-446655440001"
    }
}
