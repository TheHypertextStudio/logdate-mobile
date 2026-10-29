package app.logdate.server.atproto.firehose

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import studio.hypertext.atproto.identity.AtprotoDid

/**
 * Whether a repository other apps know about can be fetched from this server right now, and if not,
 * why not. The reasons are the statuses `com.atproto.sync.getRepoStatus` and the `#account` event use.
 */
sealed interface RepoStanding {
    /** The account is in good standing and its repository is shown. */
    data object Active : RepoStanding

    /**
     * The account is not active here. [status] is `deactivated`, `suspended`, `takendown` or `deleted`.
     */
    data class Inactive(
        val status: String,
    ) : RepoStanding {
        /** True when the account was deleted, so there is no repository left to ask about. */
        val deleted: Boolean get() = status == AtprotoRepoStatus.DELETED
    }
}

/** The account statuses the AT Protocol defines for a repository that is not active. */
object AtprotoRepoStatus {
    const val DEACTIVATED: String = "deactivated"
    const val SUSPENDED: String = "suspended"
    const val TAKENDOWN: String = "takendown"
    const val DELETED: String = "deleted"
}

/**
 * Decides whether an account's repository may be shown to people other than its owner.
 *
 * A repository is shown only while the account is active and in good standing, keeps its journal in
 * its personal space, and has an identity other apps can look up. An account on legacy storage keeps
 * every entry, journal and draft as a record in the same repository, so it is never shown, not even
 * for the records that would be public: its repository is erased when the journal moves into a
 * personal space, and what other apps held of it would be left pointing at nothing. An account whose
 * `did:plc` has not been published to the PLC directory is not part of the network yet, so nothing
 * about it is announced.
 */
interface PublicRepoGate {
    /** True when the repository of [did] may be shown, as far as the account is concerned. */
    suspend fun mayShow(did: AtprotoDid): Boolean

    /**
     * The standing of [did], or `null` when the server shows nothing about it: an account that is not
     * public, or a DID this server never announced. An account that was announced and has since been
     * deleted is [RepoStanding.Inactive] with status `deleted`.
     */
    suspend fun standing(did: AtprotoDid): RepoStanding?
}

/** The gate that reads the account's storage mode and state from [accounts] and past announcements from [events]. */
class AccountPublicRepoGate(
    private val accounts: AccountRepository,
    private val identityConfig: AtprotoIdentityConfig,
    private val events: RepoEventStore,
) : PublicRepoGate {
    override suspend fun mayShow(did: AtprotoDid): Boolean = accounts.findByDid(did.toString())?.let(::mayShow) == true

    override suspend fun standing(did: AtprotoDid): RepoStanding? {
        val account = accounts.findByDid(did.toString()) ?: return deletedIfAnnounced(did)
        if (!isPublicAccount(account)) return null
        val moderation = account.moderation
        return when {
            moderation != null -> RepoStanding.Inactive(moderation.wireValue)
            !account.isActive -> RepoStanding.Inactive(AtprotoRepoStatus.DEACTIVATED)
            else -> RepoStanding.Active
        }
    }

    /** True when [account] is active, in good standing, keeps its journal in its personal space, and can be looked up. */
    fun mayShow(account: Account): Boolean = isPublicAccount(account) && account.isActive && account.moderation == null

    /**
     * True only for the exact identity its owner made public. A hosted `did:plc` also requires
     * directory publication; neither a resolvable `did:web` nor a server flag establishes consent.
     */
    fun hasPublicIdentity(account: Account): Boolean {
        val did = account.did ?: return false
        if (account.identityKind != AccountIdentityKind.HOSTED || account.publicIdentityDid != did) return false
        return did.startsWith("did:web:") || (did.startsWith("did:plc:") && identityConfig.publishesPlcOperations)
    }

    private fun isPublicAccount(account: Account): Boolean =
        account.storageMode == AccountStorageMode.PERSONAL_SPACE_V1 && hasPublicIdentity(account)

    private suspend fun deletedIfAnnounced(did: AtprotoDid): RepoStanding? =
        if (events.hasEvents(did)) RepoStanding.Inactive(AtprotoRepoStatus.DELETED) else null
}
