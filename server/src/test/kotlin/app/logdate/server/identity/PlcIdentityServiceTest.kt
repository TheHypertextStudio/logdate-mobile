package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcEncoding
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcLogEntry
import studio.hypertext.atproto.plc.PlcOperation
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for [PlcIdentityService], the specialized component for managing
 * identities using the `did:plc` (Placeholder Library Client) method.
 *
 * This suite focuses on the complex state transitions and external directory
 * interactions required for PLC-based identities:
 * - Provisioning of signed PLC operations for new account creation.
 * - Integration with the PLC directory for publishing and auditing identity logs.
 * - Secure management of the PLC operation chain, including CID-based sequence tracking.
 * - Identity lifecycle events: rotation of signing keys and updating of recovery keys.
 * - Implementation of the recovery flow, allowing an identity to be reclaimed or
 *   migrated using a registered recovery key.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class PlcIdentityServiceTest {
    @Test
    fun `provisionHostedDid derives did plc identity and preserves atproto plc document fields`() =
        kotlinx.coroutines.test.runTest {
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val operationRepository = InMemoryHostedPlcOperationRepository()
            val service =
                PlcIdentityService(
                    signingKeyService = signingKeyService,
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://pds.logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.PLC,
                        ),
                    hostedPlcOperationRepository = operationRepository,
                )
            val accountId = Uuid.random()
            val recoveryDidKey = didKeyFor(signingKeyService.ensureActiveKey(Uuid.random()).publicKeyMultibase)

            val provisioned =
                service.provisionHostedDid(
                    accountId = accountId,
                    handle = "alice.logdate.app",
                    recoveryDidKey = recoveryDidKey,
                )
            val repeated =
                service.provisionHostedDid(
                    accountId = accountId,
                    handle = "alice.logdate.app",
                    recoveryDidKey = recoveryDidKey,
                )

            assertTrue(provisioned.did.startsWith("did:plc:"))
            assertEquals(provisioned.did, repeated.did)
            assertEquals(listOf("at://alice.logdate.app"), provisioned.operation.alsoKnownAs)
            assertEquals(
                "https://pds.logdate.app",
                provisioned.operation.services
                    .getValue("atproto_pds")
                    .endpoint,
            )
            assertEquals(
                provisioned.publicKeyDidKey,
                provisioned.operation.verificationMethods.getValue("atproto"),
            )
            assertEquals(listOf(recoveryDidKey, provisioned.publicKeyDidKey), provisioned.operation.rotationKeys)
            assertEquals(AtprotoDid.require(provisioned.did), service.documentFor(provisioned).id)
            assertEquals(1, operationRepository.listByDid(provisioned.did).size)
            assertEquals(PlcEncoding.operationCid(provisioned.operation), operationRepository.listByDid(provisioned.did).first().cid)
        }

    @Test
    fun `provisionHostedDid stays private even when legacy publishing is enabled`() =
        kotlinx.coroutines.test.runTest {
            val published = mutableListOf<PlcIndexedOperation>()
            val service =
                PlcIdentityService(
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.PLC,
                            publishHostedPlcOperations = true,
                            recoveryKeysEnabled = true,
                        ),
                    hostedPlcOperationRepository = InMemoryHostedPlcOperationRepository(),
                    plcDirectoryClient =
                        object : PlcDirectoryClient {
                            override suspend fun getDocument(did: AtprotoDid) = error("unused")

                            override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

                            override suspend fun getAuditLog(did: AtprotoDid): Result<List<PlcIndexedOperation>> =
                                Result.success(published.filter { it.did == did })

                            override suspend fun export(
                                after: String?,
                                count: Int?,
                            ) = error("unused")

                            override suspend fun submit(
                                did: AtprotoDid,
                                entry: PlcLogEntry,
                            ): Result<Unit> =
                                runCatching {
                                    published +=
                                        PlcIndexedOperation(
                                            did = did,
                                            operation = entry,
                                            cid = "cid-${published.size + 1}",
                                            nullified = false,
                                            createdAt = "2026-03-09T00:00:00Z",
                                        )
                                }
                        },
                )

            val provisioned = service.provisionHostedDid(accountId = Uuid.random(), handle = "brie.logdate.app")

            assertTrue(provisioned.did.startsWith("did:plc:"))
            assertEquals(0, published.size)
        }

    @Test
    fun `identity service provisions did plc accounts by default and preserves existing did web identities`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val plcIdentityService =
                PlcIdentityService(SigningKeyService(InMemorySigningKeyRepository(), "test-kek"), AtprotoIdentityConfig())
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(),
                    plcIdentityService = plcIdentityService,
                )
            val hosted =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "cora",
                        displayName = "Cora",
                        createdAt = Clock.System.now(),
                    ),
                )
            val existingWeb =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "dana",
                        displayName = "Dana",
                        createdAt = Clock.System.now(),
                        handle = "dana.logdate.app",
                        did = "did:web:dana.logdate.app",
                        signingKeyPublic = "zDanaKey",
                    ),
                )

            val hostedEnsured = service.ensureIdentity(hosted)
            val existingEnsured = service.ensureIdentity(existingWeb)

            assertTrue(hostedEnsured.did?.startsWith("did:plc:") == true)
            assertEquals("cora.logdate.app", hostedEnsured.handle)
            assertEquals("did:web:dana.logdate.app", existingEnsured.did)
            assertFalse(existingEnsured.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `rotateHostedDid publishes plc update and activates next signing key`() =
        kotlinx.coroutines.test.runTest {
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val operationRepository = InMemoryHostedPlcOperationRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val service =
                PlcIdentityService(
                    signingKeyService = signingKeyService,
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.PLC,
                            publishHostedPlcOperations = true,
                            recoveryKeysEnabled = true,
                        ),
                    hostedPlcOperationRepository = operationRepository,
                    plcDirectoryClient =
                        object : PlcDirectoryClient {
                            override suspend fun getDocument(did: AtprotoDid) = error("unused")

                            override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

                            override suspend fun getAuditLog(did: AtprotoDid): Result<List<PlcIndexedOperation>> =
                                Result.success(published[did.toString()].orEmpty())

                            override suspend fun export(
                                after: String?,
                                count: Int?,
                            ) = error("unused")

                            override suspend fun submit(
                                did: AtprotoDid,
                                entry: PlcLogEntry,
                            ): Result<Unit> =
                                runCatching {
                                    val operations = published.getOrPut(did.toString()) { mutableListOf() }
                                    operations +=
                                        PlcIndexedOperation(
                                            did = did,
                                            operation = entry,
                                            cid = "cid-${operations.size + 1}",
                                            nullified = false,
                                            createdAt = "2026-03-09T00:00:00Z",
                                        )
                                }
                        },
                )
            val accountId = Uuid.random()
            val provisioned = service.provisionHostedDid(accountId = accountId, handle = "alice.logdate.app")
            service.publishStoredGenesis(operationRepository.listByDid(provisioned.did).single())
            val recoveryDidKey = didKeyFor(signingKeyService.ensureActiveKey(Uuid.random()).publicKeyMultibase)

            val rotated =
                service.rotateHostedDid(
                    accountId = accountId,
                    did = AtprotoDid.require(provisioned.did),
                    handle = "alice.logdate.app",
                    recoveryDidKey = recoveryDidKey,
                )
            val active = signingKeyService.ensureActiveKey(accountId)
            val update = published.getValue(provisioned.did).last().operation as PlcOperation

            assertEquals(provisioned.did, rotated.did)
            assertNotEquals(provisioned.publicKeyMultibase, rotated.publicKeyMultibase)
            assertEquals(rotated.publicKeyMultibase, active.publicKeyMultibase)
            assertEquals("cid-1", update.prev)
            assertEquals(rotated.publicKeyDidKey, update.verificationMethods.getValue("atproto"))
            assertEquals(listOf(recoveryDidKey, rotated.publicKeyDidKey), update.rotationKeys)
            assertTrue(update.isSigned)
            assertEquals(2, operationRepository.listByDid(provisioned.did).size)
            assertEquals("cid-1", operationRepository.listByDid(provisioned.did).last().prevCid)
            assertEquals("cid-2", operationRepository.listByDid(provisioned.did).last().cid)
        }

    @Test
    fun `updateHostedRecoveryKey publishes plc update without rotating active signing key`() =
        kotlinx.coroutines.test.runTest {
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val operationRepository = InMemoryHostedPlcOperationRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val service =
                PlcIdentityService(
                    signingKeyService = signingKeyService,
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.PLC,
                            publishHostedPlcOperations = true,
                            recoveryKeysEnabled = true,
                        ),
                    hostedPlcOperationRepository = operationRepository,
                    plcDirectoryClient =
                        object : PlcDirectoryClient {
                            override suspend fun getDocument(did: AtprotoDid) = error("unused")

                            override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

                            override suspend fun getAuditLog(did: AtprotoDid): Result<List<PlcIndexedOperation>> =
                                Result.success(published[did.toString()].orEmpty())

                            override suspend fun export(
                                after: String?,
                                count: Int?,
                            ) = error("unused")

                            override suspend fun submit(
                                did: AtprotoDid,
                                entry: PlcLogEntry,
                            ): Result<Unit> =
                                runCatching {
                                    val operations = published.getOrPut(did.toString()) { mutableListOf() }
                                    operations +=
                                        PlcIndexedOperation(
                                            did = did,
                                            operation = entry,
                                            cid = "cid-${operations.size + 1}",
                                            nullified = false,
                                            createdAt = "2026-03-09T00:00:00Z",
                                        )
                                }
                        },
                )
            val accountId = Uuid.random()
            val provisioned = service.provisionHostedDid(accountId = accountId, handle = "alice.logdate.app")
            service.publishStoredGenesis(operationRepository.listByDid(provisioned.did).single())
            val activeBefore = signingKeyService.ensureActiveKey(accountId)
            val recoveryDidKey = didKeyFor(signingKeyService.ensureActiveKey(Uuid.random()).publicKeyMultibase)

            val updated =
                service.updateHostedRecoveryKey(
                    accountId = accountId,
                    did = AtprotoDid.require(provisioned.did),
                    handle = "alice.logdate.app",
                    recoveryDidKey = recoveryDidKey,
                )
            val activeAfter = signingKeyService.ensureActiveKey(accountId)
            val update = published.getValue(provisioned.did).last().operation as PlcOperation

            assertEquals(provisioned.did, updated.did)
            assertEquals(recoveryDidKey, updated.recoveryDidKey)
            assertEquals(activeBefore.id, activeAfter.id)
            assertEquals("cid-1", update.prev)
            assertEquals(provisioned.publicKeyDidKey, update.verificationMethods.getValue("atproto"))
            assertEquals(listOf(recoveryDidKey, provisioned.publicKeyDidKey), update.rotationKeys)
            assertEquals(2, operationRepository.listByDid(provisioned.did).size)
            assertEquals("cid-2", operationRepository.listByDid(provisioned.did).last().cid)
        }

    @Test
    fun `prepareHostedRecoveryImport builds canonical payload and publishClientSignedRecoveryImport verifies signature`() =
        kotlinx.coroutines.test.runTest {
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val operationRepository = InMemoryHostedPlcOperationRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val service =
                PlcIdentityService(
                    signingKeyService = signingKeyService,
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.PLC,
                            publishHostedPlcOperations = true,
                            recoveryKeysEnabled = true,
                        ),
                    hostedPlcOperationRepository = operationRepository,
                    plcDirectoryClient =
                        object : PlcDirectoryClient {
                            override suspend fun getDocument(did: AtprotoDid) = error("unused")

                            override suspend fun getOperationLog(did: AtprotoDid) = error("unused")

                            override suspend fun getAuditLog(did: AtprotoDid): Result<List<PlcIndexedOperation>> =
                                Result.success(published[did.toString()].orEmpty())

                            override suspend fun export(
                                after: String?,
                                count: Int?,
                            ) = error("unused")

                            override suspend fun submit(
                                did: AtprotoDid,
                                entry: PlcLogEntry,
                            ): Result<Unit> =
                                runCatching {
                                    val operations = published.getOrPut(did.toString()) { mutableListOf() }
                                    operations +=
                                        PlcIndexedOperation(
                                            did = did,
                                            operation = entry,
                                            cid = "cid-${operations.size + 1}",
                                            nullified = false,
                                            createdAt = "2026-03-09T00:00:00Z",
                                        )
                                }
                        },
                )
            val accountId = Uuid.random()
            val provisioned = service.provisionHostedDid(accountId = accountId, handle = "alice.logdate.app")
            service.publishStoredGenesis(operationRepository.listByDid(provisioned.did).single())
            val recoveryKey = signingKeyService.generateKeyPair(curve = EcCurve.P256)
            val recoveryDidKey = didKeyFor(recoveryKey.publicKeyMultibase)
            service.updateHostedRecoveryKey(
                accountId = accountId,
                did = AtprotoDid.require(provisioned.did),
                handle = "alice.logdate.app",
                recoveryDidKey = recoveryDidKey,
            )
            val prepared =
                service.prepareHostedRecoveryImport(
                    accountId = accountId,
                    did = AtprotoDid.require(provisioned.did),
                    handle = "alice.logdate.app",
                    recoveryDidKey = recoveryDidKey,
                    nextSigningKeyDidKey = "did:key:zNextSigning",
                )
            val signature =
                Base64.UrlSafe
                    .encode(
                        EcKeySupport.signSha256(
                            privateKey = EcKeySupport.decodePrivateKey(recoveryKey.privateKeyPkcs8),
                            curve = EcCurve.P256,
                            payload = prepared.signingPayload,
                        ),
                    ).trimEnd('=')

            val signed =
                service.publishClientSignedRecoveryImport(
                    accountId = accountId,
                    did = AtprotoDid.require(provisioned.did),
                    recoveryDidKey = recoveryDidKey,
                    preparedOperation = prepared,
                    signature = signature,
                )

            assertEquals("cid-2", prepared.unsignedOperation.prev)
            assertEquals("did:key:zNextSigning", prepared.nextSigningKeyDidKey)
            assertEquals("did:key:zNextSigning", signed.verificationMethods.getValue("atproto"))
            assertEquals(listOf(recoveryDidKey, "did:key:zNextSigning"), signed.rotationKeys)
            assertTrue(signed.isSigned)
            assertEquals("cid-3", operationRepository.listByDid(provisioned.did).last().cid)
        }
}
