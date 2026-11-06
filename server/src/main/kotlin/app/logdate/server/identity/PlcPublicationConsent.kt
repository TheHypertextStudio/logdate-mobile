package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.database.PlcPublicationState
import app.logdate.server.database.PlcPublicationWrites
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcEncoding
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcOperations
import java.util.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Prepares and authorizes publication. Directory writes belong to the separate claimed worker. */
@OptIn(ExperimentalUuidApi::class)
internal class PlcPublicationConsent(
    private val accounts: AccountRepository,
    private val hostedOperations: HostedPlcOperationRepository,
    private val writes: PlcPublicationWrites,
    private val passkeys: PlcPublicationPasskey,
    private val pdsEndpoint: String,
    private val serverRotationDidKey: String,
    private val signingKeys: SigningKeyService,
) {
    suspend fun status(accountId: Uuid): PlcPublicationStatus? {
        val account = accounts.findById(accountId) ?: return null
        val handle = account.handle ?: return null
        val did = account.did ?: return null
        if (hostedOperations.listByDid(did).none { it.accountId == accountId }) return null
        val state =
            if (account.publicIdentityDid == did) {
                PlcUsernameState.Public
            } else {
                when (writes.readActiveState(accountId, did)) {
                    PlcPublicationState.AUTHORIZED, PlcPublicationState.UNCERTAIN -> PlcUsernameState.Pending
                    PlcPublicationState.CONFLICT -> PlcUsernameState.NeedsAttention
                    null -> PlcUsernameState.Private
                }
            }
        return PlcPublicationStatus(handle, state)
    }

    suspend fun begin(accountId: Uuid): PlcPublicationPrompt? {
        val account = accounts.findById(accountId) ?: return null
        val handle = account.handle ?: return null
        val did = account.did ?: return null
        val operations =
            try {
                hostedOperations
                    .listByDid(did)
                    .filter { it.accountId == accountId }
                    .map { Json.decodeFromString<PlcOperation>(it.operationJson) }
            } catch (_: SerializationException) {
                return null
            }
        val existing = prepare(account, operations)
        val review = existing ?: prepareTransition(account, operations) ?: return null
        val reviewId = Uuid.random()
        if (!writes.saveReview(accountId, reviewId, handle, review, appendPreparedHead = existing == null)) return null
        val options = passkeys.begin(accountId, reviewId)
        if (options.allowCredentials.isEmpty()) return null
        return PlcPublicationPrompt(reviewId, handle, review.history, options)
    }

    suspend fun authorize(
        accountId: Uuid,
        reviewId: Uuid,
        challenge: String,
        credential: PasskeyAuthenticationResponse,
    ): Boolean {
        val saved = writes.readReview(accountId, reviewId) ?: return false
        val account = accounts.findById(accountId) ?: return false
        if (account.did != saved.did || account.handle != saved.handle) return false
        val review = prepare(account, saved.operations) ?: return false
        if (!passkeys.verify(accountId, reviewId, challenge, credential)) return false
        return writes.authorize(accountId, reviewId, review)
    }

    suspend fun advanceDueReviews(worker: PlcPublicationWorker) {
        for (pending in writes.dueReviews(limit = 16)) {
            val account = accounts.findById(pending.accountId)
            val review =
                account
                    ?.takeIf { it.did == pending.review.did && it.handle == pending.review.handle }
                    ?.let { prepare(it, pending.review.operations) }
            if (review == null) {
                writes.invalidate(pending.accountId, pending.reviewId)
            } else if (writes.advance(pending.accountId, pending.reviewId, review, worker) == PlcPublicationOutcome.Inactive) {
                writes.invalidate(pending.accountId, pending.reviewId)
            }
        }
    }

    private suspend fun prepareTransition(
        account: Account,
        operations: List<PlcOperation>,
    ): PlcPublicationReview? {
        if (!account.isActive || account.moderation != null || account.publicIdentityDid == account.did) return null
        val handle = account.handle ?: return null
        val recoveryKey = account.plcRecoveryDidKey ?: return null
        val activeKey = signingKeys.findActiveKey(account.id) ?: return null
        if (activeKey.publicKeyMultibase != account.signingKeyPublic) return null
        val signingKey = "did:key:${activeKey.publicKeyMultibase}"
        val parentCids = operations.mapNotNull(PlcOperation::prev).toSet()
        val head = operations.singleOrNull { PlcEncoding.operationCid(it) !in parentCids } ?: return null
        if (signingKey !in head.rotationKeys) return null
        val curve = EcCurve.fromSigningKeyAlgorithm(activeKey.algorithm) ?: return null
        val transition =
            PlcOperations.atprotoUpdate(
                prevCid = PlcEncoding.operationCid(head),
                handle = handle,
                pdsServiceEndpoint = pdsEndpoint,
                signingKeyDidKey = signingKey,
                rotationKeys = listOf(recoveryKey, serverRotationDidKey),
            )
        val signature =
            EcKeySupport.signSha256(
                privateKey = signingKeys.decryptPrivateKey(activeKey),
                curve = curve,
                payload = PlcEncoding.encodeUnsigned(transition),
            )
        return prepare(account, operations + transition.signed(Base64.getUrlEncoder().withoutPadding().encodeToString(signature)))
    }

    private fun prepare(
        account: Account,
        operations: List<PlcOperation>,
    ): PlcPublicationReview? {
        val did = account.did?.let { AtprotoDid.parse(it).getOrNull() } ?: return null
        if (!account.isActive || account.moderation != null || account.publicIdentityDid == account.did) return null
        val handle = account.handle ?: return null
        val signingKey = account.signingKeyPublic ?: return null
        val recoveryKey = account.plcRecoveryDidKey ?: return null
        return try {
            PlcPublicationReview.prepare(did, handle, pdsEndpoint, "did:key:$signingKey", recoveryKey, serverRotationDidKey, operations)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
internal data class PlcPublicationPrompt(
    val reviewId: Uuid,
    val username: String,
    val history: PlcPublicationHistory,
    val passkey: PasskeyAuthenticationOptions,
)

internal enum class PlcUsernameState(
    val wireValue: String,
) {
    Private("private"),
    Pending("pending"),
    Public("public"),
    NeedsAttention("needs_attention"),
}

internal data class PlcPublicationStatus(
    val username: String,
    val state: PlcUsernameState,
)
