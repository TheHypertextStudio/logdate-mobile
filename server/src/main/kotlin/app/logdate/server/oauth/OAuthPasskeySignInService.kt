package app.logdate.server.oauth

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.crypto.sha256
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.passkeys.OAUTH_AUTHORIZATION_CHALLENGE_TYPE
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import io.github.aakira.napier.Napier
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Lets a person approve another app's authorization request on the browser authorization page by
 * signing in with a passkey, so accounts without a password can still use their identity.
 *
 * Signing in with the passkey is the approval. The page never holds a session of its own: every
 * request is approved by its own passkey ceremony, over a challenge that is only valid for that
 * request.
 */
@OptIn(ExperimentalUuidApi::class)
class OAuthPasskeySignInService(
    config: OAuthConfig,
    private val authorizationService: OAuthAuthorizationService,
    private val webAuthnService: WebAuthnPasskeyService,
    private val accountRepository: AccountRepository,
    private val identityService: AtprotoIdentityService,
) {
    private val trustedClientIds = config.trustedClientIds

    init {
        check(webAuthnService.strictVerificationEnabled) {
            "LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED is on, but passkey verification is not strict. Without " +
                "WEBAUTHN_STRICT_VERIFICATION=true an assertion is not checked cryptographically, so anyone could approve " +
                "apps on someone else's behalf. Turn strict verification on or turn the flag off."
        }
        val relyingPartyId = webAuthnService.relyingPartyId
        val origin =
            checkNotNull(config.passkeySignInOrigin(relyingPartyId)) {
                "LOGDATE_OAUTH_PASSKEY_SIGN_IN_ENABLED is on, but the OAuth issuer ${config.issuerOrigin} is outside the passkey " +
                    "relying party $relyingPartyId. Browsers only offer a passkey on pages under its relying party, so nobody " +
                    "could sign in on the authorization page. Serve the issuer from $relyingPartyId or one of its subdomains, " +
                    "or turn the flag off."
            }
        check(webAuthnService.acceptsOrigin(origin)) {
            "Passkey verification does not accept the authorization page origin $origin. Add it to the WebAuthn origins."
        }
    }

    /** Decides what the authorization page shows for [requestUri]. */
    suspend fun page(
        requestUri: String,
        clientId: String?,
    ): OAuthPasskeyPage {
        // Only an unknown or expired request means "expired"; anything else is a server failure
        // and reaches the caller, which shows the server-error page.
        val prompt =
            try {
                authorizationService.describeAuthorizationRequest(requestUri)
            } catch (_: OAuthInvalidRequestException) {
                return OAuthPasskeyPage.RequestExpired
            }
        if (clientId != null && clientId != prompt.clientId) return OAuthPasskeyPage.ClientMismatch

        val clientLabel = clientLabel(prompt.clientId, prompt.clientName)
        val hint = prompt.loginHint
        val hintedAccount = hint?.let { findHintedAccount(it) }
        if (hint != null && hintedAccount == null) {
            return OAuthPasskeyPage.UnknownAccount(clientLabel = clientLabel, requestedAccount = displayAccount(hint))
        }
        return OAuthPasskeyPage.SignIn(
            requestUri = prompt.requestUri,
            clientLabel = clientLabel,
            clientAddress = clientAddress(prompt.clientId),
            accountHandle = hintedAccount?.handle,
        )
    }

    /**
     * Issues a passkey challenge that only approves [requestUri].
     *
     * The options never list credential IDs, so anyone can push a request naming any handle
     * without learning which passkeys that account has. The browser offers the person's
     * discoverable passkeys instead, and [approve] refuses one that belongs to another account.
     */
    suspend fun options(requestUri: String): OAuthPasskeyOptionsResponse {
        val prompt = authorizationService.describeAuthorizationRequest(requestUri)
        val options =
            webAuthnService.generateBoundAuthenticationOptions(
                purpose = OAUTH_AUTHORIZATION_CHALLENGE_TYPE,
                binding = challengeBinding(prompt.requestUri),
            )
        return OAuthPasskeyOptionsResponse(
            challenge = options.challenge,
            rpId = webAuthnService.relyingPartyId,
            allowCredentials = options.allowCredentials,
            timeout = options.timeout,
            userVerification = USER_VERIFICATION_REQUIRED,
        )
    }

    /**
     * Verifies [credential] against [challenge] and, when it signs in the account the request is
     * for, approves [requestUri]. Returns the app's redirect URI carrying the authorization code.
     *
     * A failed or mismatched sign-in throws [OAuthAccessDeniedException] and leaves the request
     * usable, so the person can try another passkey.
     */
    suspend fun approve(
        requestUri: String,
        challenge: String,
        credential: PasskeyAssertionResponse,
    ): String {
        val prompt = authorizationService.describeAuthorizationRequest(requestUri)
        val verification =
            webAuthnService.verifyBoundAuthentication(
                challenge = challenge,
                authenticationResponse = credential.toAuthenticationResponse(),
                purpose = OAUTH_AUTHORIZATION_CHALLENGE_TYPE,
                binding = challengeBinding(prompt.requestUri),
            )
        val accountId = verification.userId
        if (!verification.success || accountId == null) {
            Napier.w("Authorization page passkey sign-in failed for client ${prompt.clientId}: ${verification.error}")
            throw OAuthAccessDeniedException(failureMessage(verification.error))
        }
        val account =
            accountRepository
                .findById(accountId)
                ?.takeIf(Account::isActive)
                ?.let { identityService.ensureIdentity(it) }
                ?: throw OAuthAccessDeniedException("The LogDate account for this passkey is no longer available.")
        val did = requireNotNull(account.did) { "ensureIdentity returned an account without a DID" }
        val handle = requireNotNull(account.handle) { "ensureIdentity returned an account without a handle" }

        val hint = prompt.loginHint
        if (hint != null && !loginHintMatches(hint, subjectDid = did, subjectHandle = handle)) {
            val requested = findHintedAccount(hint)?.handle?.let { "@$it" } ?: displayAccount(hint)
            throw OAuthAccessDeniedException(
                "This passkey is for @$handle, but ${clientLabel(prompt.clientId, prompt.clientName)} asked to sign in as " +
                    "$requested. Use the passkey for $requested.",
                HttpStatusCode.Forbidden,
            )
        }

        accountRepository.updateLastSignIn(account.id)
        val redirect =
            authorizationService.completeAuthorization(
                requestUri = requestUri,
                subjectDid = did,
                subjectHandle = handle,
                approved = true,
            )
        Napier.i("Authorization page approved client ${prompt.clientId} for account ${account.id} with a passkey")
        return redirect
    }

    /** Cancels [requestUri] and returns the app's redirect URI carrying `error=access_denied`. */
    suspend fun deny(requestUri: String): String = authorizationService.denyAuthorization(requestUri)

    /**
     * Looks the hinted account up without creating anything: nobody has proven who they are yet,
     * so the page and the challenge only read what already exists.
     */
    private suspend fun findHintedAccount(loginHint: String): Account? {
        val hint = loginHint.trim().removePrefix("@").lowercase()
        val account =
            if (hint.startsWith(DID_PREFIX)) {
                accountRepository.findByDid(hint)
            } else {
                accountRepository.findByHandle(hint)
            }
        return account?.takeIf(Account::isActive)
    }

    /** Names a trusted app by its own name and every other app by the client ID URL it cannot fake. */
    private fun clientLabel(
        clientId: String,
        clientName: String,
    ): String = if (clientId in trustedClientIds) clientName else clientId

    private fun failureMessage(error: String?): String =
        when (error) {
            CREDENTIAL_NOT_FOUND -> "This passkey isn't registered to a LogDate account. Choose a passkey you use for LogDate."
            INVALID_CHALLENGE -> "This sign-in attempt timed out. Try again."
            else -> "This passkey couldn't be verified. Try again."
        }

    private companion object {
        const val USER_VERIFICATION_REQUIRED = "required"
        const val DID_PREFIX = "did:"

        // Failure reasons reported by WebAuthnPasskeyService.verifyAuthentication.
        const val CREDENTIAL_NOT_FOUND = "Credential not found"
        const val INVALID_CHALLENGE = "Invalid challenge"
    }
}

