package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.AtprotoIdentityTestSupport.plcService
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcLogEntry
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class AtprotoIdentitySigningTest {
    @Test
    fun `rotateSigningKey updates did web accounts and rejects unpublished hosted plc rotation`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val webService =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.WEB,
                        ),
                )
            val webAccount =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "rotate-web",
                        displayName = "Rotate Web",
                        createdAt = Clock.System.now(),
                    ),
                )
            val ensuredWeb = webService.ensureIdentity(webAccount)

            val rotated = webService.rotateSigningKey(ensuredWeb)

            assertEquals("did:web:rotate-web.logdate.app", rotated.account.did)
            assertNotNull(rotated.account.signingKeyPublic)
            assertTrue(rotated.account.signingKeyPublic != rotated.previousPublicKeyMultibase)
            assertEquals(rotated.account.signingKeyPublic, rotated.activeKey.publicKeyMultibase)

            val plcRepository = InMemoryAccountRepository()
            val plcService =
                AtprotoIdentityService(
                    accountRepository = plcRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val plcAccount =
                plcRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "rotate-plc",
                        displayName = "Rotate PLC",
                        createdAt = Clock.System.now(),
                    ),
                )
            val ensuredPlc = plcService.ensureIdentity(plcAccount)

            val error =
                assertFailsWith<IdentityLifecycleConflictException> {
                    plcService.rotateSigningKey(ensuredPlc)
                }

            assertTrue(error.message!!.contains("published PLC operations"))
        }

    @Test
    fun `rotateSigningKey publishes hosted plc updates when configured and import can migrate to a different exported key`() =
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
                        username = "rotate-hosted",
                        displayName = "Rotate Hosted",
                        createdAt = Clock.System.now(),
                    ),
                )
            val ensured = service.ensureIdentity(account)
            seedPublishedGenesis(operationRepository, requireNotNull(ensured.did), published)
            val otherAccount =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "other-export",
                        displayName = "Other Export",
                        createdAt = Clock.System.now(),
                    ),
                )
            val otherEnsured = service.ensureIdentity(otherAccount)
            val migratedExport = signingKeyService.exportActiveKey(otherEnsured.id, "secret")

            val rotated = service.rotateSigningKey(ensured)
            val imported =
                service.importSigningKey(
                    rotated.account,
                    signingKeyService.exportActiveKey(rotated.account.id, "rotated"),
                    "rotated",
                )

            assertTrue(rotated.account.did?.startsWith("did:plc:") == true)
            assertNotNull(rotated.plcOperation)
            assertTrue(published.getValue(requireNotNull(rotated.account.did)).size >= 2)
            assertEquals(rotated.activeKey.publicKeyMultibase, imported.activeKey.publicKeyMultibase)
            val migrated =
                service.importSigningKey(
                    rotated.account,
                    migratedExport,
                    "secret",
                )

            assertEquals(migratedExport.publicKeyMultibase, migrated.activeKey.publicKeyMultibase)
            assertEquals(migratedExport.publicKeyMultibase, migrated.account.signingKeyPublic)
            assertTrue(published.getValue(requireNotNull(rotated.account.did)).size >= 3)
        }

    @Test
    fun `importSigningKey replaces did web signing keys with a different exported key`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = signingKeyService,
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.WEB,
                        ),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "web-import",
                        displayName = "Web Import",
                        createdAt = Clock.System.now(),
                    ),
                )
            val donor =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "web-donor",
                        displayName = "Web Donor",
                        createdAt = Clock.System.now(),
                    ),
                )
            val ensured = service.ensureIdentity(account)
            val donorEnsured = service.ensureIdentity(donor)
            val donorExport = signingKeyService.exportActiveKey(donorEnsured.id, "donor-secret")

            val imported =
                service.importSigningKey(
                    ensured,
                    donorExport,
                    "donor-secret",
                )

            assertEquals("did:web:web-import.logdate.app", imported.account.did)
            assertEquals("web-import.logdate.app", imported.account.handle)
            assertEquals(donorExport.publicKeyMultibase, imported.activeKey.publicKeyMultibase)
            assertEquals(donorExport.publicKeyMultibase, imported.account.signingKeyPublic)
        }
}
