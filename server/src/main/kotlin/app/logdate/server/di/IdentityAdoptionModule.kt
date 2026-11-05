@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.di

import app.logdate.server.bluesky.AtprotoBlueskySignInProvider
import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.crypto.EncryptionService
import app.logdate.server.database.DatabaseConfig
import app.logdate.server.database.PostgreSQLIdentityAdoptionRepository
import app.logdate.server.database.composeIdentityAdoptionRuntime
import app.logdate.server.database.toJavaUUID
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.adoption.IdentityAdoptionRepository
import app.logdate.server.identity.adoption.IdentityAdoptionRuntime
import app.logdate.server.identity.adoption.createIdentityAdoptionRuntime
import app.logdate.server.logdate.FilesystemLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.sync.GcsMediaStorage
import org.koin.core.Koin
import org.koin.dsl.module
import java.net.URI

internal fun identityAdoptionRecoveryModule(isDatabaseAvailable: Boolean) =
    module {
        if (isDatabaseAvailable) {
            val source = checkNotNull(DatabaseConfig.initializedDataSource)
            single<IdentityAdoptionRepository> { PostgreSQLIdentityAdoptionRepository(source) }
        }
    }

internal fun createServerIdentityAdoptionRuntime(
    isDatabaseAvailable: Boolean,
    bluesky: BlueskyOAuthRuntime?,
    koin: Koin,
): IdentityAdoptionRuntime? =
    createIdentityAdoptionRuntime(isDatabaseAvailable, bluesky) { source, oauth ->
        require(oauth.client.clientMetadata.scope == "atproto") { "Identity adoption requires authentication-only OAuth" }
        val attempts = requireNotNull(oauth.attempts) { "Identity adoption requires the protected OAuth attempt store" }
        val storage = koin.get<LogDateBlobStorage>()
        require(storage is FilesystemLogDateBlobStorage || storage is GcsMediaStorage) {
            "Identity adoption requires durable private object ownership and retirement"
        }
        val entitlements = koin.get<EntitlementEnforcer>()
        val origin = identityAdoptionBackendOrigin(koin.get<AtprotoIdentityConfig>().pdsServiceEndpoint)
        composeIdentityAdoptionRuntime(
            source,
            AtprotoBlueskySignInProvider(oauth.client),
            attempts,
            koin.get(),
            origin,
            storage,
            { entitlements.spaceQuota(it.toJavaUUID())?.limitBytes ?: Long.MAX_VALUE },
            EncryptionService.fromEnvironment(),
            koin.get(),
        )
    }

internal fun identityAdoptionBackendOrigin(endpoint: String): String {
    val parsed = URI(endpoint)
    require(
        parsed.scheme == "https" &&
            parsed.host != null &&
            parsed.rawPath in listOf("", "/") &&
            parsed.rawQuery == null &&
            parsed.rawFragment == null &&
            parsed.rawUserInfo == null &&
            (parsed.port == -1 || parsed.port in 1..65535),
    ) { "Identity adoption requires a canonical HTTPS backend origin" }
    val origin = endpoint.removeSuffix("/")
    val canonical = URI("https", null, parsed.host.lowercase(), if (parsed.port == 443) -1 else parsed.port, null, null, null)
    require(origin == canonical.toASCIIString()) { "Identity adoption backend origin must match the canonical descriptor origin" }
    return origin
}
