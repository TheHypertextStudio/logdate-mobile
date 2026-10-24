package app.logdate.client.permissions

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyCapabilities
import app.logdate.shared.model.PasskeyRegistrationOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class AndroidPasskeyManager(
    private val context: Context,
) : PasskeyManager {
    private val credentialManager = CredentialManager.create(context)

    override suspend fun getCapabilities(): PasskeyCapabilities =
        try {
            val isPlatformAvailable = isPlatformAuthenticatorAvailable()
            PasskeyCapabilities(
                isSupported = true,
                isPlatformAuthenticatorAvailable = isPlatformAvailable,
                supportedAlgorithms = listOf("ES256", "RS256"),
            )
        } catch (e: Exception) {
            PasskeyCapabilities(
                isSupported = false,
                isPlatformAuthenticatorAvailable = false,
            )
        }

    override suspend fun isPlatformAuthenticatorAvailable(): Boolean =
        try {
            // Check if Google Play Services and device support passkeys
            true // Android 9+ with Google Play Services generally supports passkeys
        } catch (e: Exception) {
            false
        }

    override suspend fun registerPasskey(options: PasskeyRegistrationOptions): Result<String> =
        try {
            val createPublicKeyCredentialRequest =
                CreatePublicKeyCredentialRequest(
                    requestJson = buildRegistrationRequestJson(options),
                    preferImmediatelyAvailableCredentials = false,
                )

            val result =
                credentialManager.createCredential(
                    context = context,
                    request = createPublicKeyCredentialRequest,
                )

            when (result) {
                is CreatePublicKeyCredentialResponse -> {
                    Result.success(result.registrationResponseJson)
                }

                else -> {
                    Result.failure(PasskeyException("Unexpected credential type", PasskeyErrorCodes.UNKNOWN_ERROR))
                }
            }
        } catch (e: CreateCredentialException) {
            Result.failure(handleCreateCredentialException(e))
        } catch (e: Exception) {
            Result.failure(PasskeyException("Registration failed", PasskeyErrorCodes.UNKNOWN_ERROR, e))
        }

    override suspend fun authenticateWithPasskey(options: PasskeyAuthenticationOptions): Result<String> =
        requestAssertion(options, requestUnlock = false).map { it.credentialJson }

    override suspend fun authenticateWithUnlock(options: PasskeyAuthenticationOptions): Result<PasskeyAuthenticationResult> =
        requestAssertion(options, requestUnlock = true)

    private suspend fun requestAssertion(
        options: PasskeyAuthenticationOptions,
        requestUnlock: Boolean,
    ): Result<PasskeyAuthenticationResult> =
        try {
            val getPublicKeyCredentialOption =
                GetPublicKeyCredentialOption(
                    requestJson = buildAuthenticationRequestJson(options, requestUnlock),
                )

            val getCredentialRequest =
                GetCredentialRequest(
                    credentialOptions = listOf(getPublicKeyCredentialOption),
                )

            val result =
                credentialManager.getCredential(
                    context = context,
                    request = getCredentialRequest,
                )

            when (val credential = result.credential) {
                is PublicKeyCredential -> {
                    Result.success(parsePasskeyAuthenticationResult(credential.authenticationResponseJson))
                }

                else -> {
                    Result.failure(PasskeyException("Unexpected credential type", PasskeyErrorCodes.UNKNOWN_ERROR))
                }
            }
        } catch (e: GetCredentialException) {
            Result.failure(handleGetCredentialException(e))
        } catch (e: Exception) {
            Result.failure(PasskeyException("Authentication failed", PasskeyErrorCodes.UNKNOWN_ERROR, e))
        }

    override fun getAvailabilityStatus(): Flow<PasskeyCapabilities> =
        flowOf(
            PasskeyCapabilities(
                isSupported = true,
                isPlatformAuthenticatorAvailable = true,
                supportedAlgorithms = listOf("ES256", "RS256"),
            ),
        )

    private fun handleCreateCredentialException(e: CreateCredentialException): PasskeyException =
        when (e) {
            is CreateCredentialCancellationException -> {
                PasskeyException("User cancelled registration", PasskeyErrorCodes.USER_CANCELLED, e)
            }

            is CreateCredentialInterruptedException -> {
                PasskeyException("Registration interrupted", PasskeyErrorCodes.INVALID_STATE, e)
            }

            is CreateCredentialProviderConfigurationException -> {
                PasskeyException("Provider configuration error", PasskeyErrorCodes.CONSTRAINT_ERROR, e)
            }

            is CreateCredentialUnknownException -> {
                PasskeyException("Unknown registration error", PasskeyErrorCodes.UNKNOWN_ERROR, e)
            }

            is CreateCredentialUnsupportedException -> {
                PasskeyException("Passkeys not supported", PasskeyErrorCodes.NOT_SUPPORTED, e)
            }

            // Everything else -- most importantly CreatePublicKeyCredentialDomException, which
            // carries the WebAuthn DOMException naming the real cause. Reporting a bare
            // "Registration failed" throws that away and leaves nothing to diagnose from.
            else -> {
                PasskeyException(describe("Registration failed", e.type, e.errorMessage), PasskeyErrorCodes.UNKNOWN_ERROR, e)
            }
        }

    private fun handleGetCredentialException(e: GetCredentialException): PasskeyException =
        when (e) {
            is GetCredentialCancellationException -> {
                PasskeyException("User cancelled authentication", PasskeyErrorCodes.USER_CANCELLED, e)
            }

            is GetCredentialInterruptedException -> {
                PasskeyException("Authentication interrupted", PasskeyErrorCodes.INVALID_STATE, e)
            }

            is GetCredentialProviderConfigurationException -> {
                PasskeyException("Provider configuration error", PasskeyErrorCodes.CONSTRAINT_ERROR, e)
            }

            is GetCredentialUnknownException -> {
                PasskeyException("Unknown authentication error", PasskeyErrorCodes.UNKNOWN_ERROR, e)
            }

            is GetCredentialUnsupportedException -> {
                PasskeyException("Passkeys not supported", PasskeyErrorCodes.NOT_SUPPORTED, e)
            }

            is NoCredentialException -> {
                PasskeyException("No credentials available", PasskeyErrorCodes.NOT_ALLOWED, e)
            }

            else -> {
                PasskeyException(describe("Authentication failed", e.type, e.errorMessage), PasskeyErrorCodes.UNKNOWN_ERROR, e)
            }
        }

    /**
     * Credential Manager reports unrecognised failures through [type] and [errorMessage] rather
     * than a distinct exception class, so both are kept -- they are usually the only evidence of
     * why a ceremony failed.
     */
    private fun describe(
        summary: String,
        type: String?,
        errorMessage: CharSequence?,
    ): String =
        listOfNotNull(
            summary,
            type?.takeIf { it.isNotBlank() },
            errorMessage?.toString()?.takeIf { it.isNotBlank() },
        ).joinToString(": ")
}

