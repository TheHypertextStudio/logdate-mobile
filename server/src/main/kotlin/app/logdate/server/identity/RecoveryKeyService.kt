package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Registers, replaces and uses the recovery key a person holds for their hosted `did:plc` identity.
 *
 * Two requests for the same account must not both build on the same operation, or the identity's
 * history would fork and the account could name one key while the log names another. Three things
 * prevent that:
 *
 * - Every change runs under a per-account lock and starts from the account as stored, read again
 *   inside the lock, so requests on one server instance take turns.
 * - The operation log refuses a second operation on the same head, so two server instances
 *   cannot fork it either; the loser answers `IDENTITY_CHANGED`.
 * - The account is written with [AccountRepository.updateIdentityIfUnchanged], which never
 *   re-creates a deleted account and never overwrites an identity change made in between.
 *
 * Each change appends its operation first, then activates the server key it installs, then writes
 * the account. If a later step fails, repeating the same request finds the operation already at
 * the head of the log and finishes the remaining steps instead of appending again.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class RecoveryKeyService(
    private val accountRepository: AccountRepository,
    private val signingKeyService: SigningKeyService,
    private val plcIdentityService: PlcIdentityService,
    private val config: AtprotoIdentityConfig,
    private val ensureIdentity: suspend (Account) -> Account,
) {
    private val changeLocks = Array(CHANGE_LOCK_STRIPES) { Mutex() }

    /**
     * Lists [recoveryDidKey] first in the identity's rotation keys, ahead of the server's key.
     * Registering the key that is already registered changes nothing; a different key is refused
     * with `RECOVERY_KEY_ALREADY_SET`, because replacing it goes through [replace].
     */
    suspend fun register(
        accountId: Uuid,
        recoveryDidKey: String,
    ): RegisteredPlcRecoveryKey =
        withChangeLock(accountId) {
            val target = currentIdentity(accountId)
            when (target.account.plcRecoveryDidKey) {
                recoveryDidKey -> {
                    RegisteredPlcRecoveryKey(account = target.account, recoveryDidKey = recoveryDidKey)
                }

                null -> {
                    installRecoveryKey(target, recoveryDidKey)
                }

                else -> {
                    throw IdentityLifecycleConflictException(
                        "A different recovery key is already registered for this identity",
                        code = AtprotoIdentityService.RECOVERY_KEY_ALREADY_SET,
                    )
                }
            }
        }

    /**
     * Puts [recoveryDidKey] in place of the registered recovery key, for a person who lost the phrase
     * the old key came from. The server signs the change with its own key, which ranks below the old
     * recovery key, so once the identity is published the old key can still undo it for 72 hours.
     */
    suspend fun replace(
        accountId: Uuid,
        recoveryDidKey: String,
    ): RegisteredPlcRecoveryKey =
        withChangeLock(accountId) {
            val target = currentIdentity(accountId)
            when (target.account.plcRecoveryDidKey) {
                null -> {
                    throw IdentityLifecycleConflictException(
                        "No recovery key is registered for this identity",
                        code = AtprotoIdentityService.RECOVERY_KEY_NOT_SET,
                    )
                }

                recoveryDidKey -> {
                    RegisteredPlcRecoveryKey(account = target.account, recoveryDidKey = recoveryDidKey)
                }

                else -> {
                    installRecoveryKey(target, recoveryDidKey)
                }
            }
        }

    /**
     * Step one of replacing the server's key with the person's recovery key: prepares a fresh server
     * key and the operation that installs it, for the recovery key to sign on the device. The fresh
     * key comes back sealed in [PreparedIdentityRecovery.preparedKey] together with a digest of the
     * operation, so nothing is stored until [completeRecovery].
     */
    suspend fun prepareRecovery(accountId: Uuid): PreparedIdentityRecovery {
        val target = currentIdentity(accountId)
        val recoveryDidKey = target.requireRecoveryKey()
        val nextKey = signingKeyService.prepareKey(accountId)
        val prepared =
            plcIdentityService.prepareHostedRecoveryImport(
                accountId = accountId,
                did = target.did,
                handle = target.handle,
                recoveryDidKey = recoveryDidKey,
                nextSigningKeyDidKey = didKeyFor(nextKey.publicKeyMultibase),
            )
        return PreparedIdentityRecovery(
            did = target.did.toString(),
            handle = target.handle,
            recoveryDidKey = recoveryDidKey,
            nextSigningKeyDidKey = prepared.nextSigningKeyDidKey,
            unsignedOperation = prepared.unsignedOperation,
            signingPayloadBase64Url = encodeBase64Url(prepared.signingPayload),
            preparedKey =
                signingKeyService.sealPreparedKey(
                    key = nextKey,
                    prevCid = requireNotNull(prepared.unsignedOperation.prev),
                    operationDigest = digest(prepared.signingPayload),
                ),
        )
    }

    /**
     * Step two: rebuilds the operation from [prepareRecovery], refuses it as `IDENTITY_CHANGED` when
     * anything about the identity changed in between, checks the recovery key's [signature], appends
     * it, and makes the prepared key the server's key. The old key loses all authority.
     */
    suspend fun completeRecovery(
        accountId: Uuid,
        preparedKey: String,
        signature: String,
    ): ImportedIdentitySigningKey =
        withChangeLock(accountId) {
            val target = currentIdentity(accountId)
            val recoveryDidKey = target.requireRecoveryKey()
            val opened = signingKeyService.openPreparedKey(accountId, preparedKey)
            val nextSigningKeyDidKey = didKeyFor(opened.key.publicKeyMultibase)
            val head = plcIdentityService.head(target.did)
            if (!isAppendedRecovery(head, opened, nextSigningKeyDidKey, signature)) {
                if (head.cid != opened.prevCid) throw identityChanged()
                val prepared =
                    plcIdentityService.prepareHostedRecoveryImport(
                        accountId = accountId,
                        did = target.did,
                        handle = target.handle,
                        recoveryDidKey = recoveryDidKey,
                        nextSigningKeyDidKey = nextSigningKeyDidKey,
                    )
                // The handle or the service may have changed since step one; the signature would then be over another operation.
                if (digest(prepared.signingPayload) != opened.operationDigest) throw identityChanged()
                plcIdentityService.publishClientSignedRecoveryImport(
                    accountId = accountId,
                    did = target.did,
                    recoveryDidKey = recoveryDidKey,
                    preparedOperation = prepared,
                    signature = signature,
                )
            }
            val activeKey = activate(accountId, opened.key)
            val updated = writeIdentity(target.account, target.account.copy(signingKeyPublic = activeKey.publicKeyMultibase))
            ImportedIdentitySigningKey(account = updated, activeKey = activeKey)
        }

    private suspend fun installRecoveryKey(
        target: CurrentIdentity,
        recoveryDidKey: String,
    ): RegisteredPlcRecoveryKey {
        val head = plcIdentityService.head(target.did)
        // A retry after the operation was appended but the account was not written finds it at the head.
        val operation =
            if (head.operation.rotationKeys.firstOrNull() == recoveryDidKey) {
                null
            } else {
                plcIdentityService
                    .updateHostedRecoveryKey(
                        accountId = target.account.id,
                        did = target.did,
                        handle = target.handle,
                        recoveryDidKey = recoveryDidKey,
                    ).operation
            }
        val updated = writeIdentity(target.account, target.account.copy(plcRecoveryDidKey = recoveryDidKey))
        return RegisteredPlcRecoveryKey(account = updated, recoveryDidKey = recoveryDidKey, plcOperation = operation)
    }

    private fun isAppendedRecovery(
        head: PlcOperationHead,
        opened: SigningKeyService.OpenedPreparedKey,
        nextSigningKeyDidKey: String,
        signature: String,
    ): Boolean =
        head.operation.prev == opened.prevCid &&
            head.operation.verificationMethods[ATPROTO_VERIFICATION_METHOD] == nextSigningKeyDidKey &&
            head.operation.sig == signature.trimEnd('=')

    private suspend fun activate(
        accountId: Uuid,
        key: StoredSigningKey,
    ): StoredSigningKey {
        val active = signingKeyService.ensureActiveKey(accountId)
        if (active.publicKeyMultibase == key.publicKeyMultibase) return active
        return signingKeyService.activatePreparedKey(accountId, key)
    }

    private suspend fun writeIdentity(
        expected: Account,
        updated: Account,
    ): Account {
        accountRepository.updateIdentityIfUnchanged(expected = expected, updated = updated)?.let { return it }
        accountRepository.findById(expected.id) ?: throw IdentityAccountMissingException()
        throw identityChanged()
    }

    private suspend fun currentIdentity(accountId: Uuid): CurrentIdentity {
        val stored = accountRepository.findById(accountId) ?: throw IdentityAccountMissingException()
        val ensured = ensureIdentity(stored)
        val did =
            ensured.did
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before using a recovery key")
        if (!did.startsWith(PLC_DID_PREFIX)) {
            throw IdentityLifecycleConflictException(
                "PLC recovery keys are only supported for hosted did:plc identities",
                code = PlcIdentityService.RECOVERY_KEYS_UNAVAILABLE,
            )
        }
        if (!config.supportsRecoveryKeys) {
            throw IdentityLifecycleConflictException(
                "Recovery keys are not enabled on this server",
                code = PlcIdentityService.RECOVERY_KEYS_UNAVAILABLE,
            )
        }
        return CurrentIdentity(account = ensured, did = AtprotoDid.require(did), handle = requireNotNull(ensured.handle))
    }

    private suspend fun <T> withChangeLock(
        accountId: Uuid,
        block: suspend () -> T,
    ): T = changeLocks[Math.floorMod(accountId.hashCode(), changeLocks.size)].withLock { block() }

    private fun identityChanged(): IdentityLifecycleConflictException =
        IdentityLifecycleConflictException(
            "The identity changed while this request was in progress; start again",
            code = PlcIdentityService.IDENTITY_CHANGED,
        )

    private fun digest(payload: ByteArray): String = encodeBase64Url(MessageDigest.getInstance("SHA-256").digest(payload))

    private fun encodeBase64Url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

    private class CurrentIdentity(
        val account: Account,
        val did: AtprotoDid,
        val handle: String,
    ) {
        fun requireRecoveryKey(): String =
            account.plcRecoveryDidKey
                ?: throw IdentityLifecycleConflictException(
                    "No recovery key is registered for this identity",
                    code = AtprotoIdentityService.RECOVERY_KEY_NOT_SET,
                )
    }

    private companion object {
        private const val CHANGE_LOCK_STRIPES = 64
        private const val PLC_DID_PREFIX = "did:plc:"
        private const val ATPROTO_VERIFICATION_METHOD = "atproto"
    }
}
