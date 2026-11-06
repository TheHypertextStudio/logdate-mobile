package app.logdate.server.identity

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.database.AtprotoAppPasswordStore
import app.logdate.server.database.DatabaseConfig
import app.logdate.server.database.PlcPublicationWrites
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.routes.atprotoAppPasswordRoutes
import app.logdate.server.routes.plcPublicationRoutes
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.plc.KtorPlcDirectoryClient
import java.net.Proxy
import java.net.URI

internal data class PlcPublicationRehearsal(
    val directoryUrl: String,
    val rotationDidKey: String,
) {
    companion object {
        fun read(env: (String) -> String?): PlcPublicationRehearsal? {
            if (env("LOGDATE_PLC_PUBLICATION_REHEARSAL") != "true") return null
            if (env("LOGDATE_ENV") !in setOf("development", "test")) return null
            if (env("ATPROTO_PLC_PUBLISH_ENABLED") !in setOf(null, "false")) return null
            if (!env("ATPROTO_RELAY_HOSTS").isNullOrBlank()) return null
            val rawUrl = env("ATPROTO_PLC_DIRECTORY_URL") ?: return null
            if (!isLoopbackPlcFixture(rawUrl)) return null
            val key = env("LOGDATE_PLC_REHEARSAL_ROTATION_KEY") ?: return null
            if (!key.startsWith("did:key:") || runCatching { EcKeySupport.decodePublicKey(key.removePrefix("did:key:")) }.isFailure) {
                return null
            }
            return PlcPublicationRehearsal(rawUrl.removeSuffix("/"), key)
        }
    }
}

internal fun isPlcRehearsalDatabase(jdbcUrl: String): Boolean {
    if (!jdbcUrl.startsWith("jdbc:")) return false
    val uri = runCatching { URI(jdbcUrl.removePrefix("jdbc:")) }.getOrNull() ?: return false
    return uri.scheme == "postgresql" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
        uri.rawAuthority == "127.0.0.1:${uri.port}" && uri.rawPath?.matches(Regex("/logdate_plc_rehearsal_[a-z0-9_]+")) == true &&
        uri.rawQuery == null && uri.rawFragment == null
}

/** Registers the account contract and enables writes only against the disposable loopback fixture. */
internal fun Application.installPlcPublicationRehearsal(isDatabaseAvailable: Boolean): AtprotoAppPasswordStore? {
    val accounts by inject<AccountRepository>()
    val tokens by inject<TokenService>()
    val consent = if (isDatabaseAvailable) startPlcPublicationFixture(accounts) else null
    val source = DatabaseConfig.initializedDataSource
    val store = if (consent != null && source != null) AtprotoAppPasswordStore(source) else null
    val webAuthn by inject<WebAuthnPasskeyService>()
    routing {
        route("/api/v1") {
            plcPublicationRoutes(accounts, tokens, consent)
            if (store != null) {
                atprotoAppPasswordRoutes(accounts, tokens, store, PlcPublicationPasskey(webAuthn, purpose = "app_password"))
            }
        }
    }
    return store
}

private fun Application.startPlcPublicationFixture(accounts: AccountRepository): PlcPublicationConsent? {
    val rehearsal = PlcPublicationRehearsal.read(System::getenv) ?: return null
    val source = DatabaseConfig.initializedDataSource as? HikariDataSource ?: return null
    if (!isPlcRehearsalDatabase(source.jdbcUrl.orEmpty())) return null
    val hostedOperations by inject<HostedPlcOperationRepository>()
    val signingKeys by inject<SigningKeyService>()
    val webAuthn by inject<WebAuthnPasskeyService>()
    val identityConfig by inject<AtprotoIdentityConfig>()
    if (!webAuthn.strictVerificationEnabled || identityConfig.publishHostedPlcOperations) return null

    val http =
        HttpClient(OkHttp) {
            followRedirects = false
            engine {
                config {
                    proxy(Proxy.NO_PROXY)
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
        }
    val consent =
        PlcPublicationConsent(
            accounts = accounts,
            hostedOperations = hostedOperations,
            writes = PlcPublicationWrites(source),
            passkeys = PlcPublicationPasskey(webAuthn),
            pdsEndpoint = identityConfig.pdsServiceEndpoint,
            serverRotationDidKey = rehearsal.rotationDidKey,
            signingKeys = signingKeys,
        )
    val directory = KtorPlcDirectoryClient(http, rehearsal.directoryUrl)
    installPlcPublicationMaintenance(consent, PlcPublicationWorker(directory))
    monitor.subscribe(ApplicationStopped) { http.close() }
    return consent
}
