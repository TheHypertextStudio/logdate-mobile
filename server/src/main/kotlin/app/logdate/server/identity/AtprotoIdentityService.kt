package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.logging.IdentityNotices
import app.logdate.server.logging.RepoEventNotices
import app.logdate.shared.model.AccountIdentityKind
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.hypertext.atproto.crypto.Multikey
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.identity.Service
import studio.hypertext.atproto.identity.VerificationMethod
import studio.hypertext.atproto.pds.DescribeRepoResponse
import studio.hypertext.atproto.pds.PdsIdentityService
import studio.hypertext.atproto.pds.ResolveHandleResponse
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcUnsignedOperation
import studio.hypertext.atproto.syntax.Handle
import studio.hypertext.atproto.syntax.Nsid
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Told when an account gets an identity or its identity changes: a new DID, handle or signing key.
 * The repository event stream uses it to tell relays to look the account up again.
 */
fun interface IdentityEvents {
    suspend fun identityChanged(account: Account)
}

/**
 * Provisions and resolves AT Protocol identity data for LogDate accounts.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class AtprotoIdentityService(
    private val accountRepository: AccountRepository,
    private val signingKeyService: SigningKeyService,
    val config: AtprotoIdentityConfig,
    private val plcIdentityService: PlcIdentityService = PlcIdentityService(signingKeyService = signingKeyService, config = config),
) : PdsIdentityService {
    private var repoCollectionsResolver: suspend (String) -> List<Nsid> = { emptyList() }
    private val provisioningLocks = Array(PROVISIONING_LOCK_STRIPES) { Mutex() }
    private val recoveryKeys =
        RecoveryKeyService(
            accountRepository = accountRepository,
            signingKeyService = signingKeyService,
            plcIdentityService = plcIdentityService,
            config = config,
            ensureIdentity = ::ensureIdentity,
        )

    fun setRepoCollectionsResolver(resolver: suspend (String) -> List<Nsid>) {
        repoCollectionsResolver = resolver
    }

    private var identityEvents: IdentityEvents? = null

    /**
     * Sets who is told when an account gets an identity or its identity changes, such as a new
     * signing key. A listener that fails never fails the change itself; the failure is logged.
     */
    fun setIdentityEvents(events: IdentityEvents?) {
        identityEvents = events
    }

    private suspend fun notifyIdentityChanged(account: Account) {
        val events = identityEvents ?: return
        try {
            events.identityChanged(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Napier.e(RepoEventNotices.EVENT_NOT_RECORDED, failure)
        }
    }

    /**
     * Returns [account] with a canonical handle, DID, and signing key, provisioning whatever is
     * missing. Provisioning always starts from the stored account, read again under a per-account
     * lock, so two concurrent calls in one process never create two DIDs. The result is written
     * with [AccountRepository.updateIdentityIfUnchanged], so a deleted account is never written back
     * and a DID another server instance assigned first is kept.
     *
     * @throws IdentityAccountMissingException when the account no longer exists.
     */
    suspend fun ensureIdentity(account: Account): Account {
        var provisioned = false
        return provisioningLockFor(account.id)
            .withLock {
                val current = accountRepository.findById(account.id) ?: throw IdentityAccountMissingException()
                requireHostedIdentity(current)
                if (hasCanonicalIdentity(current)) current else ensureIdentityLocked(current).also { provisioned = true }
            }.also { if (provisioned) notifyIdentityChanged(it) }
    }

    private suspend fun ensureIdentityLocked(stored: Account): Account {
        val existingHandle = stored.handle?.let(::canonicalizeHandle)
        val existingDid = stored.did?.let(::canonicalizeDid)
        val existingRecoveryDidKey = stored.plcRecoveryDidKey?.let(::canonicalizeDidKey)
        val normalized =
            stored.copy(
                handle = existingHandle,
                did = existingDid,
                plcRecoveryDidKey = existingRecoveryDidKey,
            )
        if (existingHandle != null && existingDid != null && stored.signingKeyPublic != null) {
            return writeIdentity(stored, normalized)
        }

        val handle = provisionedHandleFor(normalized)
        val did =
            when (config.hostedAccountDidMethod) {
                HostedAccountDidMethod.PLC -> {
                    val provisionedIdentity =
                        plcIdentityService.provisionHostedDid(
                            accountId = stored.id,
                            handle = handle,
                            recoveryDidKey = existingRecoveryDidKey,
                        )
                    provisionedIdentity.did
                }
                HostedAccountDidMethod.WEB -> didForHandle(handle).toString()
            }
        val signingKey = stored.signingKeyPublic ?: signingKeyService.ensureActiveKey(stored.id).publicKeyMultibase

        return writeIdentity(stored, normalized.copy(did = did, handle = handle, signingKeyPublic = signingKey))
    }

    /**
     * Stores [updated]'s identity while the account still has [stored]'s. When another writer got
     * there first, the account as it now stands is returned if it has a complete identity.
     */
    private suspend fun writeIdentity(
        stored: Account,
        updated: Account,
    ): Account {
        accountRepository.updateIdentityIfUnchanged(expected = stored, updated = updated)?.let { return it }
        val current = accountRepository.findById(stored.id) ?: throw IdentityAccountMissingException()
        if (hasCompleteIdentity(current)) return current
        throw IdentityLifecycleConflictException("The account's identity changed while it was being provisioned")
    }

    private fun hasCanonicalIdentity(account: Account): Boolean {
        val handle = account.handle ?: return false
        val did = account.did ?: return false
        return account.signingKeyPublic != null &&
            canonicalizeHandle(handle) == handle &&
            canonicalizeDid(did) == did &&
            account.plcRecoveryDidKey?.let(::canonicalizeDidKey) == account.plcRecoveryDidKey
    }

    private fun provisioningLockFor(accountId: Uuid): Mutex = provisioningLocks[Math.floorMod(accountId.hashCode(), provisioningLocks.size)]

    /**
     * Provisions every active account that still lacks a handle, DID, or signing key, the same way
     * sign-in does. Each account is attempted once. An account deleted after the listing is
     * skipped; any other failure is logged and counted, and the run continues.
     */
    suspend fun backfillMissingIdentities(): IdentityBackfillResult {
        var provisioned = 0
        var skipped = 0
        var failed = 0
        accountRepository.findAccountsMissingIdentity().forEach { account ->
            try {
                ensureIdentity(account)
                provisioned++
            } catch (error: CancellationException) {
                throw error
            } catch (_: IdentityAccountMissingException) {
                skipped++
            } catch (error: Exception) {
                Napier.e(IdentityNotices.BACKFILL_ACCOUNT_FAILED, error)
                failed++
            }
        }
        return IdentityBackfillResult(provisioned = provisioned, skipped = skipped, failed = failed)
    }

    /** Active accounts that still lack a handle, DID, or signing key. Reads only. */
    suspend fun accountsMissingIdentity(): List<Account> = accountRepository.findAccountsMissingIdentity()

    /**
     * The account that owns [handle], when it has a complete identity. Reads only: an anonymous
     * lookup never provisions or publishes anything, and it costs one indexed query whatever the
     * handle is. Accounts without an identity get one at sign-in or from the operator backfill.
     */
    suspend fun findByHandle(handle: String): Account? {
        val normalizedHandle = canonicalizeHandle(handle) ?: return null
        return accountRepository.findByHandle(normalizedHandle)?.takeIf(::hasCompleteIdentity)
    }

    /** Anonymous discovery requires consent for this exact DID, independently of deployment flags. */
    suspend fun findPublicByHandle(handle: String): Account? = findByHandle(handle)?.takeIf(::hasPublicIdentity)

    private fun hasPublicIdentity(account: Account): Boolean =
        account.identityKind == AccountIdentityKind.HOSTED &&
            account.isActive &&
            account.did != null &&
            account.publicIdentityDid == account.did

    /** The account that owns [did], when it has a complete identity. Reads only. */
    suspend fun findByDid(did: String): Account? {
        val normalizedDid = canonicalizeDid(did) ?: return null
        accountRepository.findByDid(normalizedDid)?.takeIf(::hasCompleteIdentity)?.let { return it }

        val handle = handleFromDid(normalizedDid) ?: return null
        return findByHandle(handle)?.takeIf { it.did == normalizedDid }
    }

    private fun hasCompleteIdentity(account: Account): Boolean =
        account.identityKind == AccountIdentityKind.HOSTED &&
            account.handle != null &&
            account.did != null &&
            account.signingKeyPublic != null

    fun didForHandle(handle: String): AtprotoDid = AtprotoDid.require("did:web:${requireNotNull(canonicalizeHandle(handle))}")

    suspend fun rotateSigningKey(account: Account): RotatedIdentitySigningKey =
        performSigningKeyRotation(account).also { notifyIdentityChanged(it.account) }

    private suspend fun performSigningKeyRotation(account: Account): RotatedIdentitySigningKey {
        val ensured = ensureIdentity(account)
        val did = requireNotNull(ensured.did)
        val previousPublicKey = requireNotNull(ensured.signingKeyPublic)

        return when {
            did.startsWith("did:web:") -> {
                val activeKey = signingKeyService.rotateKey(ensured.id)
                val updatedAccount = accountRepository.save(ensured.copy(signingKeyPublic = activeKey.publicKeyMultibase))
                RotatedIdentitySigningKey(
                    account = updatedAccount,
                    previousPublicKeyMultibase = previousPublicKey,
                    activeKey = activeKey,
                )
            }

            did.startsWith("did:plc:") -> {
                val rotated =
                    plcIdentityService.rotateHostedDid(
                        accountId = ensured.id,
                        did = AtprotoDid.require(did),
                        handle = requireNotNull(ensured.handle),
                        recoveryDidKey = ensured.plcRecoveryDidKey,
                    )
                val activeKey = signingKeyService.ensureActiveKey(ensured.id)
                val updatedAccount = accountRepository.save(ensured.copy(signingKeyPublic = activeKey.publicKeyMultibase))
                RotatedIdentitySigningKey(
                    account = updatedAccount,
                    previousPublicKeyMultibase = rotated.previousPublicKeyMultibase,
                    activeKey = activeKey,
                    plcOperation = rotated.operation,
                )
            }

            else -> throw IdentityLifecycleConflictException("Signing key rotation is not supported for DID $did")
        }
    }

    suspend fun importSigningKey(
        account: Account,
        exportedKey: SigningKeyService.ExportedSigningKey,
        passphrase: String,
    ): ImportedIdentitySigningKey = performSigningKeyImport(account, exportedKey, passphrase).also { notifyIdentityChanged(it.account) }

    private suspend fun performSigningKeyImport(
        account: Account,
        exportedKey: SigningKeyService.ExportedSigningKey,
        passphrase: String,
    ): ImportedIdentitySigningKey {
        val ensured = ensureIdentity(account)
        val currentDid =
            ensured.did
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before importing a signing key")
        val currentHandle =
            ensured.handle
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before importing a signing key")
        val currentPublicKey =
            ensured.signingKeyPublic
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before importing a signing key")

        val activeKey =
            when {
                exportedKey.publicKeyMultibase == currentPublicKey -> {
                    signingKeyService.importActiveKey(ensured.id, exportedKey, passphrase)
                }

                currentDid.startsWith("did:web:") -> {
                    signingKeyService.importActiveKey(ensured.id, exportedKey, passphrase)
                }

                currentDid.startsWith("did:plc:") -> {
                    if (!config.publishHostedPlcOperations) {
                        throw IdentityLifecycleConflictException("Hosted PLC recovery requires published PLC operations")
                    }
                    val preparedKey = signingKeyService.prepareImportedKey(ensured.id, exportedKey, passphrase)
                    plcIdentityService
                        .rotateHostedDid(
                            accountId = ensured.id,
                            did = AtprotoDid.require(currentDid),
                            handle = currentHandle,
                            recoveryDidKey = ensured.plcRecoveryDidKey,
                            preparedKey = preparedKey,
                        ).let { signingKeyService.ensureActiveKey(ensured.id) }
                }

                else -> {
                    throw IdentityLifecycleConflictException("Signing key import is not supported for DID $currentDid")
                }
            }
        val updatedAccount =
            accountRepository.save(
                ensured.copy(
                    did = currentDid,
                    handle = currentHandle,
                    signingKeyPublic = activeKey.publicKeyMultibase,
                ),
            )
        return ImportedIdentitySigningKey(
            account = updatedAccount,
            activeKey = activeKey,
        )
    }

    suspend fun prepareRecoverySigningKeyImport(
        account: Account,
        exportedKey: SigningKeyService.ExportedSigningKey,
        passphrase: String,
    ): PreparedRecoverySigningKeyImport {
        val ensured = ensureIdentity(account)
        val currentDid =
            ensured.did
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before importing a signing key")
        val currentHandle =
            ensured.handle
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before importing a signing key")
        val recoveryDidKey =
            ensured.plcRecoveryDidKey
                ?: throw IdentityLifecycleConflictException("A PLC recovery key must be registered before recovery import")
        require(currentDid.startsWith("did:plc:")) { "Hosted PLC recovery import is only supported for did:plc identities" }
        require(config.publishHostedPlcOperations) { "Hosted PLC recovery import requires published PLC operations" }

        val preparedKey = signingKeyService.prepareImportedKey(ensured.id, exportedKey, passphrase)
        val nextSigningKeyDidKey = didKeyFor(preparedKey.publicKeyMultibase)
        val preparedOperation =
            plcIdentityService.prepareHostedRecoveryImport(
                accountId = ensured.id,
                did = AtprotoDid.require(currentDid),
                handle = currentHandle,
                recoveryDidKey = recoveryDidKey,
                nextSigningKeyDidKey = nextSigningKeyDidKey,
            )
        return PreparedRecoverySigningKeyImport(
            did = currentDid,
            handle = currentHandle,
            recoveryDidKey = recoveryDidKey,
            nextSigningKeyDidKey = nextSigningKeyDidKey,
            unsignedOperation = preparedOperation.unsignedOperation,
            signingPayloadBase64Url = Base64.UrlSafe.encode(preparedOperation.signingPayload).trimEnd('='),
        )
    }

    suspend fun importSigningKeyWithRecovery(
        account: Account,
        exportedKey: SigningKeyService.ExportedSigningKey,
        passphrase: String,
        recoverySignature: String,
    ): ImportedIdentitySigningKey =
        performRecoverySigningKeyImport(account, exportedKey, passphrase, recoverySignature)
            .also { notifyIdentityChanged(it.account) }

    private suspend fun performRecoverySigningKeyImport(
        account: Account,
        exportedKey: SigningKeyService.ExportedSigningKey,
        passphrase: String,
        recoverySignature: String,
    ): ImportedIdentitySigningKey {
        val ensured = ensureIdentity(account)
        val currentDid =
            ensured.did
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before importing a signing key")
        val currentHandle =
            ensured.handle
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before importing a signing key")
        val recoveryDidKey =
            ensured.plcRecoveryDidKey
                ?: throw IdentityLifecycleConflictException("A PLC recovery key must be registered before recovery import")
        require(currentDid.startsWith("did:plc:")) { "Hosted PLC recovery import is only supported for did:plc identities" }
        require(config.publishHostedPlcOperations) { "Hosted PLC recovery import requires published PLC operations" }

        val preparedKey = signingKeyService.prepareImportedKey(ensured.id, exportedKey, passphrase)
        val preparedOperation =
            plcIdentityService.prepareHostedRecoveryImport(
                accountId = ensured.id,
                did = AtprotoDid.require(currentDid),
                handle = currentHandle,
                recoveryDidKey = recoveryDidKey,
                nextSigningKeyDidKey = didKeyFor(preparedKey.publicKeyMultibase),
            )
        plcIdentityService.publishClientSignedRecoveryImport(
            accountId = ensured.id,
            did = AtprotoDid.require(currentDid),
            recoveryDidKey = recoveryDidKey,
            preparedOperation = preparedOperation,
            signature = recoverySignature,
        )
        val activeKey = signingKeyService.activatePreparedKey(ensured.id, preparedKey)
        val updatedAccount =
            accountRepository.save(
                ensured.copy(
                    did = currentDid,
                    handle = currentHandle,
                    signingKeyPublic = activeKey.publicKeyMultibase,
                ),
            )
        return ImportedIdentitySigningKey(
            account = updatedAccount,
            activeKey = activeKey,
        )
    }

    /**
     * Registers [recoveryDidKey] as the identity's recovery key, listed ahead of the server's key.
     * See [RecoveryKeyService.register].
     */
    suspend fun registerPlcRecoveryKey(
        account: Account,
        recoveryDidKey: String,
    ): RegisteredPlcRecoveryKey = recoveryKeys.register(account.id, requireRecoveryDidKey(recoveryDidKey))

    /**
     * Puts [recoveryDidKey] in place of the registered recovery key. See [RecoveryKeyService.replace].
     */
    suspend fun replacePlcRecoveryKey(
        account: Account,
        recoveryDidKey: String,
    ): RegisteredPlcRecoveryKey = recoveryKeys.replace(account.id, requireRecoveryDidKey(recoveryDidKey))

    /** Step one of replacing the server's key. See [RecoveryKeyService.prepareRecovery]. */
    suspend fun prepareRecovery(account: Account): PreparedIdentityRecovery = recoveryKeys.prepareRecovery(account.id)

    /** Step two of replacing the server's key. See [RecoveryKeyService.completeRecovery]. */
    suspend fun completeRecovery(
        account: Account,
        preparedKey: String,
        signature: String,
    ): ImportedIdentitySigningKey =
        recoveryKeys.completeRecovery(account.id, preparedKey, signature).also { notifyIdentityChanged(it.account) }

    /** [recoveryDidKey] in canonical form, or `null` when it is not a `did:key` for a supported curve. */
    fun canonicalRecoveryDidKey(recoveryDidKey: String): String? = canonicalizeDidKey(recoveryDidKey)

    private fun requireRecoveryDidKey(recoveryDidKey: String): String =
        canonicalizeDidKey(recoveryDidKey)
            ?: throw IdentityLifecycleValidationException("Recovery key must be a valid did:key multikey value")

    override suspend fun resolveHandle(handle: String): Result<ResolveHandleResponse?> =
        runCatching {
            findPublicByHandle(handle)
                ?.did
                ?.let(AtprotoDid::require)
                ?.let(::ResolveHandleResponse)
        }

    override suspend fun describeRepo(repo: String): Result<DescribeRepoResponse?> =
        runCatching {
            val account =
                when {
                    repo.startsWith("did:") -> findByDid(repo)
                    else -> findByHandle(repo)
                }?.takeIf(::hasPublicIdentity) ?: return@runCatching null
            DescribeRepoResponse(
                handle = requireNotNull(account.handle),
                did = AtprotoDid.require(requireNotNull(account.did)),
                didDoc = documentFor(account),
                collections = repoCollectionsResolver(requireNotNull(account.did)),
                handleIsCorrect = true,
            )
        }

    override suspend fun didDocument(did: AtprotoDid): Result<DidDocument?> =
        runCatching {
            findByDid(did.toString())?.takeIf(::hasPublicIdentity)?.let(::documentFor)
        }

    suspend fun identityStatus(account: Account): IdentityStatus {
        val ensured = ensureIdentity(account)
        val did = requireNotNull(ensured.did)
        val handle = requireNotNull(ensured.handle)
        val signingKeyPublic = requireNotNull(ensured.signingKeyPublic)
        val operations =
            if (did.startsWith("did:plc:")) {
                plcIdentityService.listStoredOperations(AtprotoDid.require(did))
            } else {
                emptyList()
            }

        return IdentityStatus(
            did = did,
            handle = handle,
            signingKeyPublicMultibase = signingKeyPublic,
            signingKeyDidKey = didKeyFor(signingKeyPublic),
            plcRecoveryDidKey = ensured.plcRecoveryDidKey,
            plcOperationCount = operations.size,
        )
    }

    suspend fun hostedPlcOperations(account: Account): List<StoredHostedPlcOperation> {
        val ensured = ensureIdentity(account)
        val did =
            ensured.did
                ?: throw IdentityLifecycleConflictException("Account identity must be provisioned before listing PLC operations")
        if (!did.startsWith("did:plc:")) {
            return emptyList()
        }
        return plcIdentityService.listStoredOperations(AtprotoDid.require(did))
    }

    fun documentFor(account: Account): DidDocument {
        requireHostedIdentity(account)
        val did = AtprotoDid.require(requireNotNull(account.did))
        val handle = requireNotNull(account.handle)
        val signingKeyPublic = requireNotNull(account.signingKeyPublic)

        return DidDocument(
            context = listOf("https://www.w3.org/ns/did/v1", "https://w3id.org/security/multikey/v1"),
            id = did,
            alsoKnownAs = listOf("at://$handle"),
            verificationMethod =
                listOf(
                    VerificationMethod(
                        id = "$did#atproto",
                        type = "Multikey",
                        controller = did,
                        publicKeyMultibase = signingKeyPublic,
                    ),
                ),
            service =
                listOf(
                    Service(
                        id = "#atproto_pds",
                        type = "AtprotoPersonalDataServer",
                        serviceEndpoint = config.pdsServiceEndpoint,
                    ),
                ),
        )
    }

    private fun requireHostedIdentity(account: Account) {
        if (account.identityKind != AccountIdentityKind.HOSTED) {
            throw IdentityLifecycleConflictException("This identity is not hosted by LogDate")
        }
    }

    fun serverDocument(): DidDocument =
        DidDocument(
            id = AtprotoDid.require(config.serverDid),
            service =
                listOf(
                    Service(
                        id = "#atproto_pds",
                        type = "AtprotoPersonalDataServer",
                        serviceEndpoint = config.pdsServiceEndpoint,
                    ),
                ),
        )

    fun defaultHandleFor(username: String): String = "${managedHandleLabel(username)}.${config.normalizedHandleDomain}"

    private fun handleFromDid(did: String): String? {
        if (!did.startsWith("did:web:")) {
            return null
        }
        return canonicalizeHandle(did.substringAfter("did:web:"))
    }

    private suspend fun provisionedHandleFor(account: Account): String {
        val existingHandle = account.handle?.let(::canonicalizeHandle)
        if (existingHandle != null) {
            return ensureUniqueHandle(existingHandle, account.id)
        }

        val baseLabel = managedHandleLabel(account.username)
        val baseHandle = "$baseLabel.${config.normalizedHandleDomain}"
        if (isHandleAvailableForAccount(baseHandle, account.id)) {
            return baseHandle
        }

        val suffix =
            account.id
                .toString()
                .substring(0, HANDLE_COLLISION_SUFFIX_LENGTH)
                .lowercase()
        val suffixedBase = managedHandleLabel(account.username, maxLength = MAX_HANDLE_LABEL_LENGTH - suffix.length - 1)
        return "$suffixedBase-$suffix.${config.normalizedHandleDomain}"
    }

    private suspend fun ensureUniqueHandle(
        handle: String,
        accountId: Uuid,
    ): String {
        if (isHandleAvailableForAccount(handle, accountId)) {
            return handle
        }

        val label = handle.substringBefore('.')
        val domain = handle.substringAfter('.', missingDelimiterValue = config.normalizedHandleDomain)
        val suffix = accountId.toString().substring(0, HANDLE_COLLISION_SUFFIX_LENGTH).lowercase()
        val adjustedLabel =
            if (label.length >= MAX_HANDLE_LABEL_LENGTH - suffix.length) {
                label.take(MAX_HANDLE_LABEL_LENGTH - suffix.length - 1).trim('-')
            } else {
                label
            }.ifBlank { DEFAULT_HANDLE_LABEL }
        return "$adjustedLabel-$suffix.$domain"
    }

    private suspend fun isHandleAvailableForAccount(
        handle: String,
        accountId: Uuid,
    ): Boolean = accountRepository.findByHandle(handle)?.id?.let { it == accountId } ?: true

    private fun managedHandleLabel(
        username: String,
        maxLength: Int = MAX_HANDLE_LABEL_LENGTH,
    ): String {
        val sanitized = HandleLabels.labelForUsername(username).ifBlank { DEFAULT_HANDLE_LABEL }
        val label = sanitized.take(maxLength.coerceAtLeast(1)).trim('-').ifBlank { DEFAULT_HANDLE_LABEL }
        return if (HandleLabels.isReserved(label)) DEFAULT_HANDLE_LABEL else label
    }

    private fun canonicalizeHandle(handle: String): String? {
        val trimmed = handle.trim().trim('.').lowercase()
        if (trimmed.isBlank()) {
            return null
        }
        return Handle.parse(trimmed).getOrNull()?.toString()
    }

    private fun canonicalizeDid(did: String): String? {
        val trimmed = did.trim().lowercase()
        if (trimmed.isBlank()) {
            return null
        }
        return AtprotoDid.parse(trimmed).getOrNull()?.toString()
    }

    private fun canonicalizeDidKey(didKey: String): String? {
        val trimmed = didKey.trim()
        if (trimmed.isBlank()) {
            return null
        }
        if (!trimmed.regionMatches(0, DID_KEY_PREFIX, 0, DID_KEY_PREFIX.length, ignoreCase = true)) {
            return null
        }
        val suffix = trimmed.substring(DID_KEY_PREFIX.length)
        if (suffix.isBlank()) {
            return null
        }
        return runCatching {
            Multikey.decode(suffix)
            "$DID_KEY_PREFIX$suffix"
        }.getOrNull()
    }

    companion object {
        /** A different recovery key is already registered; replacing it goes through the replace request. */
        const val RECOVERY_KEY_ALREADY_SET: String = "RECOVERY_KEY_ALREADY_SET"

        /** Recovery was asked for, but no recovery key is registered. */
        const val RECOVERY_KEY_NOT_SET: String = "RECOVERY_KEY_NOT_SET"

        private const val DEFAULT_HANDLE_LABEL = "user"
        private const val DID_KEY_PREFIX = "did:key:"
        private const val MAX_HANDLE_LABEL_LENGTH = 63
        private const val HANDLE_COLLISION_SUFFIX_LENGTH = 8
        private const val PROVISIONING_LOCK_STRIPES = 64
    }
}