internal fun buildRegistrationRequestJson(options: PasskeyRegistrationOptions): String {
    // Build the WebAuthn registration request JSON
    // This should match the format expected by the Android Credential Manager
    return """
        {
            "rp": {
                "id": "${options.rpId}",
                "name": "${options.rpName}"
            },
            "user": {
                "id": "${options.user.id}",
                "name": "${options.user.name}",
                "displayName": "${options.user.displayName}"
            },
            "challenge": "${options.challenge}",
            "pubKeyCredParams": [
                {"type": "public-key", "alg": -7},
                {"type": "public-key", "alg": -257}
            ],
            "timeout": ${options.timeout},
            "excludeCredentials": ${credentialDescriptors(options.excludeCredentials)},
            "authenticatorSelection": {
                "requireResidentKey": false,
                "residentKey": "preferred",
                "userVerification": "preferred"
            },
            "extensions": {"prf": {}},
            "attestation": "none"
        }
        """.trimIndent()
}

internal fun buildAuthenticationRequestJson(
    options: PasskeyAuthenticationOptions,
    requestUnlock: Boolean = false,
): String {
    val extensions = if (requestUnlock) ",\n\"extensions\":{\"prf\":{\"eval\":{\"first\":\"${PasskeyUnlockInput.first}\"}}}" else ""
    return """
        {
            "challenge": "${options.challenge}",
            "timeout": ${options.timeout},
            "rpId": "${options.rpId}",
            "allowCredentials": ${credentialDescriptors(options.allowCredentials)},
            "userVerification": "${if (requestUnlock) "required" else "preferred"}"$extensions
        }
        """.trimIndent()
}

/**
 * WebAuthn's `allowCredentials` and `excludeCredentials` take credential descriptors, not bare
 * ids. Credential Manager silently matches nothing when handed a list of strings, so sign-in
 * fails with "No credentials available" even when the passkey is present and healthy.
 */
private fun credentialDescriptors(credentialIds: List<String>): String =
    Json.encodeToString(
        ListSerializer(PublicKeyCredentialDescriptorJson.serializer()),
        credentialIds.map { PublicKeyCredentialDescriptorJson(type = "public-key", id = it) },
    )

@Serializable
private data class PublicKeyCredentialDescriptorJson(
    val type: String,
    val id: String,
)
