@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.logdate.client.permissions

import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyCapabilities
import app.logdate.shared.model.PasskeyRegistrationOptions
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialAssertion
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialProvider
import platform.AuthenticationServices.ASAuthorizationPlatformPublicKeyCredentialRegistration
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

private const val IOS_PASSKEY_MIN_VERSION = 16

/**
 * iOS [PasskeyManager] backed by `ASAuthorizationPlatformPublicKeyCredentialProvider`.
 *
 * Real client-side passkey registration and assertion. The relying-party identifier in the
 * registration / authentication options must match a domain listed under the iOS app's
 * `com.apple.developer.associated-domains` entitlement and the corresponding
 * `apple-app-site-association` file hosted at that domain — without those, iOS rejects the
 * request and the failure surfaces through [PasskeyException].
 */
@OptIn(ExperimentalForeignApi::class)
class IosPasskeyManager : PasskeyManager {
    private var pendingRegistration: CompletableDeferred<Result<String>>? = null
    private var pendingAuthentication: CompletableDeferred<Result<String>>? = null
    private val controllers = mutableSetOf<ASAuthorizationController>()
    private val delegate = AuthorizationDelegate()
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    override suspend fun getCapabilities(): PasskeyCapabilities =
        PasskeyCapabilities(
            isSupported = isPasskeySupported(),
            isPlatformAuthenticatorAvailable = isPlatformAuthenticatorAvailable(),
            supportedAlgorithms = listOf("ES256", "RS256"),
        )

    override suspend fun isPlatformAuthenticatorAvailable(): Boolean = isPasskeySupported()