/** Outcome of [AtprotoIdentityService.backfillMissingIdentities]. */
data class IdentityBackfillResult(
    val provisioned: Int,
    val skipped: Int,
    val failed: Int,
)

data class RotatedIdentitySigningKey(
    val account: Account,
    val previousPublicKeyMultibase: String,
    val activeKey: StoredSigningKey,
    val plcOperation: PlcOperation? = null,
)

data class ImportedIdentitySigningKey(
    val account: Account,
    val activeKey: StoredSigningKey,
)

data class PreparedRecoverySigningKeyImport(
    val did: String,
    val handle: String,
    val recoveryDidKey: String,
    val nextSigningKeyDidKey: String,
    val unsignedOperation: PlcUnsignedOperation,
    val signingPayloadBase64Url: String,
)

/**
 * A prepared recovery: the operation the recovery key must sign, and the sealed server key that
 * completing it will install.
 */
data class PreparedIdentityRecovery(
    val did: String,
    val handle: String,
    val recoveryDidKey: String,
    val nextSigningKeyDidKey: String,
    val unsignedOperation: PlcUnsignedOperation,
    val signingPayloadBase64Url: String,
    val preparedKey: String,
)

data class RegisteredPlcRecoveryKey(
    val account: Account,
    val recoveryDidKey: String,
    val plcOperation: PlcOperation? = null,
)

data class IdentityStatus(
    val did: String,
    val handle: String,
    val signingKeyPublicMultibase: String,
    val signingKeyDidKey: String,
    val plcRecoveryDidKey: String?,
    val plcOperationCount: Int,
)
