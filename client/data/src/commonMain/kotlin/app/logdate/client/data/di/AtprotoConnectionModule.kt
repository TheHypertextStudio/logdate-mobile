package app.logdate.client.data.di

import app.logdate.client.data.account.AtprotoConnectionAccountHost
import app.logdate.client.data.account.AtprotoConnectionController
import app.logdate.client.data.account.BlueskyServerAvailability
import app.logdate.client.data.account.DefaultPasskeyAccountRepository
import app.logdate.client.data.account.PasskeyCredentialCodec
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.auth.AtprotoConnectionPendingStore
import app.logdate.client.networking.atproto.AtprotoConnectionApiClient
import app.logdate.client.networking.bluesky.BlueskyApiClient
import app.logdate.client.permissions.PasskeyErrorCodes
import app.logdate.client.permissions.PasskeyException
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AtprotoConnectionRepository
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.ServerProtocolFeature
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/** Registered by Android only; the native SwiftUI app owns its separate account host. */
internal val atprotoConnectionModule =
    module {
        single { AtprotoConnectionPendingStore(get()) }
        single<AtprotoConnectionRepository> {
            val accounts = get<PasskeyAccountRepository>() as DefaultPasskeyAccountRepository
            val config = get<LogDateConfigRepository>()
            val flags = get<FeatureFlagStore>()
            val transport = get<HttpClient>()
            val manager = get<PasskeyManager>()
            val codec = PasskeyCredentialCodec(Json { ignoreUnknownKeys = true })
            val host = AtprotoConnectionAccountHost(get(), config::getCurrentBackendUrl, accounts::refreshConnectionAuthentication)
            val availability =
                BlueskyServerAvailability(config, { flags.isEnabled(FeatureFlag.ATPROTO_CONNECTION) }) { origin ->
                    val api = BlueskyApiClient(transport, origin)
                    try {
                        api.serverDescriptor()
                    } finally {
                        api.close()
                    }
                }
            AtprotoConnectionController(
                binding = host::binding,
                accessToken = host::accessToken,
                enabled = {
                    availability.prepare() &&
                        config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.ATPROTO_CONNECTION_V1) == true
                },
                api = { AtprotoConnectionApiClient(transport, it) },
                pending = get(),
                passkey = { options ->
                    val raw =
                        manager.authenticateWithPasskey(options).getOrElse { error ->
                            if (error is CancellationException) throw error
                            if (error is PasskeyException && error.errorCode == PasskeyErrorCodes.USER_CANCELLED) {
                                throw CancellationException("Passkey confirmation canceled")
                            }
                            throw IllegalStateException("Passkey confirmation unavailable")
                        }
                    codec.parseAssertionResponse(raw)
                },
            )
        }
    }
