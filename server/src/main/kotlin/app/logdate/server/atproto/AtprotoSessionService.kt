@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.atproto

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.HandleLabels
import app.logdate.shared.model.AccountStorageMode
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.CreateAccountRequest
import studio.hypertext.atproto.pds.CreateSessionRequest
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.SessionInfoResponse
import studio.hypertext.atproto.pds.SessionResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Stored AT Protocol password credential for standards-compatible session creation.
 */
@OptIn(ExperimentalUuidApi::class)
public data class AtprotoPasswordCredential(
    val accountId: Uuid,
    val salt: String,
    val hash: String,
    val iterations: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/**
 * Persistent hosted AT Protocol session metadata.
 */
@OptIn(ExperimentalUuidApi::class)
public data class AtprotoSession(
    val id: String,
    val accountId: Uuid,
    val createdAtEpochMillis: Long,
    val refreshExpiresAtEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
    val appPasswordId: Uuid? = null,
)

/**
 * Password credential persistence for standard AT Protocol account sessions.
 */
@OptIn(ExperimentalUuidApi::class)
public interface AtprotoPasswordCredentialRepository {
    public suspend fun save(credential: AtprotoPasswordCredential): AtprotoPasswordCredential

    public suspend fun findByAccountId(accountId: Uuid): AtprotoPasswordCredential?
}

/**
 * Session persistence for standard AT Protocol account sessions.
 */
@OptIn(ExperimentalUuidApi::class)
public interface AtprotoSessionRepository {
    public suspend fun save(session: AtprotoSession): AtprotoSession

    public suspend fun findById(sessionId: String): AtprotoSession?

    public suspend fun isAppPasswordActive(
        accountId: Uuid,
        passwordId: Uuid,
        did: String,
    ): Boolean = false

    public suspend fun revoke(sessionId: String): Boolean
}

/**
 * In-memory password credential repository for tests and local runtime fallback.
 */
@OptIn(ExperimentalUuidApi::class)
public class InMemoryAtprotoPasswordCredentialRepository : AtprotoPasswordCredentialRepository {
    private val credentials = linkedMapOf<Uuid, AtprotoPasswordCredential>()

    override suspend fun save(credential: AtprotoPasswordCredential): AtprotoPasswordCredential {
        credentials[credential.accountId] = credential
        return credential
    }

    override suspend fun findByAccountId(accountId: Uuid): AtprotoPasswordCredential? = credentials[accountId]
}

/**
 * In-memory hosted AT Protocol session repository for tests and local runtime fallback.
 */
public class InMemoryAtprotoSessionRepository : AtprotoSessionRepository {
    private val sessions = linkedMapOf<String, AtprotoSession>()

    override suspend fun save(session: AtprotoSession): AtprotoSession {
        sessions[session.id] = session
        return session
    }

    override suspend fun findById(sessionId: String): AtprotoSession? = sessions[sessionId]

    override suspend fun revoke(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        sessions[sessionId] = session.copy(revokedAtEpochMillis = Clock.System.now().toEpochMilliseconds())
        return true
    }
}

/**
 * Password hashing and verification for hosted AT Protocol credentials.
 */
@OptIn(ExperimentalUuidApi::class)
public class AtprotoPasswordService(
    private val repository: AtprotoPasswordCredentialRepository,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val clock: Clock = Clock.System,
) {
    public suspend fun setPassword(
        accountId: Uuid,
        password: String,
    ): AtprotoPasswordCredential {
        val normalizedPassword = password.trim()
        require(normalizedPassword.length >= MIN_PASSWORD_LENGTH) { "Password must be at least $MIN_PASSWORD_LENGTH characters" }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val now = clock.now().toEpochMilliseconds()
        return repository.save(
            AtprotoPasswordCredential(
                accountId = accountId,
                salt = base64(salt),
                hash = hashPassword(normalizedPassword, salt, ITERATIONS),
                iterations = ITERATIONS,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
    }

    public suspend fun verifyPassword(
        accountId: Uuid,
        password: String,
    ): Boolean {
        val credential = repository.findByAccountId(accountId) ?: return false
        val candidateHash = hashPassword(password.trim(), decodeBase64(credential.salt), credential.iterations)
        return MessageDigest.isEqual(candidateHash.encodeToByteArray(), credential.hash.encodeToByteArray())
    }

    public suspend fun hasPassword(accountId: Uuid): Boolean = repository.findByAccountId(accountId) != null

    private fun hashPassword(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): String {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return base64(factory.generateSecret(keySpec).encoded)
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 120_000
        private const val HASH_BITS = 256
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    }
}

/**
 * Stateful access/refresh token service for hosted AT Protocol sessions.
 */
@OptIn(ExperimentalUuidApi::class)
public class AtprotoSessionTokenService(
    private val sessionRepository: AtprotoSessionRepository,
    private val secret: String,
    private val issuer: String = "logdate.app",
    private val audience: String = "atproto-pds",
    private val clock: Clock = Clock.System,
) {
    public suspend fun createSession(account: Account): IssuedAtprotoSession {
        val session =
            AtprotoSession(
                id = Uuid.random().toString(),
                accountId = account.id,
                createdAtEpochMillis = clock.now().toEpochMilliseconds(),
                refreshExpiresAtEpochMillis = (clock.now() + REFRESH_TOKEN_TTL).toEpochMilliseconds(),
            )
        sessionRepository.save(session)
        return issueSessionTokens(account = account, session = session)
    }

    internal suspend fun createAppPasswordSession(
        account: Account,
        passwordId: Uuid,
        persist: suspend (AtprotoSession, String) -> Boolean,
    ): IssuedAtprotoSession? {
        val did = account.did ?: return null
        if (account.publicIdentityDid != did || !account.isActive || account.moderation != null) return null
        val now = clock.now()
        val session =
            AtprotoSession(
                id = Uuid.random().toString(),
                accountId = account.id,
                createdAtEpochMillis = now.toEpochMilliseconds(),
                refreshExpiresAtEpochMillis = (now + REFRESH_TOKEN_TTL).toEpochMilliseconds(),
                appPasswordId = passwordId,
            )
        if (!persist(session, did)) return null
        return issueSessionTokens(account, session)
    }

    public suspend fun refreshSession(
        refreshJwt: String,
        accountRepository: AccountRepository,
        identityService: AtprotoIdentityService,
    ): IssuedAtprotoSession? {
        val token = validateToken(refreshJwt, expectedType = REFRESH_TOKEN_TYPE) ?: return null
        val session = activeSession(token.sessionId) ?: return null
        val account = accountRepository.findById(session.accountId) ?: return null
        val current = sessionAccount(account, session, token, identityService, allowAppPassword = true) ?: return null
        return issueSessionTokens(account = current, session = session)
    }

    public suspend fun getAccessAccount(
        accessJwt: String,
        accountRepository: AccountRepository,
        identityService: AtprotoIdentityService,
    ): Account? {
        val token = validateToken(accessJwt, expectedType = ACCESS_TOKEN_TYPE) ?: return null
        val session = activeSession(token.sessionId) ?: return null
        val account = accountRepository.findById(session.accountId) ?: return null
        return sessionAccount(account, session, token, identityService, allowAppPassword = false)
    }

    internal suspend fun getAppPasswordAccessAccount(
        accessJwt: String,
        accountRepository: AccountRepository,
        identityService: AtprotoIdentityService,
    ): Account? {
        val token = validateToken(accessJwt, expectedType = ACCESS_TOKEN_TYPE) ?: return null
        val session = activeSession(token.sessionId)?.takeIf { it.appPasswordId != null } ?: return null
        val account = accountRepository.findById(session.accountId) ?: return null
        return sessionAccount(account, session, token, identityService, allowAppPassword = true)
    }

    private suspend fun sessionAccount(
        account: Account,
        session: AtprotoSession,
        token: ValidatedAtprotoSessionToken,
        identityService: AtprotoIdentityService,
        allowAppPassword: Boolean,
    ): Account? {
        if (token.accountId != session.accountId) return null
        val passwordId = session.appPasswordId ?: return identityService.ensureIdentity(account)
        if (!allowAppPassword || account.did != token.did || account.publicIdentityDid != token.did ||
            !account.isActive || account.moderation != null
        ) {
            return null
        }
        return account.takeIf { sessionRepository.isAppPasswordActive(account.id, passwordId, token.did) }
    }

    public suspend fun revokeRefreshToken(refreshJwt: String): Boolean {
        val token = validateToken(refreshJwt, expectedType = REFRESH_TOKEN_TYPE) ?: return false
        return sessionRepository.revoke(token.sessionId)
    }

    private suspend fun issueSessionTokens(
        account: Account,
        session: AtprotoSession,
    ): IssuedAtprotoSession {
        val did = requireNotNull(account.did)
        val accessJwt =
            generateToken(
                payload =
                    AtprotoSessionTokenClaims(
                        sub = account.id.toString(),
                        iss = issuer,
                        aud = audience,
                        exp = (clock.now() + ACCESS_TOKEN_TTL).epochSeconds,
                        iat = clock.now().epochSeconds,
                        type = ACCESS_TOKEN_TYPE,
                        sid = session.id,
                        did = did,
                    ),
            )
        val refreshJwt =
            generateToken(
                payload =
                    AtprotoSessionTokenClaims(
                        sub = account.id.toString(),
                        iss = issuer,
                        aud = audience,
                        exp = session.refreshExpiresAtEpochMillis / 1000L,
                        iat = clock.now().epochSeconds,
                        type = REFRESH_TOKEN_TYPE,
                        sid = session.id,
                        did = did,
                    ),
            )
        return IssuedAtprotoSession(
            accessJwt = accessJwt,
            refreshJwt = refreshJwt,
            sessionId = session.id,
            appPassword = session.appPasswordId != null,
        )
    }

    private suspend fun activeSession(sessionId: String): AtprotoSession? {
        val session = sessionRepository.findById(sessionId) ?: return null
        if (session.revokedAtEpochMillis != null) {
            return null
        }
        if (session.refreshExpiresAtEpochMillis <= clock.now().toEpochMilliseconds()) {
            return null
        }
        return session
    }

    private fun generateToken(payload: AtprotoSessionTokenClaims): String {
        val header = AtprotoJwtHeader()
        val encodedHeader = base64Url(Json.encodeToString(header).toByteArray(StandardCharsets.UTF_8))
        val encodedPayload = base64Url(Json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload"
        val signature = signature(signingInput)
        return "$signingInput.$signature"
    }

    private fun validateToken(
        token: String,
        expectedType: String,
    ): ValidatedAtprotoSessionToken? =
        runCatching {
            val parts = token.split('.')
            if (parts.size != JWT_SEGMENT_COUNT) {
                return null
            }
            val signingInput = "${parts[0]}.${parts[1]}"
            if (parts[2] != signature(signingInput)) {
                return null
            }
            val claims =
                Json.decodeFromString<AtprotoSessionTokenClaims>(
                    String(
                        Base64.getUrlDecoder().decode(parts[1].addBase64Padding()),
                        StandardCharsets.UTF_8,
                    ),
                )
            if (claims.type != expectedType || claims.iss != issuer || claims.aud != audience || claims.exp <= clock.now().epochSeconds) {
                return null
            }
            ValidatedAtprotoSessionToken(
                accountId = Uuid.parse(claims.sub),
                sessionId = claims.sid,
                did = claims.did,
            )
        }.onFailure { error ->
            Napier.w("Failed to validate ATProto session token", error)
        }.getOrNull()

    private fun signature(signingInput: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        return base64Url(mac.doFinal(signingInput.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private val ACCESS_TOKEN_TTL = 1.hours
        private val REFRESH_TOKEN_TTL = 30.days
        private const val ACCESS_TOKEN_TYPE = "access"
        private const val REFRESH_TOKEN_TYPE = "refresh"
        private const val JWT_SEGMENT_COUNT = 3
    }
}

/**
 * Hosted PDS session service for the standard AT Protocol account/session endpoints.
 */
@OptIn(ExperimentalUuidApi::class)
public class AtprotoPdsSessionService(
    private val accountRepository: AccountRepository,
    private val identityService: AtprotoIdentityService,
    private val passwordService: AtprotoPasswordService,
    private val sessionTokenService: AtprotoSessionTokenService,
    private val newAccountStorageMode: AccountStorageMode = AccountStorageMode.LEGACY,
) : PdsSessionService {
    override suspend fun createAccount(request: CreateAccountRequest): Result<SessionResponse> =
        runCatching {
            val password = requireNotNull(request.password?.trim()?.takeIf(String::isNotEmpty)) { "InvalidPassword" }
            val normalizedHandle = normalizeHostedHandle(request.handle)
            val internalUsername = uniqueInternalUsername(normalizedHandle.substringBefore('.'))
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = internalUsername,
                        displayName = normalizedHandle.substringBefore('.'),
                        handle = normalizedHandle,
                        email = request.email?.trim()?.takeIf(String::isNotEmpty),
                        emailVerified = !request.email.isNullOrBlank(),
                        createdAt = Clock.System.now(),
                        storageMode = newAccountStorageMode,
                    ),
                )
            passwordService.setPassword(account.id, password)
            var ensured = identityService.ensureIdentity(account)
            val recoveryKey = request.recoveryKey?.trim()?.takeIf(String::isNotEmpty)
            if (recoveryKey != null) {
                ensured =
                    identityService
                        .registerPlcRecoveryKey(ensured, recoveryKey)
                        .account
            }
            buildSessionResponse(ensured, sessionTokenService.createSession(ensured))
        }

    override suspend fun createSession(request: CreateSessionRequest): Result<SessionResponse> =
        runCatching {
            val account =
                resolveAccount(request.identifier)
                    ?: throw IllegalArgumentException("Invalid login")
            if (!passwordService.verifyPassword(account.id, request.password)) {
                throw IllegalArgumentException("Invalid login")
            }
            val ensured = identityService.ensureIdentity(account)
            if (!ensured.isActive && request.allowTakendown != true) {
                throw IllegalStateException("AccountTakedown")
            }
            buildSessionResponse(ensured, sessionTokenService.createSession(ensured))
        }

    internal suspend fun loginWithAppPassword(
        identifier: String,
        password: String,
        store: app.logdate.server.database.AtprotoAppPasswordStore,
    ): SessionResponse? {
        val match = store.match(identifier, password) ?: return null
        val account = accountRepository.findById(match.accountId) ?: return null
        if (account.did != match.did) return null
        val issued = sessionTokenService.createAppPasswordSession(account, match.passwordId, store::startSession) ?: return null
        return buildSessionResponse(account, issued)
    }

    override suspend fun getSession(accessJwt: String): Result<SessionInfoResponse> =
        runCatching {
            val account =
                sessionTokenService
                    .getAppPasswordAccessAccount(accessJwt, accountRepository, identityService)
                    ?.copy(email = null, emailVerified = false)
                    ?: sessionTokenService.getAccessAccount(accessJwt, accountRepository, identityService)
                    ?: throw IllegalArgumentException("InvalidToken")
            buildSessionInfo(account)
        }

    override suspend fun refreshSession(refreshJwt: String): Result<SessionResponse> =
        runCatching {
            val issued =
                sessionTokenService.refreshSession(refreshJwt, accountRepository, identityService)
                    ?: throw IllegalArgumentException("InvalidToken")
            val account = accountRepository.findById(Uuid.parse(issued.accessSubject)) ?: throw IllegalArgumentException("InvalidToken")
            buildSessionResponse(account, issued)
        }

    override suspend fun deleteSession(refreshJwt: String): Result<Unit> =
        runCatching {
            if (!sessionTokenService.revokeRefreshToken(refreshJwt)) {
                throw IllegalArgumentException("InvalidToken")
            }
        }

    private suspend fun resolveAccount(identifier: String): Account? {
        val normalized = identifier.trim()
        return when {
            normalized.startsWith("did:") -> accountRepository.findByDid(normalized)
            normalized.contains('@') -> accountRepository.findByEmail(normalized)
            normalized.contains('.') -> identityService.findByHandle(normalized)
            else -> accountRepository.findByUsername(normalized)
        }
    }

    private suspend fun uniqueInternalUsername(baseLabel: String): String {
        val sanitizedBase = sanitizeUsername(baseLabel)
        if (!accountRepository.usernameExists(sanitizedBase)) {
            return sanitizedBase
        }
        val suffix =
            Uuid
                .random()
                .toString()
                .substring(0, 6)
                .lowercase()
        return "$sanitizedBase-$suffix"
    }

    private fun normalizeHostedHandle(handle: String): String {
        val normalized = handle.trim().trim('.').lowercase()
        val supportedDomain = identityService.config.normalizedHandleDomain
        require(normalized.endsWith(".$supportedDomain")) { "UnsupportedDomain" }
        val label = normalized.removeSuffix(".$supportedDomain")
        require(hostedHandleLabel.matches(label) && !HandleLabels.isReserved(label)) { "InvalidHandle" }
        return normalized
    }

    private fun sanitizeUsername(value: String): String =
        value
            .lowercase()
            .replace(nonUsernameCharacters, "-")
            .trim('-')
            .ifBlank { "user" }

    private fun buildSessionInfo(account: Account): SessionInfoResponse =
        SessionInfoResponse(
            handle = requireNotNull(account.handle),
            did = AtprotoDid.require(requireNotNull(account.did)),
            didDoc = identityService.documentFor(account),
            email = account.email,
            emailConfirmed = account.emailVerified,
            emailAuthFactor = false,
            active = account.isActive,
            status = account.statusValue(),
        )

    private fun buildSessionResponse(
        account: Account,
        issued: IssuedAtprotoSession,
    ): SessionResponse =
        SessionResponse(
            accessJwt = issued.accessJwt,
            refreshJwt = issued.refreshJwt,
            handle = requireNotNull(account.handle),
            did = AtprotoDid.require(requireNotNull(account.did)),
            didDoc = identityService.documentFor(account),
            email = account.email.takeUnless { issued.appPassword },
            emailConfirmed = account.emailVerified.takeUnless { issued.appPassword },
            emailAuthFactor = false,
            active = account.isActive,
            status = account.statusValue(),
        )

    private fun Account.statusValue(): String? = if (isActive) null else "deactivated"

    private companion object {
        private val nonUsernameCharacters = Regex("[^a-z0-9-]")
        private val hostedHandleLabel = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    }
}

/**
 * Result of issuing a hosted AT Protocol session.
 */
public data class IssuedAtprotoSession(
    val accessJwt: String,
    val refreshJwt: String,
    val sessionId: String,
    internal val appPassword: Boolean = false,
) {
    public val accessSubject: String
        get() = decodeSubject(accessJwt)

    private fun decodeSubject(token: String): String =
        Json
            .decodeFromString<AtprotoSessionTokenClaims>(
                String(
                    Base64.getUrlDecoder().decode(token.split('.')[1].addBase64Padding()),
                    StandardCharsets.UTF_8,
                ),
            ).sub
}

private data class ValidatedAtprotoSessionToken(
    val accountId: Uuid,
    val sessionId: String,
    val did: String,
)

@Serializable
private data class AtprotoJwtHeader(
    val alg: String = "HS256",
    val typ: String = "JWT",
)

@Serializable
private data class AtprotoSessionTokenClaims(
    val sub: String,
    val iss: String,
    val aud: String,
    val exp: Long,
    val iat: Long,
    val type: String,
    val sid: String,
    val did: String,
)

private fun String.addBase64Padding(): String = this + "=".repeat((4 - length % 4) % 4)
