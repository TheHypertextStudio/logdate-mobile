package app.logdate.feature.core.account

import app.logdate.client.domain.account.AuthenticateWithPasskeyUseCase
import app.logdate.client.domain.account.CreatePasskeyAccountUseCase
import app.logdate.client.networking.DiscoveryFailure
import app.logdate.client.networking.ServerDiscoveryException
import app.logdate.feature.core.settings.ui.ServerPreset
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_failure_account_not_found
import logdate.client.feature.core.generated.resources.account_failure_cloud_not_responding
import logdate.client.feature.core.generated.resources.account_failure_cloud_unreachable
import logdate.client.feature.core.generated.resources.account_failure_cloud_unreadable
import logdate.client.feature.core.generated.resources.account_failure_custom_not_responding
import logdate.client.feature.core.generated.resources.account_failure_custom_unreachable
import logdate.client.feature.core.generated.resources.account_failure_custom_unreadable
import logdate.client.feature.core.generated.resources.account_failure_display_name_invalid
import logdate.client.feature.core.generated.resources.account_failure_local_data_needs_adoption
import logdate.client.feature.core.generated.resources.account_failure_no_passkey_found
import logdate.client.feature.core.generated.resources.account_failure_passkey_creation_cancelled
import logdate.client.feature.core.generated.resources.account_failure_passkey_creation_failed
import logdate.client.feature.core.generated.resources.account_failure_passkey_not_supported
import logdate.client.feature.core.generated.resources.account_failure_rate_limited
import logdate.client.feature.core.generated.resources.account_failure_server_needs_update
import logdate.client.feature.core.generated.resources.account_failure_server_not_responding
import logdate.client.feature.core.generated.resources.account_failure_server_unreachable
import logdate.client.feature.core.generated.resources.account_failure_sign_in_cancelled
import logdate.client.feature.core.generated.resources.account_failure_sign_in_passkey_failed
import logdate.client.feature.core.generated.resources.account_failure_unknown
import logdate.client.feature.core.generated.resources.account_failure_username_invalid
import logdate.client.feature.core.generated.resources.account_failure_username_taken
import org.jetbrains.compose.resources.StringResource

/**
 * What to tell the person when the server they chose could not be set up: what failed and what to
 * do next, never the exception's own text, which names hosts and status codes.
 */
internal fun serverSelectionFailureMessage(
    error: Throwable,
    server: ServerPreset,
): StringResource {
    val cloud = server == ServerPreset.PRODUCTION
    val kind =
        when (error) {
            is ServerDiscoveryException -> error.kind

            // A custom server that is reachable but lacks the account protocol is refused before
            // it is saved; anything else from the custom checks means it could not be reached.
            is IllegalArgumentException -> return Res.string.account_failure_server_needs_update

            else -> DiscoveryFailure.UNREACHABLE
        }
    return when (kind) {
        DiscoveryFailure.UNREACHABLE -> {
            if (cloud) {
                Res.string.account_failure_cloud_unreachable
            } else {
                Res.string.account_failure_custom_unreachable
            }
        }

        DiscoveryFailure.NOT_RESPONDING -> {
            if (cloud) {
                Res.string.account_failure_cloud_not_responding
            } else {
                Res.string.account_failure_custom_not_responding
            }
        }

        DiscoveryFailure.UNREADABLE -> {
            if (cloud) {
                Res.string.account_failure_cloud_unreadable
            } else {
                Res.string.account_failure_custom_unreadable
            }
        }
    }
}

/** What to tell the person when signing in did not finish. */
internal fun signInFailureMessage(error: AuthenticateWithPasskeyUseCase.AuthenticationError): StringResource =
    when (error) {
        AuthenticateWithPasskeyUseCase.AuthenticationError.PasskeyNotSupported -> {
            Res.string.account_failure_passkey_not_supported
        }

        AuthenticateWithPasskeyUseCase.AuthenticationError.PasskeyCancelled -> {
            Res.string.account_failure_sign_in_cancelled
        }

        AuthenticateWithPasskeyUseCase.AuthenticationError.PasskeyFailed -> {
            Res.string.account_failure_sign_in_passkey_failed
        }

        AuthenticateWithPasskeyUseCase.AuthenticationError.NoCredentialsFound -> {
            Res.string.account_failure_no_passkey_found
        }

        AuthenticateWithPasskeyUseCase.AuthenticationError.AccountNotFound -> {
            Res.string.account_failure_account_not_found
        }

        AuthenticateWithPasskeyUseCase.AuthenticationError.NetworkError -> {
            Res.string.account_failure_server_unreachable
        }

        AuthenticateWithPasskeyUseCase.AuthenticationError.ServerUnavailable -> {
            Res.string.account_failure_server_not_responding
        }

        AuthenticateWithPasskeyUseCase.AuthenticationError.LocalDataNeedsAdoption -> {
            Res.string.account_failure_local_data_needs_adoption
        }

        is AuthenticateWithPasskeyUseCase.AuthenticationError.Unknown -> {
            // The exception text names servers, key hashes and file paths. It is already in
            // the log; the user gets something they can act on.
            Res.string.account_failure_unknown
        }
    }

/** What to tell the person when creating an account did not finish. */
internal fun accountCreationFailureMessage(error: CreatePasskeyAccountUseCase.CreateAccountError): StringResource =
    when (error) {
        CreatePasskeyAccountUseCase.CreateAccountError.UsernameTaken -> {
            Res.string.account_failure_username_taken
        }

        CreatePasskeyAccountUseCase.CreateAccountError.UsernameInvalid -> {
            Res.string.account_failure_username_invalid
        }

        CreatePasskeyAccountUseCase.CreateAccountError.DisplayNameInvalid -> {
            Res.string.account_failure_display_name_invalid
        }

        CreatePasskeyAccountUseCase.CreateAccountError.PasskeyNotSupported -> {
            Res.string.account_failure_passkey_not_supported
        }

        CreatePasskeyAccountUseCase.CreateAccountError.PasskeyCancelled -> {
            Res.string.account_failure_passkey_creation_cancelled
        }

        CreatePasskeyAccountUseCase.CreateAccountError.PasskeyFailed -> {
            Res.string.account_failure_passkey_creation_failed
        }

        CreatePasskeyAccountUseCase.CreateAccountError.NetworkError -> {
            Res.string.account_failure_server_unreachable
        }

        CreatePasskeyAccountUseCase.CreateAccountError.RateLimited -> {
            Res.string.account_failure_rate_limited
        }

        CreatePasskeyAccountUseCase.CreateAccountError.ServerError -> {
            Res.string.account_failure_server_not_responding
        }

        is CreatePasskeyAccountUseCase.CreateAccountError.Unknown -> {
            // The exception text names servers, key hashes and file paths. It is already in
            // the log; the user gets something they can act on.
            Res.string.account_failure_unknown
        }
    }
