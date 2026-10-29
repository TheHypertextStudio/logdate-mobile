@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.hasSupportedSessionIdentity
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectChallenge
import app.logdate.shared.model.AtprotoDisconnectReceipt
import app.logdate.shared.model.AtprotoDisconnectResponse
import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthConditionalRevocation
import studio.hypertext.atproto.oauth.client.OAuthRevocationException
import studio.hypertext.atproto.oauth.client.OAuthSessionEndedException
import studio.hypertext.atproto.oauth.client.OAuthSessionNotFoundException
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal class AtprotoConnectionService(
    private val accounts: AccountRepository,
    private val connections: AtprotoConnectionRepository,
    private val passkeys: AtprotoDisconnectPasskeys,
    private val methods: AccountSignInMethods,
    private val credentialLock: AccountCredentialMutationLock,
    private val provider: AtprotoConnectionProvider,
    private val clock: Clock = Clock.System,
) {
    suspend fun status(accountId: Uuid): AtprotoConnectionSnapshot =
        guarded {
            val account = requireAccount(accountId)
            if (account.identityKind != AccountIdentityKind.EXTERNAL || !account.hasSupportedSessionIdentity()) {
                return@guarded snapshot(account, false)
            }
            val did = externalDid(account)
            val available =
                try {
                    provider.restore(did)
                } catch (_: OAuthSessionEndedException) {
                    false
                } catch (_: OAuthSessionNotFoundException) {
                    false
                }
            val current = requireAccount(accountId)
            if (externalDid(current) != did) throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
            snapshot(current, available)
        }

    suspend fun beginDisconnect(
        accountId: Uuid,
        expectedRevision: Long,
    ): AtprotoDisconnectChallenge =
        guarded {
            credentialLock.withLock(accountId) {
                val account = requireAccount(accountId)
                val did = externalDid(account)
                val current = status(accountId)
                if (current.revision != expectedRevision) throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
                requireIndependentMethod(accountId)
                if (!current.canDisconnect) throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
                val expires = clock.now() + 5.minutes
                val options = passkeys.begin(accountId)
                val id = Base64.getUrlEncoder().withoutPadding().encodeToString(JvmOAuthCrypto.randomBytes(32))
                connections.createDecision(DisconnectDecision(id, accountId, did, expectedRevision, hash(options.challenge), expires))
                AtprotoDisconnectChallenge(id, expectedRevision, options, expires.toString())
            }
        }

    suspend fun disconnect(
        accountId: Uuid,
        decisionId: String,
        proof: PasskeyStepUpProof,
    ): AtprotoDisconnectResponse =
        guarded {
            credentialLock.withLock(accountId) {
                val account = requireAccount(accountId)
                externalDid(account)
                connections.receipt(accountId, decisionId)?.let { return@withLock response(accountId, it) }
                val decision = pending(account, decisionId)
                requireIndependentMethod(accountId)
                authorize(accountId, decision, proof)
                performDisconnect(accountId, decision)
            }
        }

    private suspend fun authorize(
        accountId: Uuid,
        decision: DisconnectDecision,
        proof: PasskeyStepUpProof,
    ) {
        if (connections.authorizedDecision(accountId, decision.id, clock.now()) != null) return
        if (hash(proof.challenge) != decision.challengeHash || !passkeys.verify(accountId, proof)) {
            throw AtprotoConnectionFailure(ConnectionFailureKind.PROOF_INVALID)
        }
        if (connections.authorizeDecision(accountId, decision.id, decision.challengeHash, clock.now()) == null) {
            throw changedOrExpired(decision)
        }
    }

    private suspend fun performDisconnect(
        accountId: Uuid,
        decision: DisconnectDecision,
    ): AtprotoDisconnectResponse {
        val outcome =
            try {
                provider.revokeIf(decision.did, beforeRevoke = {
                    val account = requireAccount(accountId)
                    if (externalDid(account) != decision.did) throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
                    requireIndependentMethod(accountId)
                    if (connections.authorizedDecision(accountId, decision.id, clock.now()) == null) {
                        throw changedOrExpired(decision)
                    }
                    true
                }, removeLocal = { connections.completeDisconnect(decision.id) })
            } catch (_: OAuthRevocationException) {
                null
            }
        if (outcome == OAuthConditionalRevocation.SKIPPED) throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
        val receipt = connections.receipt(accountId, decision.id) ?: throw AtprotoConnectionFailure(ConnectionFailureKind.UNAVAILABLE)
        val observed = if (outcome == OAuthConditionalRevocation.CONFIRMED) connections.confirmRemote(decision.id) else receipt
        return response(accountId, observed)
    }

    private suspend fun pending(
        account: Account,
        id: String,
    ): DisconnectDecision {
        val decision = connections.pendingDecision(account.id, id) ?: throw AtprotoConnectionFailure(ConnectionFailureKind.PROOF_INVALID)
        if (decision.did != externalDid(account)) throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
        if (clock.now() >= decision.expiresAt) throw AtprotoConnectionFailure(ConnectionFailureKind.PROOF_EXPIRED)
        if ((connections.snapshot(decision.did)?.revision ?: 0L) != decision.expectedRevision) {
            throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
        }
        return decision
    }

    private suspend fun response(
        accountId: Uuid,
        receipt: DisconnectReceipt,
    ): AtprotoDisconnectResponse {
        val account = requireAccount(accountId)
        val available = connections.snapshot(externalDid(account))?.state == AtprotoConnectionState.CONNECTED
        return AtprotoDisconnectResponse(
            AtprotoDisconnectReceipt(
                receipt.decisionId,
                receipt.disconnectedRevision,
                receipt.completedAt.toString(),
                receipt.remoteConfirmed,
            ),
            snapshot(account, available),
        )
    }

    private suspend fun snapshot(
        account: Account,
        available: Boolean,
    ): AtprotoConnectionSnapshot {
        if (account.identityKind != AccountIdentityKind.EXTERNAL || !account.hasSupportedSessionIdentity()) {
            val state =
                if (account.identityKind == AccountIdentityKind.HOSTED) {
                    AtprotoConnectionState.DISCONNECTED
                } else {
                    AtprotoConnectionState.RECONNECT_REQUIRED
                }
            return AtprotoConnectionSnapshot(state, 0, clock.now().toString(), eligible = false, canDisconnect = false)
        }
        val saved = connections.snapshot(externalDid(account))
        val state =
            if (saved?.state == AtprotoConnectionState.CONNECTED && !available) {
                AtprotoConnectionState.RECONNECT_REQUIRED
            } else {
                saved?.state ?: AtprotoConnectionState.RECONNECT_REQUIRED
            }
        val observed = saved?.takeIf { it.state == state }?.observedAt ?: clock.now()
        val canDisconnect =
            state == AtprotoConnectionState.CONNECTED &&
                available &&
                passkeys.credentialsFor(account.id).isNotEmpty() &&
                methods.hasUsableMethod(account.id)
        return AtprotoConnectionSnapshot(state, saved?.revision ?: 0, observed.toString(), eligible = true, canDisconnect = canDisconnect)
    }

    private suspend fun requireIndependentMethod(accountId: Uuid) {
        if (passkeys.credentialsFor(accountId).isEmpty() || !methods.hasUsableMethod(accountId)) {
            throw AtprotoConnectionFailure(ConnectionFailureKind.LAST_SIGNIN_FACTOR)
        }
    }

    private suspend fun requireAccount(accountId: Uuid): Account =
        accounts.findById(accountId)?.takeIf { it.isActive && it.moderation == null }
            ?: throw AtprotoConnectionFailure(ConnectionFailureKind.INELIGIBLE)

    private fun externalDid(account: Account): AtprotoDid {
        if (account.identityKind != AccountIdentityKind.EXTERNAL || !account.hasSupportedSessionIdentity()) {
            throw AtprotoConnectionFailure(ConnectionFailureKind.INELIGIBLE)
        }
        return AtprotoDid.parse(account.did.orEmpty()).getOrNull() ?: throw AtprotoConnectionFailure(ConnectionFailureKind.INELIGIBLE)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (bounded: AtprotoConnectionFailure) {
            throw bounded
        } catch (_: ConnectionChanged) {
            throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
        } catch (_: Exception) {
            throw AtprotoConnectionFailure(ConnectionFailureKind.UNAVAILABLE)
        }

    private fun changedOrExpired(decision: DisconnectDecision): AtprotoConnectionFailure =
        AtprotoConnectionFailure(
            if (clock.now() >= decision.expiresAt) ConnectionFailureKind.PROOF_EXPIRED else ConnectionFailureKind.CHANGED,
        )

    private fun hash(challenge: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(challenge.encodeToByteArray()))
}
