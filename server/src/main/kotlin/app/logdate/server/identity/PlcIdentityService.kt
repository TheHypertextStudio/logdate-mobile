package app.logdate.server.identity

import app.logdate.server.logging.IdentityPublishingNotices
import io.github.aakira.napier.Napier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.identity.Service
import studio.hypertext.atproto.identity.VerificationMethod
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcEncoding
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcOperations
import studio.hypertext.atproto.plc.PlcUnsignedOperation
import studio.hypertext.atproto.syntax.Handle
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Provisions hosted `did:plc` identities for LogDate accounts.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class PlcIdentityService(
    private val signingKeyService: SigningKeyService,
    private val config: AtprotoIdentityConfig,
    private val plcDirectoryClient: PlcDirectoryClient? = null,
    private val hostedPlcOperationRepository: HostedPlcOperationRepository? = null,
) {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    init {
        if (config.publishHostedPlcOperations) {
            require(plcDirectoryClient != null) { "PLC publishing requires a PLC directory client" }
        }
    }

    /**
     * Creates a signed PLC genesis operation and derives the hosted `did:plc`.
     */
    suspend fun provisionHostedDid(
        accountId: Uuid,
        handle: String,
        recoveryDidKey: String? = null,
    ): ProvisionedPlcIdentity {
        val activeKey = signingKeyService.ensureActiveKey(accountId)
        val publicKeyDidKey = "did:key:${activeKey.publicKeyMultibase}"
        val unsignedOperation =
            PlcOperations.atprotoGenesis(
                handle = handle,
                pdsServiceEndpoint = config.pdsServiceEndpoint,
                signingKeyDidKey = publicKeyDidKey,
                rotationKeys = listOfNotNull(recoveryDidKey, publicKeyDidKey).distinct(),
            )
        val signature =
            signOperation(
                payload = PlcEncoding.encodeUnsigned(unsignedOperation),
                privateKey = signingKeyService.decryptPrivateKey(activeKey),
                curve =
                    requireNotNull(EcCurve.fromSigningKeyAlgorithm(activeKey.algorithm)) {
                        "Unsupported signing key algorithm: ${activeKey.algorithm}"
                    },
            )
        val signedOperation = unsignedOperation.signed(signature)
        val did = PlcEncoding.deriveDid(signedOperation)

        val candidate =
            StoredHostedPlcOperation(
                Uuid.random(),
                accountId,
                did.toString(),
                PlcEncoding.operationCid(signedOperation),
                operationType = signedOperation.type,
                operationJson = json.encodeToString(PlcOperation.serializer(), signedOperation),
                publicationAttempted = false,
            )
        val selected = hostedPlcOperationRepository?.claimGenesis(candidate) ?: candidate
        val identity = provisionedIdentity(selected, activeKey)
        return identity
    }

    private fun provisionedIdentity(
        stored: StoredHostedPlcOperation,
        activeKey: StoredSigningKey,
    ): ProvisionedPlcIdentity {
        val operation = storedGenesisOperation(stored)
        if (operation.verificationMethods["atproto"] != didKeyFor(activeKey.publicKeyMultibase)) {
            throw IdentityLifecycleConflictException("Hosted signing authority needs reconciliation")
        }
        val handle =
            operation.alsoKnownAs
                .singleOrNull()
                ?.takeIf { it.startsWith("at://") }
                ?.removePrefix("at://")
                ?.let { Handle.parse(it).getOrNull()?.toString() }
                ?: throw IdentityLifecycleConflictException("Hosted identity metadata needs reconciliation")
        return ProvisionedPlcIdentity(stored.did, activeKey.publicKeyMultibase, didKeyFor(activeKey.publicKeyMultibase), operation, handle)
    }

    /**
     * Builds a DID document for [identity].
     */
    fun documentFor(identity: ProvisionedPlcIdentity): DidDocument =
        DidDocument(
            id = AtprotoDid.require(identity.did),
            alsoKnownAs = identity.operation.alsoKnownAs,
            verificationMethod =
                listOf(
                    VerificationMethod(
                        id = "${identity.did}#atproto",
                        type = "Multikey",
                        controller = AtprotoDid.require(identity.did),
                        publicKeyMultibase = identity.publicKeyMultibase,
                    ),
                ),
            service =
                listOf(
                    Service(
                        id = "#atproto_pds",
                        type = "AtprotoPersonalDataServer",
                        serviceEndpoint =
                            identity.operation.services
                                .getValue("atproto_pds")
                                .endpoint,
                    ),
                ),
        )

    /**
     * Rotates the active hosted PLC signing key and publishes a PLC update operation.
     */
    suspend fun rotateHostedDid(
        accountId: Uuid,
        did: AtprotoDid,
        handle: String,
        recoveryDidKey: String? = null,
        preparedKey: StoredSigningKey? = null,
    ): RotatedPlcIdentity {
        if (!config.publishHostedPlcOperations) {
            throw IdentityLifecycleConflictException("Hosted PLC rotation requires published PLC operations")
        }
        val plcClient = plcDirectoryClient ?: throw IdentityLifecycleConflictException("PLC publishing requires a PLC directory client")
        require(did.method == "plc") { "Hosted PLC rotation requires a did:plc identity" }

        val currentKey = signingKeyService.ensureActiveKey(accountId)
        val nextKey = preparedKey ?: signingKeyService.prepareKey(accountId)
        val latestIndexedOperation =
            plcClient
                .getAuditLog(did)
                .getOrThrow()
                .lastOrNull { !it.nullified }
                ?: throw IdentityLifecycleConflictException("No PLC audit log entry exists for $did")
        val nextPublicKeyDidKey = didKeyFor(nextKey.publicKeyMultibase)
        val unsignedOperation =
            buildHostedAtprotoUpdate(
                prevCid = latestIndexedOperation.cid,
                handle = handle,
                signingKeyDidKey = nextPublicKeyDidKey,
                recoveryDidKey = recoveryDidKey,
            )
        val signedOperation =
            unsignedOperation.signed(
                signOperation(
                    payload = PlcEncoding.encodeUnsigned(unsignedOperation),
                    privateKey = signingKeyService.decryptPrivateKey(currentKey),
                    curve =
                        requireNotNull(EcCurve.fromSigningKeyAlgorithm(currentKey.algorithm)) {
                            "Unsupported signing key algorithm: ${currentKey.algorithm}"
                        },
                ),
            )

        submitToDirectory(accountId, plcClient, did, signedOperation)
        persistHostedOperation(
            accountId = accountId,
            did = did,
            operation = signedOperation,
            resolvedCid = confirmPublishedCid(did, signedOperation),
        )
        val activatedKey = signingKeyService.activatePreparedKey(accountId, nextKey)
        return RotatedPlcIdentity(
            did = did.toString(),
            previousPublicKeyMultibase = currentKey.publicKeyMultibase,
            publicKeyMultibase = activatedKey.publicKeyMultibase,
            publicKeyDidKey = nextPublicKeyDidKey,
            operation = signedOperation,
        )
    }

    suspend fun listStoredOperations(accountId: Uuid): List<StoredHostedPlcOperation> =
        hostedPlcOperationRepository?.listByAccountId(accountId).orEmpty()

    suspend fun listStoredOperations(did: AtprotoDid): List<StoredHostedPlcOperation> =
        hostedPlcOperationRepository?.listByDid(did.toString()).orEmpty()

    /**
     * Appends an update operation that lists the person's [recoveryDidKey] ahead of the server's key,
     * so it can overrule the server. The server signs it with its current key, which the previous
     * operation lists as a rotation key. The operation reaches the PLC directory only when publishing
     * is on; otherwise the hosted operation log is the identity's history.
     */
    suspend fun updateHostedRecoveryKey(
        accountId: Uuid,
        did: AtprotoDid,
        handle: String,
        recoveryDidKey: String,
    ): UpdatedPlcRecoveryKey {
        requireRecoveryKeys(did)
        return UpdatedPlcRecoveryKey(
            did = did.toString(),
            recoveryDidKey = recoveryDidKey,
            operation = publishHostedUpdate(accountId, did, handle, recoveryDidKey),
        )
    }

    /**
     * Publishes an update operation that makes the directory's copy of [did] match what LogDate
     * serves for the account: its handle, active signing key, PDS endpoint and registered recovery
     * key. The account's active signing key signs it, so the directory accepts it only while that
     * key is still one of the identity's rotation keys.
     */
    suspend fun reconcileHostedDid(
        accountId: Uuid,
        did: AtprotoDid,
        handle: String,
        recoveryDidKey: String?,
    ): PlcOperation {
        if (!config.publishHostedPlcOperations) {
            throw IdentityLifecycleConflictException("Reconciling a hosted PLC identity requires published PLC operations")
        }
        require(did.method == "plc") { "Reconciling requires a did:plc identity" }
        return publishHostedUpdate(accountId, did, handle, recoveryDidKey)
    }

    /**
     * Sends a genesis operation that was signed and stored while publishing was off, and records the
     * CID the directory assigned to it.
     *
     * @return the CID the directory assigned to the genesis operation.
     */
    suspend fun publishStoredGenesis(stored: StoredHostedPlcOperation): String {
        if (!config.publishHostedPlcOperations) {
            throw IdentityLifecycleConflictException("Publishing a stored PLC genesis operation requires published PLC operations")
        }
        val plcClient = plcDirectoryClient ?: throw IdentityLifecycleConflictException("PLC publishing requires a PLC directory client")
        val did = AtprotoDid.require(stored.did)
        val operation = storedGenesisOperation(stored)
        if (hostedPlcOperationRepository?.markPublicationAttempted(stored.id, stored.identityEpoch) != true) {
            throw IdentityLifecycleConflictException("Publication attempt history could not be recorded")
        }
        submitToDirectory(stored.accountId, plcClient, did, operation, stored.identityEpoch)
        val cid =
            directoryCid(did, operation)
                ?: throw PlcPublicationUnconfirmedException(
                    "The PLC directory accepted the genesis operation for $did but its audit log does not show it",
                )
        hostedPlcOperationRepository.recordPublishedCid(stored.id, cid, stored.identityEpoch)
        return cid
    }

    /**
     * Decodes a stored genesis operation and checks that it still derives the DID it was stored
     * under, so a damaged row can never be sent in place of the real identity.
     */
    fun storedGenesisOperation(stored: StoredHostedPlcOperation): PlcOperation {
        val operation = json.decodeFromString(PlcOperation.serializer(), stored.operationJson)
        require(operation.isGenesis && operation.isSigned) { "Stored operation ${stored.id} is not a signed genesis operation" }
        require(PlcEncoding.deriveDid(operation).toString() == stored.did) {
            "Stored genesis operation ${stored.id} does not derive ${stored.did}"
        }
        return operation
    }

    private suspend fun publishHostedUpdate(
        accountId: Uuid,
        did: AtprotoDid,
        handle: String,
        recoveryDidKey: String?,
    ): PlcOperation {
        val activeKey = signingKeyService.ensureActiveKey(accountId)
        val activeDidKey = didKeyFor(activeKey.publicKeyMultibase)
        val head = head(did)
        if (activeDidKey !in head.operation.rotationKeys) {
            throw IdentityLifecycleConflictException(
                "The identity's latest operation does not list the server's current key, so it cannot sign the next one",
                code = SERVER_KEY_NOT_ON_IDENTITY,
            )
        }
        val unsignedOperation =
            buildHostedAtprotoUpdate(
                prevCid = head.cid,
                handle = handle,
                signingKeyDidKey = activeDidKey,
                recoveryDidKey = recoveryDidKey,
            )
        val signedOperation =
            unsignedOperation.signed(
                signOperation(
                    payload = PlcEncoding.encodeUnsigned(unsignedOperation),
                    privateKey = signingKeyService.decryptPrivateKey(activeKey),
                    curve =
                        requireNotNull(EcCurve.fromSigningKeyAlgorithm(activeKey.algorithm)) {
                            "Unsupported signing key algorithm: ${activeKey.algorithm}"
                        },
                ),
            )

        appendOperation(accountId = accountId, did = did, operation = signedOperation)
        return signedOperation
    }

    /**
     * Builds the unsigned operation that installs [nextSigningKeyDidKey] as the server's key, for the
     * person's [recoveryDidKey] to sign. The recovery key stays first. The operation builds on the
     * identity's latest operation, which must list the recovery key as a rotation key.
     */
    suspend fun prepareHostedRecoveryImport(
        accountId: Uuid,
        did: AtprotoDid,
        handle: String,
        recoveryDidKey: String,
        nextSigningKeyDidKey: String,
    ): PreparedPlcRecoveryOperation {
        requireRecoveryKeys(did)

        val latest = head(did)
        if (recoveryDidKey !in latest.operation.rotationKeys) {
            throw IdentityLifecycleConflictException(
                "The identity's latest operation does not list the registered recovery key",
                code = RECOVERY_KEY_NOT_ON_IDENTITY,
            )
        }
        val unsignedOperation =
            buildHostedAtprotoUpdate(
                prevCid = latest.cid,
                handle = handle,
                signingKeyDidKey = nextSigningKeyDidKey,
                recoveryDidKey = recoveryDidKey,
            )

        return PreparedPlcRecoveryOperation(
            did = did.toString(),
            recoveryDidKey = recoveryDidKey,
            nextSigningKeyDidKey = nextSigningKeyDidKey,
            unsignedOperation = unsignedOperation,
            signingPayload = PlcEncoding.encodeUnsigned(unsignedOperation),
        )
    }

    /**
     * Attaches the client's recovery-key [signature] to [preparedOperation] and appends it, after
     * checking that the operation still builds on the identity's latest operation and that the
     * signature is the registered recovery key's.
     */
    suspend fun publishClientSignedRecoveryImport(
        accountId: Uuid,
        did: AtprotoDid,
        recoveryDidKey: String,
        preparedOperation: PreparedPlcRecoveryOperation,
        signature: String,
    ): PlcOperation {
        requireRecoveryKeys(did)
        require(preparedOperation.did == did.toString()) { "Prepared PLC recovery import belongs to a different DID" }
        require(preparedOperation.recoveryDidKey == recoveryDidKey) { "Prepared PLC recovery import belongs to a different recovery key" }
        val head = head(did)
        if (preparedOperation.unsignedOperation.prev != head.cid) {
            throw IdentityLifecycleConflictException("The identity changed after this recovery was prepared", code = IDENTITY_CHANGED)
        }
        if (recoveryDidKey !in head.operation.rotationKeys) {
            throw IdentityLifecycleConflictException(
                "The identity's latest operation does not list the registered recovery key",
                code = RECOVERY_KEY_NOT_ON_IDENTITY,
            )
        }
        verifyRecoverySignature(
            recoveryDidKey = recoveryDidKey,
            payload = preparedOperation.signingPayload,
            signature = signature,
        )

        val signedOperation = preparedOperation.unsignedOperation.signed(signature)
        appendOperation(accountId = accountId, did = did, operation = signedOperation)
        return signedOperation
    }

    private fun requireRecoveryKeys(did: AtprotoDid) {
        if (!config.supportsRecoveryKeys) {
            throw IdentityLifecycleConflictException("Recovery keys are not enabled on this server", code = RECOVERY_KEYS_UNAVAILABLE)
        }
        if (did.method != "plc") {
            throw IdentityLifecycleConflictException("Recovery keys need a did:plc identity", code = RECOVERY_KEYS_UNAVAILABLE)
        }
    }

    /**
     * The identity's newest operation and its CID: from the PLC directory's audit log when this server
     * publishes, otherwise from the hosted operation log, which is then the identity's only history.
     * The hosted log is followed from its genesis operation through each `prev`, not read in the
     * order rows were written, so the head is the end of the one chain.
     */
    suspend fun head(did: AtprotoDid): PlcOperationHead {
        val plcClient = plcDirectoryClient
        if (config.publishHostedPlcOperations && plcClient != null) {
            val indexed =
                plcClient
                    .getAuditLog(did)
                    .getOrThrow()
                    .lastOrNull { !it.nullified }
                    ?: throw IdentityLifecycleConflictException("No PLC audit log entry exists for $did")
            val operation =
                indexed.operation as? PlcOperation
                    ?: throw IdentityLifecycleConflictException("The identity $did has been deactivated")
            return PlcOperationHead(cid = indexed.cid, operation = operation)
        }
        return hostedChainHead(did)
    }

    private suspend fun hostedChainHead(did: AtprotoDid): PlcOperationHead {
        val links =
            listStoredOperations(did).map { stored ->
                val operation = json.decodeFromString(PlcOperation.serializer(), stored.operationJson)
                PlcOperationHead(cid = stored.cid ?: PlcEncoding.operationCid(operation), operation = operation)
            }
        if (links.isEmpty()) throw IdentityLifecycleConflictException("No hosted PLC operation exists for $did")
        val followers = links.groupBy { it.operation.prev }
        var current =
            followers[null]?.singleOrNull()
                ?: throw IdentityLifecycleConflictException("The hosted operation log for $did does not start with one genesis operation")
        repeat(links.size) {
            val next = followers[current.cid] ?: return current
            current = next.singleOrNull()
                ?: throw IdentityLifecycleConflictException("The hosted operation log for $did forks after ${current.cid}")
        }
        throw IdentityLifecycleConflictException("The hosted operation log for $did loops")
    }

    /** Submits [operation] to the PLC directory when this server publishes, then records it in the hosted log. */
    private suspend fun appendOperation(
        accountId: Uuid,
        did: AtprotoDid,
        operation: PlcOperation,
    ) {
        val plcClient = plcDirectoryClient
        if (config.publishHostedPlcOperations && plcClient != null) {
            submitToDirectory(accountId, plcClient, did, operation)
        }
        try {
            persistHostedOperation(
                accountId = accountId,
                did = did,
                operation = operation,
                resolvedCid = confirmPublishedCid(did, operation),
            )
        } catch (conflict: HostedPlcOperationConflictException) {
            throw IdentityLifecycleConflictException(
                "Another change to the identity was made at the same time; nothing was changed",
                code = IDENTITY_CHANGED,
                cause = conflict,
            )
        }
    }

    /**
     * Sends [operation] and logs the outcome as one of the [IdentityPublishingNotices], so publish
     * successes and failures stay countable in production logs.
     */
    private suspend fun submitToDirectory(
        accountId: Uuid,
        plcClient: PlcDirectoryClient,
        did: AtprotoDid,
        operation: PlcOperation,
        identityEpoch: Long = 0,
    ) {
        if (hostedPlcOperationRepository?.beginPublicationAttempt(accountId, did.toString(), identityEpoch) != true) {
            throw IdentityLifecycleConflictException("Publication attempt history could not be recorded")
        }
        plcClient
            .submit(did, operation)
            .onFailure { failure -> Napier.e(IdentityPublishingNotices.forSubmitFailure(failure), failure) }
            .getOrThrow()
        Napier.i(IdentityPublishingNotices.PLC_OPERATION_PUBLISHED)
    }

    /**
     * The CID to store for [operation]: the one the directory reports when this server publishes,
     * otherwise the one computed locally. A published operation the directory's audit log does not
     * yet show is logged as unconfirmed and stored under its computed CID, which is the same value.
     */
    private suspend fun confirmPublishedCid(
        did: AtprotoDid,
        operation: PlcOperation,
    ): String {
        if (!config.publishHostedPlcOperations) return PlcEncoding.operationCid(operation)
        val cid = directoryCid(did, operation)
        if (cid == null) Napier.w(IdentityPublishingNotices.PLC_OPERATION_UNCONFIRMED)
        return cid ?: PlcEncoding.operationCid(operation)
    }

    private suspend fun directoryCid(
        did: AtprotoDid,
        operation: PlcOperation,
    ): String? =
        plcDirectoryClient
            ?.getAuditLog(did)
            ?.getOrNull()
            ?.lastOrNull { indexedOperation ->
                !indexedOperation.nullified &&
                    indexedOperation.operation is PlcOperation &&
                    indexedOperation.operation.sig == operation.sig
            }?.cid

    private suspend fun persistHostedOperation(
        accountId: Uuid,
        did: AtprotoDid,
        operation: PlcOperation,
        resolvedCid: String,
    ) {
        hostedPlcOperationRepository?.save(
            StoredHostedPlcOperation(
                id = Uuid.random(),
                accountId = accountId,
                did = did.toString(),
                cid = resolvedCid,
                prevCid = operation.prev,
                operationType = operation.type,
                operationJson = json.encodeToString(PlcOperation.serializer(), operation),
                createdAt = Clock.System.now(),
                publicationAttempted = config.publishHostedPlcOperations,
            ),
        )
    }

    private fun buildHostedAtprotoUpdate(
        prevCid: String,
        handle: String,
        signingKeyDidKey: String,
        recoveryDidKey: String?,
    ): PlcUnsignedOperation =
        PlcOperations.atprotoUpdate(
            prevCid = prevCid,
            handle = Handle.require(handle.trim().trim('.').lowercase()).toString(),
            pdsServiceEndpoint = config.pdsServiceEndpoint,
            signingKeyDidKey = signingKeyDidKey,
            rotationKeys = listOfNotNull(recoveryDidKey, signingKeyDidKey).distinct(),
        )

    private fun signOperation(
        payload: ByteArray,
        privateKey: java.security.PrivateKey,
        curve: EcCurve,
    ): String =
        Base64.UrlSafe
            .encode(EcKeySupport.signSha256(privateKey = privateKey, curve = curve, payload = payload))
            .trimEnd('=')

    private fun verifyRecoverySignature(
        recoveryDidKey: String,
        payload: ByteArray,
        signature: String,
    ) {
        val multikey = recoveryDidKey.removePrefix(DID_KEY_PREFIX)
        val decoded = EcKeySupport.decodePublicKey(multikey)
        val verified =
            runCatching {
                val signatureBytes = decodeBase64Url(signature)
                // The directory accepts only low-S signatures, so the hosted log must not hold any other kind.
                EcKeySupport.hasLowS(signatureBytes, decoded.curve) &&
                    EcKeySupport.verifySha256(
                        publicKey = decoded.publicKey,
                        curve = decoded.curve,
                        payload = payload,
                        signature = signatureBytes,
                    )
            }.getOrDefault(false)
        if (!verified) {
            throw IdentityLifecycleValidationException("Client PLC recovery signature is invalid", code = RECOVERY_SIGNATURE_INVALID)
        }
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val normalized =
            when (value.length % 4) {
                0 -> value
                2 -> "$value=="
                3 -> "$value="
                else -> throw IllegalArgumentException("Invalid base64url value")
            }
        return Base64.UrlSafe.decode(normalized)
    }

    companion object {
        private const val DID_KEY_PREFIX: String = "did:key:"

        /** The server does not support recovery keys, or the identity is not a `did:plc`. */
        const val RECOVERY_KEYS_UNAVAILABLE: String = "RECOVERY_KEYS_UNAVAILABLE"

        /** The identity's latest operation does not list the registered recovery key. */
        const val RECOVERY_KEY_NOT_ON_IDENTITY: String = "RECOVERY_KEY_NOT_ON_IDENTITY"

        /** Another operation was appended between preparing and completing a recovery. */
        const val IDENTITY_CHANGED: String = "IDENTITY_CHANGED"

        /** The recovery signature is not the registered recovery key's low-S signature over the operation. */
        const val RECOVERY_SIGNATURE_INVALID: String = "RECOVERY_SIGNATURE_INVALID"

        /** The identity's latest operation does not list the server's current key, so the server cannot sign. */
        const val SERVER_KEY_NOT_ON_IDENTITY: String = "SERVER_KEY_NOT_ON_IDENTITY"
    }
}
