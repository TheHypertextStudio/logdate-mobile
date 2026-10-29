package app.logdate.client.data.di

import app.logdate.client.data.account.BlueskyEnrollmentStore
import app.logdate.client.data.account.BlueskyServerAvailability
import app.logdate.client.data.account.DefaultPasskeyAccountRepository
import app.logdate.client.data.account.blueskySignInAvailable
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.auth.BlueskyAttemptStore
import app.logdate.client.networking.bluesky.BlueskyApiClient
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInRepository
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.config.LogDateConfigRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.dsl.module

internal val blueskySignInModule =
    module {
        single { BlueskyAttemptStore(get()) }
        single { BlueskyEnrollmentStore(get()) }
        single<BlueskySignInRepository> {
            val accounts = get<PasskeyAccountRepository>()
            if (accounts is DefaultPasskeyAccountRepository) {
                val config = get<LogDateConfigRepository>()
                val flags = get<FeatureFlagStore>()
                val transport = get<HttpClient>()
                val availability =
                    BlueskyServerAvailability(
                        config,
                        { flags.isEnabled(FeatureFlag.BLUESKY_SIGN_IN) },
                    ) { origin ->
                        val api = BlueskyApiClient(transport, origin)
                        try {
                            api.serverDescriptor()
                        } finally {
                            api.close()
                        }
                    }
                accounts.blueskySignInRepository(
                    prepareAvailability = availability::prepare,
                    attempts = get(),
                    enrollments = get(),
                    apiFactory = { origin -> BlueskyApiClient(transport, origin) },
                    enabled = {
                        blueskySignInAvailable(
                            flags.isEnabled(FeatureFlag.BLUESKY_SIGN_IN),
                            config.getCurrentBackendUrl(),
                            config.getCurrentServerDescriptor(),
                        )
                    },
                )
            } else {
                UnavailableBlueskySignInRepository()
            }
        }
    }

private class UnavailableBlueskySignInRepository : BlueskySignInRepository {
    override val state = MutableStateFlow<BlueskySignInState>(BlueskySignInState.Idle).asStateFlow()

    override suspend fun start(handle: String): Result<String> = Result.failure(IllegalStateException("Bluesky sign-in is unavailable"))

    override suspend fun resume(returnUrl: String): Boolean = false

    override suspend fun restore() = Unit

    override suspend fun createAccount(request: AccountCreationRequest) = Unit

    override suspend fun confirmPasskey(adoptLocalData: Boolean) = Unit

    override suspend fun cancel() = Unit
}
