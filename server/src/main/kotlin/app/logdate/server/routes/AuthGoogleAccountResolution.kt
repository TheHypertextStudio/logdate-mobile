@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.audit.AuditCategory
import app.logdate.server.audit.AuditKey
import app.logdate.server.audit.formatAuditLog
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountLinkEvent
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.IdentityProvider
import app.logdate.shared.model.AccountStorageMode
import io.github.aakira.napier.Napier
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class GoogleResolution(
    val account: Account,
    val identity: AccountIdentity,
)

internal suspend fun resolveGoogleAccount(
    claims: GoogleIdTokenClaims,
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    allowCreate: Boolean,
    requestedUsername: String?,
    requestedDisplayName: String?,
    requestedOwnerId: Uuid?,
    newAccountStorageMode: AccountStorageMode,
    call: ApplicationCall,
): GoogleResolution? {
    val normalizedEmail = claims.email.lowercase()
    val now = Clock.System.now()

    val googleIdentity = identityRepository.findByProviderSubject(IdentityProvider.GOOGLE, claims.subject)
    if (googleIdentity != null) {
        val account = accountRepository.findById(googleIdentity.accountId) ?: return null
        if (requestedOwnerId != null && account.id != requestedOwnerId) return null
        identityRepository.touchLastSignIn(googleIdentity.id)
        return GoogleResolution(account, googleIdentity)
    }

    val emailMatches = accountRepository.findByVerifiedEmail(normalizedEmail)
    if (emailMatches.size > 1) {
        Napier.w("Google linking conflict for verified email $normalizedEmail: ${emailMatches.size} accounts")
        return null
    }

    if (emailMatches.size == 1) {
        val matchedAccount = emailMatches.first()
        if (requestedOwnerId != null && matchedAccount.id != requestedOwnerId) return null
        return linkGoogleByVerifiedEmail(matchedAccount, claims, normalizedEmail, now, accountRepository, identityRepository, call)
    }

    if (!allowCreate) {
        return null
    }

    return createGoogleAccount(
        requestedOwnerId,
        requestedUsername,
        requestedDisplayName,
        claims,
        normalizedEmail,
        now,
        newAccountStorageMode,
        accountRepository,
        identityRepository,
        call,
    )
}

internal suspend fun resolveUniqueUsername(
    requestedUsername: String?,
    fallbackEmail: String,
    accountRepository: AccountRepository,
): String? {
    val requested = requestedUsername?.trim().orEmpty()
    val candidate = if (requested.isNotBlank()) requested else fallbackEmail.substringBefore('@').ifBlank { "user" }
    val sanitized = candidate.replace(Regex("[^a-zA-Z0-9_]"), "_").take(USERNAME_MAX_LENGTH)
    if (validateUsername(sanitized) == null && !accountRepository.usernameExists(sanitized)) {
        return sanitized
    }

    for (i in 1..1000) {
        val withSuffix = "${sanitized.take((USERNAME_MAX_LENGTH - 5).coerceAtLeast(1))}_${1000 + i}"
        if (validateUsername(withSuffix) == null && !accountRepository.usernameExists(withSuffix)) {
            return withSuffix
        }
    }

    return null
}

private suspend fun linkGoogleByVerifiedEmail(
    matchedAccount: Account,
    claims: GoogleIdTokenClaims,
    normalizedEmail: String,
    now: kotlin.time.Instant,
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    call: ApplicationCall,
): GoogleResolution {
    val identity =
        identityRepository.save(
            AccountIdentity(
                id = Uuid.random(),
                accountId = matchedAccount.id,
                provider = IdentityProvider.GOOGLE,
                providerSubject = claims.subject,
                email = normalizedEmail,
                emailVerified = true,
                createdAt = now,
                lastSignInAt = now,
                metadataJson = "{}",
            ),
        )

    identityRepository.saveLinkEvent(
        AccountLinkEvent(
            id = Uuid.random(),
            accountId = matchedAccount.id,
            provider = IdentityProvider.GOOGLE,
            providerSubject = claims.subject,
            reason = "implicit_verified_email",
            ipHash = call.hashRemoteIp(),
            userAgentHash = call.hashUserAgent(),
            createdAt = now,
        ),
    )

    if (matchedAccount.email != normalizedEmail || !matchedAccount.emailVerified) {
        accountRepository.save(
            matchedAccount.copy(
                email = normalizedEmail,
                emailVerified = true,
            ),
        )
    }

    Napier.i(
        formatAuditLog(
            AuditCategory.AUTH_LINK_GOOGLE_IMPLICIT,
            mapOf(
                AuditKey.ACCOUNT_ID to matchedAccount.id.toString(),
                AuditKey.PROVIDER_SUBJECT_HASH to claims.subject.sha256(),
                AuditKey.IP_HASH to call.hashRemoteIp(),
                AuditKey.USER_AGENT_HASH to call.hashUserAgent(),
            ),
        ),
    )
    return GoogleResolution(matchedAccount.copy(email = normalizedEmail, emailVerified = true), identity)
}

private suspend fun createGoogleAccount(
    requestedOwnerId: Uuid?,
    requestedUsername: String?,
    requestedDisplayName: String?,
    claims: GoogleIdTokenClaims,
    normalizedEmail: String,
    now: kotlin.time.Instant,
    newAccountStorageMode: AccountStorageMode,
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    call: ApplicationCall,
): GoogleResolution? {
    val ownerId = requestedOwnerId ?: return null

    val username =
        resolveUniqueUsername(
            requestedUsername = requestedUsername,
            fallbackEmail = normalizedEmail,
            accountRepository = accountRepository,
        ) ?: return null
    val displayName = requestedDisplayName?.takeIf { it.isNotBlank() } ?: claims.name?.takeIf { it.isNotBlank() } ?: username

    val account =
        Account(
            id = ownerId,
            username = username,
            displayName = displayName,
            email = normalizedEmail,
            emailVerified = true,
            createdAt = now,
            lastSignInAt = now,
            isActive = true,
            storageMode = newAccountStorageMode,
        )
    if (!accountRepository.createOnly(account)) return null

    val identity =
        try {
            identityRepository.save(
                AccountIdentity(
                    id = Uuid.random(),
                    accountId = account.id,
                    provider = IdentityProvider.GOOGLE,
                    providerSubject = claims.subject,
                    email = normalizedEmail,
                    emailVerified = true,
                    createdAt = now,
                    lastSignInAt = now,
                ),
            )
        } catch (error: Exception) {
            runCatching { accountRepository.deleteAccount(account.id) }
                .onFailure { Napier.w("Failed to roll back Google account ${account.id}", it) }
            throw error
        }

    identityRepository.saveLinkEvent(
        AccountLinkEvent(
            id = Uuid.random(),
            accountId = account.id,
            provider = IdentityProvider.GOOGLE,
            providerSubject = claims.subject,
            reason = "google_signup",
            ipHash = call.hashRemoteIp(),
            userAgentHash = call.hashUserAgent(),
            createdAt = now,
        ),
    )

    return GoogleResolution(account, identity)
}
