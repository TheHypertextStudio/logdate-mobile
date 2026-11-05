package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
internal object AtprotoIdentityTestSupport {
    fun newAccount(username: String) =
        Account(id = Uuid.random(), username = username, displayName = username, createdAt = Clock.System.now())

    fun plcService(accountRepository: app.logdate.server.auth.AccountRepository) =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )

    class FullScanForbiddenAccountRepository(
        private val delegate: InMemoryAccountRepository,
    ) : app.logdate.server.auth.AccountRepository by delegate {
        var lookups = 0

        override suspend fun findByHandle(handle: String): Account? {
            lookups++
            return delegate.findByHandle(handle)
        }

        override suspend fun getAllAccounts(): List<Account> = error("Handle resolution must not load every account")

        override suspend fun findAccountsMissingIdentity(): List<Account> = error("Handle resolution must not scan accounts")
    }

    fun decodeBase64Url(value: String): ByteArray {
        val normalized =
            when (value.length % 4) {
                0 -> value
                2 -> "$value=="
                3 -> "$value="
                else -> error("Invalid base64url value")
            }
        return Base64.UrlSafe.decode(normalized)
    }
}
