package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.AtprotoIdentityTestSupport.decodeBase64Url
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcLogEntry
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class AtprotoIdentityRecoveryTest {
    @Test
    fun `prepareRecoverySigningKeyImport and importSigningKeyWithRecovery use the registered PLC recovery key`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val operationRepository = InMemoryHostedPlcOperationRepository()
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = signingKeyService,
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            publishHostedPlcOperations = true,
                            recoveryKeysEnabled = true,
                        ),
                    plcIdentityService =
                        PlcIdentityService(
                            signingKeyService = signingKeyService,
                            config =
                                AtprotoIdentityConfig(
                                    handleDomain = "logdate.app",
                                    pdsServiceEndpoint = "https://logdate.app",
                                    publishHostedPlcOperations = true,
                                    recoveryKeysEnabled = true,
                                ),
                            hostedPlcOperationRepository = operationRepository,
                            plcDirectoryClient =
                                object : PlcDirectoryClient {
                                    override suspend fun getDocument(did: AtprotoDid): Result<DidDocument> = error("unused")

                                    override suspend fun getOperationLog(did: AtprotoDid): Result<List<PlcLogEntry>> = error("unused")

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
                        ),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "recoverable",
                        displayName = "Recoverable",
                        createdAt = Clock.System.now(),
                    ),
                )
            val ensured = service.ensureIdentity(account)
            seedPublishedGenesis(operationRepository, requireNotNull(ensured.did), published)
            val recoveryKey = signingKeyService.generateKeyPair(curve = EcCurve.P256)
            val recoveryDidKey = didKeyFor(recoveryKey.publicKeyMultibase)
            val recoveredAccount = service.registerPlcRecoveryKey(ensured, recoveryDidKey).account
            val donor =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "donor",
                        displayName = "Donor",
                        createdAt = Clock.System.now(),
                    ),
                )
            val donorEnsured = service.ensureIdentity(donor)
            val donorExport = signingKeyService.exportActiveKey(donorEnsured.id, "donor-secret")

            val prepared =
                service.prepareRecoverySigningKeyImport(
                    account = recoveredAccount,
                    exportedKey = donorExport,
                    passphrase = "donor-secret",
                )
            val signature =
                Base64.UrlSafe
                    .encode(
                        EcKeySupport.signSha256(
                            privateKey = EcKeySupport.decodePrivateKey(recoveryKey.privateKeyPkcs8),
                            curve = EcCurve.P256,
                            payload = decodeBase64Url(prepared.signingPayloadBase64Url),
                        ),
                    ).trimEnd('=')

            val imported =
                service.importSigningKeyWithRecovery(
                    account = recoveredAccount,
                    exportedKey = donorExport,
                    passphrase = "donor-secret",
                    recoverySignature = signature,
                )

            assertEquals(recoveryDidKey, prepared.recoveryDidKey)
            assertEquals(donorExport.publicKeyDidKey, prepared.nextSigningKeyDidKey)
            assertEquals(donorExport.publicKeyDidKey, didKeyFor(imported.activeKey.publicKeyMultibase))
        }

    @Test
    fun `registerPlcRecoveryKey publishes hosted plc update and persists canonical did key`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val operationRepository = InMemoryHostedPlcOperationRepository()
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val plcIdentityService =
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
                            override suspend fun getDocument(did: studio.hypertext.atproto.identity.AtprotoDid) = error("unused")

                            override suspend fun getOperationLog(did: studio.hypertext.atproto.identity.AtprotoDid) = error("unused")

                            override suspend fun getAuditLog(
                                did: studio.hypertext.atproto.identity.AtprotoDid,
                            ): Result<List<PlcIndexedOperation>> = Result.success(published[did.toString()].orEmpty())

                            override suspend fun export(
                                after: String?,
                                count: Int?,
                            ) = error("unused")

                            override suspend fun submit(
                                did: studio.hypertext.atproto.identity.AtprotoDid,
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
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = signingKeyService,
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            publishHostedPlcOperations = true,
                            recoveryKeysEnabled = true,
                        ),
                    plcIdentityService = plcIdentityService,
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "recoverable",
                        displayName = "Recoverable",
                        createdAt = Clock.System.now(),
                    ),
                )
            val ensured = service.ensureIdentity(account)
            seedPublishedGenesis(operationRepository, requireNotNull(ensured.did), published)
            val recoveryDidKey = didKeyFor(signingKeyService.ensureActiveKey(Uuid.random()).publicKeyMultibase)

            val registered = service.registerPlcRecoveryKey(ensured, "  DID:KEY:${recoveryDidKey.removePrefix("did:key:")}  ")

            assertEquals(recoveryDidKey, registered.recoveryDidKey)
            assertEquals(recoveryDidKey, registered.account.plcRecoveryDidKey)
            assertNotNull(registered.plcOperation)
            assertEquals(
                listOf(recoveryDidKey, "did:key:${registered.account.signingKeyPublic}"),
                registered.plcOperation.rotationKeys,
            )
        }

    @Test
    fun `registerPlcRecoveryKey rejects invalid did key and did web identities`() =
        kotlinx.coroutines.test.runTest {
            val invalidRepository = InMemoryAccountRepository()
            val invalidService =
                AtprotoIdentityService(
                    accountRepository = invalidRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val invalidAccount =
                invalidService.ensureIdentity(
                    invalidRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "invalid-recovery",
                            displayName = "Invalid Recovery",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )

            assertFailsWith<IdentityLifecycleValidationException> {
                invalidService.registerPlcRecoveryKey(invalidAccount, "not-a-did-key")
            }

            val webRepository = InMemoryAccountRepository()
            val webService =
                AtprotoIdentityService(
                    accountRepository = webRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.WEB,
                        ),
                )
            val webAccount =
                webService.ensureIdentity(
                    webRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "web-recovery",
                            displayName = "Web Recovery",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val validRecoveryDidKey =
                didKeyFor(
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
                        .ensureActiveKey(Uuid.random())
                        .publicKeyMultibase,
                )

            assertFailsWith<IdentityLifecycleConflictException> {
                webService.registerPlcRecoveryKey(webAccount, validRecoveryDidKey)
            }
        }
}
