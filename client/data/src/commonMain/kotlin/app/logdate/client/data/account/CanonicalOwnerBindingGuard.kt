package app.logdate.client.data.account

import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.sameOwnerId
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.ServerProtocolFeature
import kotlinx.coroutines.CancellationException

/**
 * Enforces LogDate's single-identity rule: an installation is bound to at most one LogDate Cloud
 * account, and every passkey/Google sign-in or restore must agree with that binding.
 */
internal class CanonicalOwnerBindingGuard(
    private val canonicalOwnerProvider: CanonicalOwnerProvider,
    private val hasLocalData: suspend () -> Boolean,
    private val configRepository: LogDateConfigRepository,
    private val adoptCanonicalOwner: (suspend (String) -> Boolean)? = null,
) {
    enum class OwnerBinding { ALLOWED, NEEDS_LOCAL_DATA_CONSENT, REFUSED }

    /**
     * An installation already claimed by an account stays with it. One that no account has claimed
     * can be adopted -- but if it holds entries written offline, those entries become part of the
     * account, so the user is asked first rather than told afterwards.
     */
    suspend fun localDataConsentRequired(): Boolean =
        !canonicalOwnerProvider.hasBoundOwner() &&
            try {
                hasLocalData()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                true
            }

    suspend fun inspectOwnerBindingFor(
        account: LogDateAccount,
        adoptLocalData: Boolean,
    ): OwnerBinding {
        if (canonicalOwnerProvider.hasBoundOwner()) {
            return if (sameOwnerId(
                    account.id.toString(),
                    canonicalOwnerProvider.getCanonicalOwnerId(),
                )
            ) {
                OwnerBinding.ALLOWED
            } else {
                OwnerBinding.REFUSED
            }
        }
        if (!adoptLocalData) {
            val containsData =
                try {
                    hasLocalData()
                } catch (
                    canceled: CancellationException,
                ) {
                    throw canceled
                } catch (_: Exception) {
                    true
                }
            if (containsData) return OwnerBinding.NEEDS_LOCAL_DATA_CONSENT
        }
        return OwnerBinding.ALLOWED
    }

    suspend fun ownerBindingFor(
        account: LogDateAccount,
        adoptLocalData: Boolean,
    ): OwnerBinding {
        val eligible = inspectOwnerBindingFor(account, adoptLocalData)
        if (eligible != OwnerBinding.ALLOWED) return eligible
        return if (adoptOwner(account.id.toString())) OwnerBinding.ALLOWED else OwnerBinding.REFUSED
    }

    private suspend fun adoptOwner(ownerId: String): Boolean =
        adoptCanonicalOwner?.invoke(ownerId) ?: canonicalOwnerProvider.adoptRemoteOwnerIfUninitialized(ownerId)

    suspend fun belongsToCanonicalOwner(account: LogDateAccount): Boolean =
        ownerBindingFor(account, adoptLocalData = false) == OwnerBinding.ALLOWED

    suspend fun sessionBelongsToCanonicalOwner(session: UserSession): Boolean =
        runCatching { sameOwnerId(session.accountId, canonicalOwnerProvider.getCanonicalOwnerId()) }.getOrDefault(false)

    fun requireCanonicalOwnerBinding() {
        if (!currentServerSupportsCanonicalOwnerBinding()) {
            throw UnsupportedCanonicalOwnerBindingException()
        }
    }

    fun currentServerSupportsCanonicalOwnerBinding(): Boolean {
        if (configRepository.getCurrentBackendUrl() == DefaultLogDateConfigRepository.DEFAULT_BACKEND_URL) {
            return true
        }
        return configRepository
            .getCurrentServerDescriptor()
            ?.hasProtocolFeature(ServerProtocolFeature.CANONICAL_OWNER_BINDING_V1) == true
    }
}