    override suspend fun registerPasskey(options: PasskeyRegistrationOptions): Result<String> {
        if (!isPasskeySupported()) {
            return Result.failure(
                PasskeyException(
                    "Passkeys require iOS $IOS_PASSKEY_MIN_VERSION or later",
                    PasskeyErrorCodes.NOT_SUPPORTED,
                ),
            )
        }
        val challenge = options.challenge.base64UrlDecodeToNSData() ?: return invalidArgFailure("challenge")
        val userId = options.user.id.toUserIdNSData() ?: return invalidArgFailure("user.id")
        val deferred = CompletableDeferred<Result<String>>()
        if (pendingRegistration != null) {
            return Result.failure(
                PasskeyException(
                    "Another passkey registration is already in flight",
                    PasskeyErrorCodes.INVALID_STATE,
                ),
            )
        }
        pendingRegistration = deferred
        return runCatching {
            withContext(Dispatchers.Main) {
                val provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier = options.rpId)
                val request =
                    provider.createCredentialRegistrationRequestWithChallenge(
                        challenge = challenge,
                        name = options.user.name,
                        userID = userId,
                    )
                val controller = ASAuthorizationController(authorizationRequests = listOf(request))
                controller.delegate = delegate
                controller.presentationContextProvider = delegate
                controllers += controller
                controller.performRequests()
            }
            deferred.await()
        }.onFailure { pendingRegistration = null }
            .getOrElse {
                Result.failure(PasskeyException("Registration failed", PasskeyErrorCodes.UNKNOWN_ERROR, it))
            }
    }

    override suspend fun authenticateWithPasskey(options: PasskeyAuthenticationOptions): Result<String> {
        if (!isPasskeySupported()) {
            return Result.failure(
                PasskeyException(
                    "Passkeys require iOS $IOS_PASSKEY_MIN_VERSION or later",
                    PasskeyErrorCodes.NOT_SUPPORTED,
                ),
            )
        }
        val challenge = options.challenge.base64UrlDecodeToNSData() ?: return invalidArgFailure("challenge")
        if (pendingAuthentication != null) {
            return Result.failure(
                PasskeyException(
                    "Another passkey authentication is already in flight",
                    PasskeyErrorCodes.INVALID_STATE,
                ),
            )
        }
        val deferred = CompletableDeferred<Result<String>>()
        pendingAuthentication = deferred
        return runCatching {
            withContext(Dispatchers.Main) {
                val provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier = options.rpId)
                val request = provider.createCredentialAssertionRequestWithChallenge(challenge = challenge)
                val controller = ASAuthorizationController(authorizationRequests = listOf(request))
                controller.delegate = delegate
                controller.presentationContextProvider = delegate
                controllers += controller
                controller.performRequests()
            }
            deferred.await()
        }.onFailure { pendingAuthentication = null }
            .getOrElse {
                Result.failure(PasskeyException("Authentication failed", PasskeyErrorCodes.UNKNOWN_ERROR, it))
            }
    }

    override fun getAvailabilityStatus(): Flow<PasskeyCapabilities> =
        flowOf(
            PasskeyCapabilities(
                isSupported = isPasskeySupported(),
                isPlatformAuthenticatorAvailable = isPasskeySupported(),
                supportedAlgorithms = listOf("ES256", "RS256"),
            ),
        )

    private fun isPasskeySupported(): Boolean {
        val version = NSProcessInfo.processInfo.operatingSystemVersion
        return version.useContents { majorVersion >= IOS_PASSKEY_MIN_VERSION }
    }

    private fun invalidArgFailure(name: String): Result<String> =
        Result.failure(
            PasskeyException(
                "Invalid passkey '$name' — expected base64URL-encoded value",
                PasskeyErrorCodes.CONSTRAINT_ERROR,
            ),
        )

    private inner class AuthorizationDelegate :
        NSObject(),
        ASAuthorizationControllerDelegateProtocol,
        ASAuthorizationControllerPresentationContextProvidingProtocol {
        override fun authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithAuthorization: ASAuthorization,
        ) {
            controllers -= controller
            when (val credential = didCompleteWithAuthorization.credential()) {
                is ASAuthorizationPlatformPublicKeyCredentialRegistration -> {
                    val response = encodeRegistration(credential)
                    pendingRegistration?.complete(Result.success(response))
                    pendingRegistration = null
                }

                is ASAuthorizationPlatformPublicKeyCredentialAssertion -> {
                    val response = encodeAssertion(credential)
                    pendingAuthentication?.complete(Result.success(response))
                    pendingAuthentication = null
                }

                else -> {
                    val ex =
                        PasskeyException(
                            "Unexpected credential type: ${credential::class.simpleName}",
                            PasskeyErrorCodes.UNKNOWN_ERROR,
                        )
                    pendingRegistration?.complete(Result.failure(ex))
                    pendingRegistration = null
                    pendingAuthentication?.complete(Result.failure(ex))
                    pendingAuthentication = null
                }
            }
        }

        override fun authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithError: NSError,
        ) {
            controllers -= controller
            Napier.w("ASAuthorization failed: ${didCompleteWithError.localizedDescription}")
            val ex =
                PasskeyException(
                    didCompleteWithError.localizedDescription,
                    PasskeyErrorCodes.UNKNOWN_ERROR,
                )
            pendingRegistration?.complete(Result.failure(ex))
            pendingRegistration = null
            pendingAuthentication?.complete(Result.failure(ex))
            pendingAuthentication = null
        }

        override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): UIWindow {
            val app = UIApplication.sharedApplication

            @Suppress("DEPRECATION")
            val window =
                app.keyWindow
                    ?: app.windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
                    ?: app.windows.firstOrNull() as? UIWindow
            return window ?: error("No UIWindow available to anchor passkey presentation")
        }
    }

    private fun encodeRegistration(credential: ASAuthorizationPlatformPublicKeyCredentialRegistration): String {
        val rawId = credential.credentialID.base64UrlEncode()
        val clientData = credential.rawClientDataJSON.base64UrlEncode()
        val attestation = credential.rawAttestationObject?.base64UrlEncode().orEmpty()
        return json.encodeToString(
            RegistrationResponseJson.serializer(),
            RegistrationResponseJson(
                id = rawId,
                rawId = rawId,
                response = AttestationJson(clientDataJSON = clientData, attestationObject = attestation),
            ),
        )
    }

    private fun encodeAssertion(credential: ASAuthorizationPlatformPublicKeyCredentialAssertion): String {
        val rawId = credential.credentialID.base64UrlEncode()
        val clientData = credential.rawClientDataJSON.base64UrlEncode()
        val authenticatorData = credential.rawAuthenticatorData?.base64UrlEncode().orEmpty()
        val signature = credential.signature?.base64UrlEncode().orEmpty()
        val userHandle = credential.userID?.base64UrlEncode()
        return json.encodeToString(
            AuthenticationResponseJson.serializer(),
            AuthenticationResponseJson(
                id = rawId,
                rawId = rawId,
                response =
                    AssertionJson(
                        clientDataJSON = clientData,
                        authenticatorData = authenticatorData,
                        signature = signature,
                        userHandle = userHandle,
                    ),
            ),
        )
    }
}

@Serializable
private data class RegistrationResponseJson(
    val id: String,
    val rawId: String,
    val type: String = "public-key",
    val response: AttestationJson,
)

@Serializable
private data class AttestationJson(
    val clientDataJSON: String,
    val attestationObject: String,
)

@Serializable
private data class AuthenticationResponseJson(
    val id: String,
    val rawId: String,
    val type: String = "public-key",
    val response: AssertionJson,
)

@Serializable
private data class AssertionJson(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String?,
)

private fun NSData.base64UrlEncode(): String =
    base64EncodedStringWithOptions(0u)
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')

private fun String.base64UrlDecodeToNSData(): NSData? {
    var s = replace('-', '+').replace('_', '/')
    val padding = (4 - s.length % 4) % 4
    if (padding > 0) s += "=".repeat(padding)
    return NSData.create(base64EncodedString = s, options = 0u)
}

/**
 * WebAuthn user-id is conventionally base64URL-encoded binary; if the caller passes a raw
 * identifier we fall back to UTF-8 so the registration can still proceed.
 */
private fun String.toUserIdNSData(): NSData? =
    base64UrlDecodeToNSData()
        ?: NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding)
