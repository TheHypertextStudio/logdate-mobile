package app.logdate.server.identity

import app.logdate.server.auth.Account
import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcDirectoryException
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcTombstone
import kotlin.uuid.ExperimentalUuidApi

/**
 * Where one account's identity stands against the PLC directory.
 *
 * [needsWork] states are what the publish command acts on; [needsAttention] states are ones it
 * can't fix by itself and an operator has to look at.
 */
enum class IdentityPublicationState(
    val code: String,
    val needsWork: Boolean = false,
    val needsAttention: Boolean = false,
) {
    /** The directory has the identity and it matches what LogDate serves. */
    PUBLISHED("published"),

    /** The directory has the identity and it matches, but LogDate never stored the directory's CID. */
    PUBLISHED_UNRECORDED("published_unrecorded", needsWork = true),

    /** LogDate signed and stored a genesis operation that the directory has never seen. */
    UNPUBLISHED("unpublished", needsWork = true),

    /** The account has no DID yet. LogDate creates one the first time the account needs it. */
    NOT_PROVISIONED("not_provisioned", needsWork = true),

    /** The directory has the identity, but its handle, keys or PDS endpoint differ from what LogDate serves. */
    MISMATCH("mismatch", needsWork = true),

    /** The identity uses `did:web`, which LogDate serves itself; nothing goes to the PLC directory. */
    NOT_PLC("not_plc"),

    /** The directory has the identity and LogDate's key can no longer sign for it; its owner controls it. */
    CONTROLLED_ELSEWHERE("controlled_elsewhere"),

    /** The account has a `did:plc` but no stored genesis operation that derives it, so there is nothing to send. */
    MISSING_GENESIS("missing_genesis", needsAttention = true),

    /** The account has a DID but is missing its handle or signing key. */
    INCOMPLETE("incomplete", needsAttention = true),

    /** The directory's latest entry for the identity is a tombstone: the identity was deactivated for good. */
    TOMBSTONED("tombstoned", needsAttention = true),

    /** The directory couldn't be read, so the state is unknown. */
    DIRECTORY_ERROR("directory_error", needsAttention = true),
}

/** A part of the directory's copy of an identity that differs from what LogDate serves. */
enum class IdentityDifference(
    val code: String,
) {
    HANDLE("handle"),
    SIGNING_KEY("signing_key"),
    PDS_ENDPOINT("pds_endpoint"),
    RECOVERY_KEY("recovery_key"),
}

/** The result of comparing one account's identity with the PLC directory. */
data class IdentityInspection(
    val account: Account,
    val state: IdentityPublicationState,
    val differences: Set<IdentityDifference> = emptySet(),
    val storedGenesis: StoredHostedPlcOperation? = null,
    val directoryCid: String? = null,
    val reason: String? = null,
)

/** What the publish command did, or would do in a dry run, for one account. */
enum class IdentityPublishAction(
    val code: String,
) {
    NONE("none"),
    PUBLISH_GENESIS("publish_genesis"),
    PROVISION_AND_PUBLISH("provision_and_publish"),
    RECORD_CID("record_cid"),
    RECONCILE("reconcile"),
}

/** The outcome of publishing one account's identity. */
data class IdentityPublishOutcome(
    val before: IdentityInspection,
    val action: IdentityPublishAction,
    val after: IdentityPublicationState,
    val failure: IdentityPublishFailure? = null,
) {
    val failed: Boolean get() = failure != null
}

/** Why publishing one account's identity failed. */
data class IdentityPublishFailure(
    val code: String,
    val detail: String,
)

/**
 * Compares accounts' `did:plc` identities with the PLC directory and publishes the ones the
 * directory doesn't have yet. Every step is safe to repeat: an identity the directory already has
 * is only ever compared, never sent again, so an interrupted run can simply be run again.
 */
