package app.logdate.server.bluesky

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.identity.HandleLabels
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyRegistrationResponse
import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.oauth.client.InProcessOAuthSessionLock
import studio.hypertext.atproto.oauth.client.OAuthSessionLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class BlueskyAccountProfile(
    val requestedOwnerId: String,
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
)

internal data class BlueskyRegistrationStart(
    val token: String,
    val options: PasskeyRegistrationOptions,
) {
    override fun toString(): String = "BlueskyRegistrationStart()"
}

internal data class BlueskyAuthenticationStart(
    val token: String,
    val options: PasskeyAuthenticationOptions,
) {
    override fun toString(): String = "BlueskyAuthenticationStart()"
}

internal enum class BlueskyAccountFailureKind { EXPIRED, INVALID_PROFILE, CONFLICT, PASSKEY_FAILED, UNAVAILABLE }

internal class BlueskyAccountFailure(
    val kind: BlueskyAccountFailureKind,
) : IllegalStateException("Bluesky account confirmation failed")

/** One-use provider proof and a verified passkey are separate requirements for account access. */
@OptIn(ExperimentalUuidApi::class)
internal class BlueskyPasskeyAccountService(
    private val accounts: AccountRepository,
    private val handoffs: BlueskyAccountHandoffStore,
    private val enrollments: BlueskyAccountEnrollmentRepository,
    private val passkeys: WebAuthnPasskeyService,
    private val clock: Clock = Clock.System,
    private val claimsLock: OAuthSessionLock = InProcessOAuthSessionLock(),
) {
    init {
        check(passkeys.strictVerificationEnabled) { "Bluesky sign-in requires strict passkey verification" }
    }

    suspend fun beginRegistration(
        token: String,
        profile: BlueskyAccountProfile,
    ): BlueskyRegistrationStart =
        available {
            val owner = validateProfile(profile)
            if (accounts.findById(owner) != null || accounts.usernameExists(profile.username)) conflict()
            val identity = handoffs.take(token) as? BlueskyAccountHandoff.Identity ?: expired()
            if (identity.accountId != null || accounts.findByDid(identity.did.toString()) != null) conflict()
            val options = passkeys.generateRegistrationOptions(owner, profile.username, profile.displayName)
            val registration =
                BlueskyAccountHandoff.Registration(
                    identity.did,
                    owner.toString(),
                    profile.username,
                    profile.displayName,
                    profile.bio,
                    profile.originalBio ?: profile.bio,
                    options.challenge,
                )
            BlueskyRegistrationStart(handoffs.issue(registration, clock.now() + 5.minutes), options)
        }

    suspend fun completeRegistration(
        token: String,
        credential: PasskeyRegistrationResponse,
        nickname: String?,
    ): Account =
        available {
            val pending = handoffs.take(token) as? BlueskyAccountHandoff.Registration ?: expired()
            val owner = Uuid.parse(pending.ownerId)
            if (accounts.findById(owner) != null || accounts.findByDid(pending.did.toString()) != null) conflict()
            val verified =
                when (val outcome = passkeys.verifyRegistrationOnly(owner, pending.challenge, credential)) {
                    is WebAuthnPasskeyService.VerificationOutcome.Success -> outcome.data.withNickname(nickname)
                    is WebAuthnPasskeyService.VerificationOutcome.Failure -> passkeyFailed()
                }
            val now = clock.now()
            val account =
                Account(
                    id = owner,
                    username = pending.username,
                    displayName = pending.displayName,
                    did = pending.did.toString(),
                    bio = pending.bio,
                    originalBio = pending.originalBio,
                    createdAt = now,
                    lastSignInAt = now,
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                    identityKind = AccountIdentityKind.EXTERNAL,
                )
            claimsLock.withLock(pending.did) {
                if (accounts.findById(owner) != null || accounts.findByDid(pending.did.toString()) != null) conflict()
                if (!enrollments.create(account, verified)) conflict()
                account
            }
        }

    suspend fun beginAuthentication(token: String): BlueskyAuthenticationStart =
        available {
            val identity = handoffs.take(token) as? BlueskyAccountHandoff.Identity ?: expired()
            val accountId = identity.accountId?.let(Uuid::parse) ?: conflict()
            val account = matchingAccount(accountId, identity.did.toString())
            val options = passkeys.generateBoundAuthenticationOptions(BLUESKY_SIGN_IN_CHALLENGE_TYPE, account.id)
            val pending = BlueskyAccountHandoff.Authentication(identity.did, account.id.toString(), options.challenge)
            BlueskyAuthenticationStart(handoffs.issue(pending, clock.now() + 5.minutes), options)
        }

    suspend fun completeAuthentication(
        token: String,
        credential: PasskeyAuthenticationResponse,
    ): Account =
        available {
            val pending = handoffs.take(token) as? BlueskyAccountHandoff.Authentication ?: expired()
            val accountId = Uuid.parse(pending.accountId)
            matchingAccount(accountId, pending.did.toString())
            val confirmed = passkeys.verifyBoundAuthentication(pending.challenge, credential, BLUESKY_SIGN_IN_CHALLENGE_TYPE, accountId)
            if (!confirmed.success || confirmed.userId != accountId) passkeyFailed()
            // Recheck after verification in case adoption, moderation or deletion changed the account.
            val account = matchingAccount(accountId, pending.did.toString())
            accounts.updateLastSignIn(account.id)
            account
        }

    private suspend fun matchingAccount(
        accountId: Uuid,
        did: String,
    ): Account {
        val account = accounts.findById(accountId) ?: conflict()
        if (account.did != did ||
            !account.isActive ||
            account.moderation != null ||
            account.identityKind == AccountIdentityKind.UNRECOGNIZED
        ) {
            conflict()
        }
        if (account.identityKind == AccountIdentityKind.EXTERNAL && account.storageMode != AccountStorageMode.PERSONAL_SPACE_V1) conflict()
        return account
    }

    private fun validateProfile(profile: BlueskyAccountProfile): Uuid {
        val owner = runCatching { Uuid.parse(profile.requestedOwnerId) }.getOrNull()
        if (owner == null ||
            owner.toString() != profile.requestedOwnerId ||
            profile.username.length !in 3..50 ||
            !usernameFormat.matches(profile.username) ||
            HandleLabels.isReservedUsername(profile.username) ||
            profile.displayName.isBlank() ||
            profile.displayName.length > 100
        ) {
            throw BlueskyAccountFailure(BlueskyAccountFailureKind.INVALID_PROFILE)
        }
        return owner
    }

    private suspend fun <T> available(block: suspend () -> T): T =
        try {
            block()
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: BlueskyAccountFailure) {
            throw BlueskyAccountFailure(failure.kind)
        } catch (_: Exception) {
            throw BlueskyAccountFailure(BlueskyAccountFailureKind.UNAVAILABLE)
        }

    private fun conflict(): Nothing = throw BlueskyAccountFailure(BlueskyAccountFailureKind.CONFLICT)

    private fun expired(): Nothing = throw BlueskyAccountFailure(BlueskyAccountFailureKind.EXPIRED)

    private fun passkeyFailed(): Nothing = throw BlueskyAccountFailure(BlueskyAccountFailureKind.PASSKEY_FAILED)

    private val usernameFormat = Regex("^[a-zA-Z0-9_]+$")
}

internal const val BLUESKY_SIGN_IN_CHALLENGE_TYPE = "bluesky_signin"