/** A stable ID for [requestUri], so its challenge cannot be spent on any other request. */
@OptIn(ExperimentalUuidApi::class)
private fun challengeBinding(requestUri: String): Uuid = Uuid.fromByteArray(sha256(requestUri).copyOf(UUID_BYTES))

private const val UUID_BYTES = 16

/** What the browser authorization page shows. */
sealed interface OAuthPasskeyPage {
    /** The request is valid: offer passkey sign-in to the app named [clientLabel]. */
    data class SignIn(
        val requestUri: String,
        /** The app's own name when it is trusted, otherwise its client ID URL. */
        val clientLabel: String,
        /** Where the app's client metadata lives, so a person can tell a real app from a look-alike. */
        val clientAddress: String,
        /** The account the app asked for, when it named one that exists here. */
        val accountHandle: String?,
    ) : OAuthPasskeyPage

    /** The request is unknown, already used, or expired. */
    data object RequestExpired : OAuthPasskeyPage

    /** The browser URL names a different app from the one that pushed the request. */
    data object ClientMismatch : OAuthPasskeyPage

    /** The server could not load the request, for example because its database is unreachable. */
    data object ServerError : OAuthPasskeyPage

    /** The app asked for an account that is not hosted here. */
    data class UnknownAccount(
        val clientLabel: String,
        val requestedAccount: String,
    ) : OAuthPasskeyPage
}

/** The WebAuthn options the authorization page passes to `navigator.credentials.get`. */
@Serializable
data class OAuthPasskeyOptionsResponse(
    val challenge: String,
    @SerialName("rp_id") val rpId: String,
    @SerialName("allow_credentials") val allowCredentials: List<String>,
    val timeout: Long,
    @SerialName("user_verification") val userVerification: String,
)

/** Body of the request that asks for passkey options. */
@Serializable
data class OAuthPasskeyOptionsRequest(
    @SerialName("request_uri") val requestUri: String,
)

/** Body of the request that signs in with a passkey and approves the authorization request. */
@Serializable
data class OAuthPasskeyApprovalRequest(
    @SerialName("request_uri") val requestUri: String,
    val challenge: String,
    val credential: PasskeyAssertionResponse,
)

/** Where the authorization page sends the browser after a successful passkey sign-in. */
@Serializable
data class OAuthPasskeyApprovalResponse(
    @SerialName("redirect_uri") val redirectUri: String,
)

private fun PasskeyAssertionResponse.toAuthenticationResponse(): PasskeyAuthenticationResponse =
    PasskeyAuthenticationResponse(
        id = id,
        rawId = rawId,
        response =
            AuthenticatorAssertionResponse(
                clientDataJSON = response.clientDataJSON,
                authenticatorData = response.authenticatorData,
                signature = response.signature,
                userHandle = response.userHandle.takeIf(String::isNotBlank),
            ),
        type = type,
    )

private fun clientAddress(clientId: String): String =
    runCatching { URI(clientId) }
        .getOrNull()
        ?.let { uri -> uri.host?.let { host -> if (uri.port == -1) host else "$host:${uri.port}" } }
        ?: clientId

private fun displayAccount(hint: String): String {
    val trimmed = hint.trim()
    return if (trimmed.startsWith("did:") || trimmed.startsWith("@")) trimmed else "@$trimmed"
}
