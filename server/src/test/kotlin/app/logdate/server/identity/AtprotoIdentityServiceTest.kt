package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.logging.IdentityNotices
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for [AtprotoIdentityService], the central orchestrator for AT Protocol
 * identity lifecycle management.
 *
 * This suite verifies the complex integration between account management and
 * decentralized identity providers (PLC and Web). Key areas of focus include:
 * - Provisioning of new identities (DIDs, handles, and signing keys) for user accounts.
 * - Handle normalization and disambiguation logic for managed subdomains.
 * - Resolution of identities via both handles and DIDs across different DID methods.
 * - Advanced identity lifecycle operations: signing key rotation, PLC recovery key
 *   registration, and cross-account key imports.
 * - Generation of compliant DID documents for use by the wider AT Protocol network.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class AtprotoIdentityServiceTest {
    @Test
    fun `ensureIdentity provisions did handle and signing key`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "alice",
                        displayName = "Alice",
                        createdAt = Clock.System.now(),
                    ),
                )

            val ensured = service.ensureIdentity(account)

            assertEquals("alice.logdate.app", ensured.handle)
            assertTrue(ensured.did?.startsWith("did:plc:") == true)
            assertTrue(ensured.signingKeyPublic?.startsWith("z") == true)
            val ensuredHandle = assertNotNull(ensured.handle)
            assertEquals(ensured.did, accountRepository.findByDid(ensured.did)?.did)
            assertEquals(ensuredHandle, accountRepository.findByHandle(ensuredHandle)?.handle)
        }

    @Test
    fun `findByHandle resolves provisioned handle`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "bob",
                        displayName = "Bob",
                        createdAt = Clock.System.now(),
                    ),
                )
            service.ensureIdentity(account)

            val resolved = service.findByHandle("bob.logdate.app")

            assertTrue(resolved?.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `prepareRecoverySigningKeyImport and importSigningKeyWithRecovery use the registered PLC recovery key`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
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
                            hostedPlcOperationRepository = InMemoryHostedPlcOperationRepository(),
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
    fun `findByDid resolves from did web handle`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "carol",
                        displayName = "Carol",
                        createdAt = Clock.System.now(),
                    ),
                )
            service.ensureIdentity(account)

            val resolved =
                service.findByDid(
                    requireNotNull(accountRepository.findByUsername("carol")?.let { service.ensureIdentity(it).did }),
                )

            assertEquals("carol.logdate.app", resolved?.handle)
        }

    @Test
    fun `web mode provisions did web identities and resolves normalized did lookups`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
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
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "web-user",
                        displayName = "Web User",
                        createdAt = Clock.System.now(),
                    ),
                )

            val ensured = service.ensureIdentity(account)
            val resolved = service.findByDid("  DID:WEB:WEB-USER.LOGDATE.APP  ")

            assertEquals("did:web:web-user.logdate.app", ensured.did)
            assertEquals("web-user.logdate.app", resolved?.handle)
        }

    @Test
    fun `documentFor builds atproto pds did document`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://pds.logdate.app"),
                )
            val ensured =
                service.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "dana",
                            displayName = "Dana",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )

            val document = service.documentFor(ensured)

            assertTrue(document.id.toString().startsWith("did:plc:"))
            assertEquals(listOf("at://dana.logdate.app"), document.alsoKnownAs)
            assertEquals("https://pds.logdate.app", document.service.single().serviceEndpoint)
            assertEquals("${document.id}#atproto", document.verificationMethod.single().id)
        }

    @Test
    fun `ensureIdentity normalizes usernames into valid handles`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "LogDate.App.", pdsServiceEndpoint = "https://logdate.app"),
                )
            val ensured =
                service.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "eve_user",
                            displayName = "Eve",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )

            assertEquals("eve-user.logdate.app", ensured.handle)
            assertEquals("did:web:eve.logdate.app", service.didForHandle("EVE.LogDate.App").toString())
            assertNotNull(ensured.signingKeyPublic)
        }

    @Test
    fun `ensureIdentity disambiguates managed handle collisions`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val first =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "same_user",
                        displayName = "Same One",
                        createdAt = Clock.System.now(),
                    ),
                )
            val second =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "same-user",
                        displayName = "Same Two",
                        createdAt = Clock.System.now(),
                    ),
                )

            val firstEnsured = service.ensureIdentity(first)
            val secondEnsured = service.ensureIdentity(second)
            val secondHandle = assertNotNull(secondEnsured.handle)

            assertEquals("same-user.logdate.app", firstEnsured.handle)
            assertTrue(secondHandle.startsWith("same-user-"))
            assertTrue(secondHandle.endsWith(".logdate.app"))
            assertTrue(secondEnsured.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `ensureIdentity reuses canonical identity and normalizes stored casing`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = signingKeyService,
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val canonical =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "ida",
                        displayName = "Ida",
                        createdAt = Clock.System.now(),
                        handle = "ida.logdate.app",
                        did = "did:web:ida.logdate.app",
                        signingKeyPublic = "zCanonicalKey",
                    ),
                )

            assertSame(canonical, service.ensureIdentity(canonical))

            val mixedCase =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "jules",
                        displayName = "Jules",
                        createdAt = Clock.System.now(),
                        handle = "Jules.LogDate.App.",
                        did = "did:web:Jules.LogDate.App",
                        signingKeyPublic = "zMixedCase",
                    ),
                )

            val normalized = service.ensureIdentity(mixedCase)

            assertEquals("jules.logdate.app", normalized.handle)
            assertEquals("did:web:jules.logdate.app", normalized.did)
            assertEquals("zMixedCase", normalized.signingKeyPublic)
        }

    @Test
    fun `ensureIdentity saves normalized canonical values and trims canonical handle collisions`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val mixedCase =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "mina",
                        displayName = "Mina",
                        createdAt = Clock.System.now(),
                        handle = "Mina.LogDate.App.",
                        did = "did:web:mina.logdate.app",
                        signingKeyPublic = "zMixed",
                    ),
                )

            val normalized = service.ensureIdentity(mixedCase)

            assertEquals("mina.logdate.app", normalized.handle)
            assertEquals("did:web:mina.logdate.app", normalized.did)

            val longHandle = "${"a".repeat(60)}.logdate.app"
            val colliding =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "collision",
                        displayName = "Collision",
                        createdAt = Clock.System.now(),
                        handle = longHandle.uppercase(),
                        did = null,
                        signingKeyPublic = null,
                    ),
                )
            accountRepository.save(
                Account(
                    id = Uuid.random(),
                    username = "taken",
                    displayName = "Taken",
                    createdAt = Clock.System.now(),
                    handle = longHandle,
                    did = "did:web:${longHandle.lowercase()}",
                    signingKeyPublic = "zTaken",
                ),
            )

            val resolved = service.ensureIdentity(colliding)

            val resolvedHandle = assertNotNull(resolved.handle)
            assertTrue(resolvedHandle != longHandle)
            assertTrue(resolvedHandle.contains("-${colliding.id.toString().substring(0, 8).lowercase()}"))
            assertTrue(resolvedHandle.endsWith(".logdate.app"))
        }

    @Test
    fun `identity service backfills accounts and rejects invalid handle inputs`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val saved =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "kara",
                        displayName = "Kara",
                        createdAt = Clock.System.now(),
                    ),
                )

            assertEquals(listOf(saved.id), service.accountsMissingIdentity().map { it.id })
            assertEquals(IdentityBackfillResult(provisioned = 1, skipped = 0, failed = 0), service.backfillMissingIdentities())
            assertEquals(emptyList(), service.accountsMissingIdentity())

            val backfilled = accountRepository.findById(saved.id)
            assertEquals("kara.logdate.app", backfilled?.handle)
            assertTrue(backfilled?.did?.startsWith("did:plc:") == true)
            val preservedHandle =
                service.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "lena",
                            displayName = "Lena",
                            createdAt = Clock.System.now(),
                            handle = "lena.logdate.app",
                        ),
                    ),
                )
            assertEquals("lena.logdate.app", preservedHandle.handle)
            assertNull(service.findByHandle(" "))
            assertNull(service.findByDid(" "))
            assertNull(service.findByDid("did:plc:not-supported"))
            assertFailsWith<IllegalArgumentException> {
                service.didForHandle("invalid handle")
            }
        }

    @Test
    fun `document helpers require complete identity and expose server did document`() =
        kotlinx.coroutines.test.runTest {
            val service =
                AtprotoIdentityService(
                    accountRepository = InMemoryAccountRepository(),
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://pds.logdate.app",
                        ),
                )

            assertEquals("profile-user.logdate.app", service.defaultHandleFor("profile_user"))
            assertEquals("user.logdate.app", service.defaultHandleFor("!!!"))
            assertEquals("user.logdate.app", service.defaultHandleFor("staging"))
            assertEquals("user.logdate.app", service.defaultHandleFor("Staging"))

            val serverDocument = service.serverDocument()
            assertEquals("did:web:logdate.app", serverDocument.id.toString())
            assertTrue(serverDocument.verificationMethod.isEmpty())
            assertEquals("https://pds.logdate.app", serverDocument.service.single().serviceEndpoint)

            assertFailsWith<IllegalArgumentException> {
                service.documentFor(
                    Account(
                        id = Uuid.random(),
                        username = "missing-did",
                        displayName = "Missing DID",
                        createdAt = Clock.System.now(),
                        handle = "missing.logdate.app",
                        signingKeyPublic = "zKey",
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                service.documentFor(
                    Account(
                        id = Uuid.random(),
                        username = "missing-handle",
                        displayName = "Missing Handle",
                        createdAt = Clock.System.now(),
                        did = "did:web:missing.logdate.app",
                        signingKeyPublic = "zKey",
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                service.documentFor(
                    Account(
                        id = Uuid.random(),
                        username = "missing-key",
                        displayName = "Missing Key",
                        createdAt = Clock.System.now(),
                        handle = "missing.logdate.app",
                        did = "did:web:missing.logdate.app",
                    ),
                )
            }
        }

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

    @Test
    fun `registerPlcRecoveryKey publishes hosted plc update and persists canonical did key`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
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

    @Test
    fun `managed handle label reflection covers constrained lengths`() {
        val service =
            AtprotoIdentityService(
                accountRepository = InMemoryAccountRepository(),
                signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
            )
        val method =
            service::class.java
                .getDeclaredMethod("managedHandleLabel", String::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }

        assertEquals("a", method.invoke(service, "alpha", 0))
        assertEquals("user", method.invoke(service, "!!!", 5))
        assertEquals("user", method.invoke(service, "app", 63))
    }

    @Test
    fun `identity helper reflection covers did parsing fallback`() {
        val service =
            AtprotoIdentityService(
                accountRepository = InMemoryAccountRepository(),
                signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
            )
        val handleFromDid =
            service::class.java
                .getDeclaredMethod("handleFromDid", String::class.java)
                .apply { isAccessible = true }

        assertEquals("alice.logdate.app", handleFromDid.invoke(service, "did:web:alice.logdate.app"))
        assertNull(handleFromDid.invoke(service, "did:plc:alice123"))
    }

    @Test
    fun `resolving an unknown handle never loads every account`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val accountRepository = FullScanForbiddenAccountRepository(delegate)
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            service.ensureIdentity(
                delegate.save(Account(id = Uuid.random(), username = "provisioned", displayName = "P", createdAt = Clock.System.now())),
            )
            val legacy =
                delegate.save(Account(id = Uuid.random(), username = "legacy", displayName = "L", createdAt = Clock.System.now()))
            accountRepository.lookups = 0

            assertNull(service.findByHandle("unknown.logdate.app"))
            assertEquals(1, accountRepository.lookups)
            assertTrue(service.findByHandle("provisioned.logdate.app")?.did?.startsWith("did:plc:") == true)
            // An account without an identity is not resolvable, and looking it up never creates one.
            assertNull(service.findByHandle("legacy.logdate.app"))
            assertEquals(legacy, delegate.findById(legacy.id))
        }

    @Test
    fun `concurrent ensureIdentity calls provision one identity`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(Account(id = Uuid.random(), username = "race", displayName = "R", createdAt = Clock.System.now()))

            val results =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    (1..16).map { async { service.ensureIdentity(account) } }.awaitAll()
                }

            assertEquals(1, results.map { it.did }.toSet().size)
            assertEquals(results.first().did, accountRepository.findById(account.id)?.did)
        }

    @Test
    fun `ensureIdentity never brings back a deleted account`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service = plcService(accountRepository)
            val stale =
                accountRepository.save(newAccount("gone"))
            accountRepository.deleteAccount(stale.id)

            assertFailsWith<IdentityAccountMissingException> { service.ensureIdentity(stale) }
            assertNull(accountRepository.findById(stale.id))
            assertNull(accountRepository.findByHandle("gone.logdate.app"))
        }

    @Test
    fun `ensureIdentity provisions from the stored account rather than the caller's copy`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service = plcService(accountRepository)
            val stale =
                accountRepository.save(newAccount("renamed"))
            accountRepository.save(stale.copy(displayName = "New", bio = "Written after the caller read the account"))

            val ensured = service.ensureIdentity(stale)

            assertEquals("New", ensured.displayName)
            assertEquals("Written after the caller read the account", accountRepository.findById(stale.id)?.bio)
            assertTrue(accountRepository.findById(stale.id)?.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `ensureIdentity keeps the identity another writer assigned first`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val account =
                delegate.save(newAccount("contested"))
            val winner =
                account.copy(
                    handle = "contested.logdate.app",
                    did = "did:plc:winnerwinnerwinnerwinner",
                    signingKeyPublic = "zWinner",
                )
            val repository =
                object : app.logdate.server.auth.AccountRepository by delegate {
                    override suspend fun updateIdentityIfUnchanged(
                        expected: Account,
                        updated: Account,
                    ): Account? {
                        // Another server instance assigns its identity between this one's read and write.
                        delegate.save(winner)
                        return delegate.updateIdentityIfUnchanged(expected, updated)
                    }
                }

            val ensured = plcService(repository).ensureIdentity(account)

            assertEquals(winner.did, ensured.did)
            assertEquals(winner.did, delegate.findById(account.id)?.did)
        }

    @Test
    fun `backfill skips an account deleted after the listing`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val kept = delegate.save(newAccount("kept"))
            val deleted = delegate.save(newAccount("deleted"))
            val repository =
                object : app.logdate.server.auth.AccountRepository by delegate {
                    override suspend fun findAccountsMissingIdentity(): List<Account> {
                        val listed = delegate.findAccountsMissingIdentity()
                        delegate.deleteAccount(deleted.id)
                        return listed
                    }
                }

            val result = plcService(repository).backfillMissingIdentities()

            assertEquals(IdentityBackfillResult(provisioned = 1, skipped = 1, failed = 0), result)
            assertNull(delegate.findById(deleted.id))
            assertNull(delegate.findByHandle("deleted.logdate.app"))
            assertTrue(delegate.findById(kept.id)?.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `backfill logs each account that fails and keeps going`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val broken = delegate.save(newAccount("broken"))
            val healthy = delegate.save(newAccount("healthy"))
            val failure = IllegalStateException("database write failed")
            val repository =
                object : app.logdate.server.auth.AccountRepository by delegate {
                    override suspend fun updateIdentityIfUnchanged(
                        expected: Account,
                        updated: Account,
                    ): Account? {
                        if (expected.id == broken.id) throw failure
                        return delegate.updateIdentityIfUnchanged(expected, updated)
                    }
                }
            val logged = mutableListOf<Triple<LogLevel, String?, Throwable?>>()
            val antilog =
                object : Antilog() {
                    override fun performLog(
                        priority: LogLevel,
                        tag: String?,
                        throwable: Throwable?,
                        message: String?,
                    ) {
                        logged += Triple(priority, message, throwable)
                    }
                }

            Napier.base(antilog)
            val result =
                try {
                    plcService(repository).backfillMissingIdentities()
                } finally {
                    Napier.takeLogarithm(antilog)
                }

            assertEquals(IdentityBackfillResult(provisioned = 1, skipped = 0, failed = 1), result)
            assertEquals<List<Triple<LogLevel, String?, Throwable?>>>(
                listOf(Triple(LogLevel.ERROR, IdentityNotices.BACKFILL_ACCOUNT_FAILED, failure)),
                logged,
            )
            assertTrue(delegate.findById(healthy.id)?.did?.startsWith("did:plc:") == true)
            assertNull(delegate.findById(broken.id)?.did)
        }

    @Test
    fun `the conditional identity write refuses a changed or deleted account`() =
        kotlinx.coroutines.test.runTest {
            val repository = InMemoryAccountRepository()
            val account =
                repository.save(newAccount("cas").copy(bio = "bio"))
            val identity = account.copy(handle = "cas.logdate.app", did = "did:plc:casaaaaaaaaaaaaaaaaaaaaa", signingKeyPublic = "zKey")

            val written = assertNotNull(repository.updateIdentityIfUnchanged(expected = account, updated = identity.copy(bio = "ignored")))
            assertEquals("cas.logdate.app", written.handle)
            assertEquals("bio", written.bio)
            assertNull(repository.updateIdentityIfUnchanged(expected = account, updated = identity.copy(did = "did:plc:other")))
            repository.deleteAccount(account.id)
            assertNull(repository.updateIdentityIfUnchanged(expected = written, updated = identity))
            assertNull(repository.findById(account.id))
        }

    private fun newAccount(username: String) =
        Account(id = Uuid.random(), username = username, displayName = username, createdAt = Clock.System.now())

    private fun plcService(accountRepository: app.logdate.server.auth.AccountRepository) =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )

    private class FullScanForbiddenAccountRepository(
        private val delegate: InMemoryAccountRepository,
    ) : app.logdate.server.auth.AccountRepository by delegate {
        var lookups = 0

        override suspend fun findByHandle(handle: String): Account? {
            lookups++
            return delegate.findByHandle(handle)
        }

        override suspend fun getAllAccounts(): List<Account> = error("Handle resolution must not load every account")

        override suspend fun findAccountsMissingIdentity(): List<Account> = error("Handle resolution must not scan accounts")
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val normalized =
            when (value.length % 4) {
                0 -> value
                2 -> "$value=="
                3 -> "$value="
                else -> error("Invalid base64url value")
            }
        return Base64.UrlSafe.decode(normalized)
    }
}