@OptIn(ExperimentalUuidApi::class)
class IdentityPublishingService(
    private val identityService: AtprotoIdentityService,
    private val plcIdentityService: PlcIdentityService,
    private val operationRepository: HostedPlcOperationRepository,
    private val directory: PlcDirectoryClient,
    private val config: AtprotoIdentityConfig,
) {
    /** Reads the directory's copy of [account]'s identity and compares it with LogDate's. Never writes. */
    suspend fun inspect(account: Account): IdentityInspection {
        val did = account.did ?: return IdentityInspection(account, IdentityPublicationState.NOT_PROVISIONED)
        if (!did.startsWith("did:plc:")) return IdentityInspection(account, IdentityPublicationState.NOT_PLC)
        val parsedDid =
            AtprotoDid.parse(did).getOrNull()
                ?: return IdentityInspection(account, IdentityPublicationState.INCOMPLETE, reason = "The stored DID is not valid")
        if (account.handle == null || account.signingKeyPublic == null) {
            return IdentityInspection(
                account,
                IdentityPublicationState.INCOMPLETE,
                reason = "The account has a DID but no handle or signing key",
            )
        }
        val storedGenesis = findStoredGenesis(account, did)
        val auditLog =
            try {
                directory.getAuditLog(parsedDid).getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (notFound: PlcDirectoryException) {
                if (!notFound.isNotFound) return directoryError(account, storedGenesis, notFound)
                return if (storedGenesis != null) {
                    IdentityInspection(account, IdentityPublicationState.UNPUBLISHED, storedGenesis = storedGenesis)
                } else {
                    IdentityInspection(
                        account,
                        IdentityPublicationState.MISSING_GENESIS,
                        reason = "No stored genesis operation derives this DID",
                    )
                }
            } catch (exception: Exception) {
                return directoryError(account, storedGenesis, exception)
            }
        return compareWithDirectory(account, storedGenesis, auditLog)
    }

    /**
     * Brings the inspected account's identity into the directory, then inspects it again to confirm
     * the result. Requires `ATPROTO_PLC_PUBLISH_ENABLED`; [reconcile] additionally allows sending an
     * update when the directory's copy differs from what LogDate serves. [beforeWrite] runs before
     * anything is sent, so the caller can pace writes.
     */
    suspend fun publish(
        inspection: IdentityInspection,
        reconcile: Boolean,
        beforeWrite: suspend () -> Unit = {},
    ): IdentityPublishOutcome {
        val action = plannedAction(inspection, reconcile)
        if (action == IdentityPublishAction.NONE) {
            return IdentityPublishOutcome(inspection, action, inspection.state)
        }
        return try {
            val after = inspect(perform(inspection, action, beforeWrite))
            val failure =
                if (after.state == IdentityPublicationState.PUBLISHED) {
                    null
                } else {
                    IdentityPublishFailure("not_confirmed", "After ${action.code} the identity is ${after.state.code}")
                }
            IdentityPublishOutcome(inspection, action, after.state, failure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            IdentityPublishOutcome(inspection, action, inspection.state, failureFor(exception))
        }
    }

    /** The action [publish] takes for [inspection]; a dry run reports this without doing it. */
    fun plannedAction(
        inspection: IdentityInspection,
        reconcile: Boolean,
    ): IdentityPublishAction =
        when (inspection.state) {
            IdentityPublicationState.UNPUBLISHED -> {
                IdentityPublishAction.PUBLISH_GENESIS
            }

            IdentityPublicationState.NOT_PROVISIONED -> {
                if (config.hostedAccountDidMethod == HostedAccountDidMethod.PLC) {
                    IdentityPublishAction.PROVISION_AND_PUBLISH
                } else {
                    IdentityPublishAction.NONE
                }
            }

            IdentityPublicationState.PUBLISHED_UNRECORDED -> {
                IdentityPublishAction.RECORD_CID
            }

            IdentityPublicationState.MISMATCH -> {
                if (reconcile) IdentityPublishAction.RECONCILE else IdentityPublishAction.NONE
            }

            else -> {
                IdentityPublishAction.NONE
            }
        }

    /** Carries out [action] and returns the account as it stands afterwards. */
    private suspend fun perform(
        inspection: IdentityInspection,
        action: IdentityPublishAction,
        beforeWrite: suspend () -> Unit,
    ): Account {
        val account = inspection.account
        when (action) {
            IdentityPublishAction.PUBLISH_GENESIS -> {
                beforeWrite()
                plcIdentityService.publishStoredGenesis(requireNotNull(inspection.storedGenesis))
            }

            IdentityPublishAction.PROVISION_AND_PUBLISH -> {
                beforeWrite()
                return identityService.ensureIdentity(account)
            }

            IdentityPublishAction.RECORD_CID -> {
                operationRepository.recordPublishedCid(requireNotNull(inspection.storedGenesis).id, requireNotNull(inspection.directoryCid))
            }

            IdentityPublishAction.RECONCILE -> {
                beforeWrite()
                plcIdentityService.reconcileHostedDid(
                    accountId = account.id,
                    did = AtprotoDid.require(requireNotNull(account.did)),
                    handle = requireNotNull(account.handle),
                    recoveryDidKey = account.plcRecoveryDidKey,
                )
            }

            IdentityPublishAction.NONE -> {
                Unit
            }
        }
        return account
    }

    private suspend fun findStoredGenesis(
        account: Account,
        did: String,
    ): StoredHostedPlcOperation? =
        operationRepository
            .listByDid(did)
            .filter { it.accountId == account.id && it.prevCid == null && it.operationType == PlcOperation.TYPE }
            .firstOrNull { stored -> runCatching { plcIdentityService.storedGenesisOperation(stored) }.isSuccess }

    private fun compareWithDirectory(
        account: Account,
        storedGenesis: StoredHostedPlcOperation?,
        auditLog: List<PlcIndexedOperation>,
    ): IdentityInspection {
        val latest =
            auditLog.lastOrNull { !it.nullified }
                ?: return IdentityInspection(
                    account,
                    IdentityPublicationState.DIRECTORY_ERROR,
                    storedGenesis = storedGenesis,
                    reason = "The audit log is empty",
                )
        val operation =
            when (val entry = latest.operation) {
                is PlcTombstone -> return IdentityInspection(account, IdentityPublicationState.TOMBSTONED, storedGenesis = storedGenesis)
                is PlcOperation -> entry
            }
        val signingDidKey = didKeyFor(requireNotNull(account.signingKeyPublic))
        if (signingDidKey !in operation.rotationKeys) {
            return IdentityInspection(account, IdentityPublicationState.CONTROLLED_ELSEWHERE, storedGenesis = storedGenesis)
        }
        val differences = differencesFrom(account, operation, signingDidKey)
        val genesisCid = storedGenesis?.let { stored -> genesisCidIn(auditLog, stored) }
        val state =
            when {
                differences.isNotEmpty() -> IdentityPublicationState.MISMATCH
                storedGenesis != null && storedGenesis.cid == null && genesisCid != null -> IdentityPublicationState.PUBLISHED_UNRECORDED
                else -> IdentityPublicationState.PUBLISHED
            }
        return IdentityInspection(
            account = account,
            state = state,
            differences = differences,
            storedGenesis = storedGenesis,
            directoryCid = genesisCid,
        )
    }

    private fun differencesFrom(
        account: Account,
        operation: PlcOperation,
        signingDidKey: String,
    ): Set<IdentityDifference> =
        buildSet {
            if (operation.alsoKnownAs.firstOrNull() != "at://${account.handle}") add(IdentityDifference.HANDLE)
            if (operation.verificationMethods["atproto"] != signingDidKey) add(IdentityDifference.SIGNING_KEY)
            val pds = operation.services["atproto_pds"]
            if (pds?.endpoint != config.pdsServiceEndpoint || pds.type != "AtprotoPersonalDataServer") add(IdentityDifference.PDS_ENDPOINT)
            val recovery = account.plcRecoveryDidKey
            if (recovery != null && recovery !in operation.rotationKeys) add(IdentityDifference.RECOVERY_KEY)
        }

    private fun genesisCidIn(
        auditLog: List<PlcIndexedOperation>,
        stored: StoredHostedPlcOperation,
    ): String? {
        val signature = runCatching { plcIdentityService.storedGenesisOperation(stored).sig }.getOrNull() ?: return null
        return auditLog.firstOrNull { (it.operation as? PlcOperation)?.sig == signature }?.cid
    }

    private fun directoryError(
        account: Account,
        storedGenesis: StoredHostedPlcOperation?,
        exception: Exception,
    ): IdentityInspection =
        IdentityInspection(
            account,
            IdentityPublicationState.DIRECTORY_ERROR,
            storedGenesis = storedGenesis,
            reason = describe(exception),
        )

    private fun failureFor(exception: Exception): IdentityPublishFailure {
        val directoryFailure = exception as? PlcDirectoryException
        val code =
            when {
                exception is PlcPublicationUnconfirmedException -> "not_confirmed"
                directoryFailure?.statusCode == 429 -> "directory_rate_limited"
                directoryFailure?.statusCode in 400..499 -> "directory_rejected"
                directoryFailure != null -> "directory_unreachable"
                exception is IdentityLifecycleConflictException -> "publishing_disabled"
                else -> "unexpected_error"
            }
        return IdentityPublishFailure(code, describe(exception))
    }

    private fun describe(exception: Exception): String {
        val directoryFailure = exception as? PlcDirectoryException
        val message = exception.message ?: exception::class.simpleName.orEmpty()
        return directoryFailure?.directoryMessage?.let { "$message: $it" } ?: message
    }
}
