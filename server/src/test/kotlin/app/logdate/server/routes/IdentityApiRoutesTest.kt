package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.HostedAccountDidMethod
import app.logdate.server.identity.InMemoryHostedPlcOperationRepository
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.PlcIdentityService
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.didKeyFor
import app.logdate.server.identity.seedPublishedGenesis
import app.logdate.server.passkeys.TestPasskeyStepUp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcLogEntry
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Integration tests for the identity management API routes.
 *
 * This suite covers the lifecycle of cryptographic signing keys, including export,
 * rotation, and import operations. It validates complex identity workflows such as
 * DID PLC recovery, cross-account key migration, and passphrase-protected key
 * material handling, ensuring compatibility with both DID Web and DID PLC methods.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class IdentityApiRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `signing key export requires bearer auth and non blank passphrase`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val env = configureAuthV1TestApp(accountRepository = accountRepository)
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "alice",
                        displayName = "Alice",
                        createdAt = Clock.System.now(),
                    ),
                )
            val accessToken = env.tokenService.generateAccessToken(account.id.toString())

            val missingAuth =
                client.post("/api/v1/identity/signing-key/export") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"secret"}""")
                }
            val blankPassphrase =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"   "}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, missingAuth.status)
            assertEquals(HttpStatusCode.BadRequest, blankPassphrase.status)
            assertEquals(
                "PASSPHRASE_REQUIRED",
                json
                    .parseToJsonElement(blankPassphrase.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `signing key export returns decryptable key material for authenticated account`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val env = configureAuthV1TestApp(accountRepository = accountRepository)
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "brie",
                        displayName = "Brie",
                        createdAt = Clock.System.now(),
                    ),
                )
            val accessToken = env.tokenService.generateAccessToken(account.id.toString())

            val response =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"correct horse battery staple"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<ExportSigningKeyResponse>(response.bodyAsText())
            val decrypted =
                env.signingKeyService.decryptExportedKey(
                    payload.data.exportedKey,
                    passphrase = "correct horse battery staple",
                )

            assertTrue(payload.success)
            assertEquals("brie.logdate.app", payload.data.handle)
            assertTrue(payload.data.did.startsWith("did:plc:"))
            assertEquals("K-256", payload.data.exportedKey.algorithm)
            assertTrue(
                payload.data.exportedKey.publicKeyDidKey
                    .startsWith("did:key:z"),
            )
            assertNotNull(decrypted)
            assertEquals("EC", decrypted.algorithm)
        }

    @Test
    fun `signing key export rejects blank invalid malformed and missing-account tokens`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val env = configureAuthV1TestApp(accountRepository = accountRepository)
            val existingAccount =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "cora",
                        displayName = "Cora",
                        createdAt = Clock.System.now(),
                    ),
                )

            val blankBearer =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer    ")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"secret"}""")
                }
            val invalidToken =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer not-a-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"secret"}""")
                }
            val malformedAccountId =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer ${env.tokenService.generateAccessToken("not-a-uuid")}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"secret"}""")
                }
            val missingAccount =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer ${env.tokenService.generateAccessToken(Uuid.random().toString())}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"secret"}""")
                }
            val validAccount =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer ${env.tokenService.generateAccessToken(existingAccount.id.toString())}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"secret"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, blankBearer.status)
            assertEquals(HttpStatusCode.Unauthorized, invalidToken.status)
            assertEquals(HttpStatusCode.Unauthorized, malformedAccountId.status)
            assertEquals(HttpStatusCode.NotFound, missingAccount.status)
            assertEquals(HttpStatusCode.OK, validAccount.status)
        }

    @Test
    fun `signing key export returns server error when key export fails`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val tokenService = JwtTokenService("identity-api-route-secret")
            val signingKeyService = mockk<SigningKeyService>()
            val identityService =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "dana",
                        displayName = "Dana",
                        createdAt = Clock.System.now(),
                    ),
                )

            coEvery { signingKeyService.exportActiveKey(account.id, "explode") } throws IllegalStateException("boom")

            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    route("/api/v1") {
                        identityApiRoutes(
                            accountRepository = accountRepository,
                            tokenService = tokenService,
                            atprotoIdentityService = identityService,
                            signingKeyService = signingKeyService,
                        )
                    }
                }
            }

            val response =
                client.post("/api/v1/identity/signing-key/export") {
                    header(HttpHeaders.Authorization, "Bearer ${tokenService.generateAccessToken(account.id.toString())}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"explode"}""")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(
                "SIGNING_KEY_EXPORT_FAILED",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `signing key rotation returns exported replacement key for did web accounts`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val env =
                configureAuthV1TestApp(
                    accountRepository = accountRepository,
                    atprotoIdentityConfig =
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
                        username = "rotate-web",
                        displayName = "Rotate Web",
                        createdAt = Clock.System.now(),
                    ),
                )
            val accessToken = env.tokenService.generateAccessToken(account.id.toString())

            val response =
                client.post("/api/v1/identity/signing-key/rotate") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"new-secret"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<RotateSigningKeyResponse>(response.bodyAsText())
            val decrypted = env.signingKeyService.decryptExportedKey(payload.data.exportedKey, "new-secret")

            assertTrue(payload.success)
            assertEquals("rotate-web.logdate.app", payload.data.handle)
            assertEquals("did:web:rotate-web.logdate.app", payload.data.did)
            assertTrue(payload.data.previousPublicKeyDidKey.startsWith("did:key:z"))
            assertEquals("EC", decrypted.algorithm)
        }

    @Test
    fun `signing key rotation rejects hosted plc accounts without plc publishing`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val env = configureAuthV1TestApp(accountRepository = accountRepository)
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "rotate-plc",
                        displayName = "Rotate PLC",
                        createdAt = Clock.System.now(),
                    ),
                )
            val accessToken = env.tokenService.generateAccessToken(account.id.toString())

            val response =
                client.post("/api/v1/identity/signing-key/rotate") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"passphrase":"new-secret"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(
                "SIGNING_KEY_ROTATION_CONFLICT",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `signing key import restores matching active key material and can migrate did web accounts to a different exported key`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val env =
                configureAuthV1TestApp(
                    accountRepository = accountRepository,
                    atprotoIdentityConfig =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.WEB,
                        ),
                )
            val firstAccount =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "importable",
                        displayName = "Importable",
                        createdAt = Clock.System.now(),
                    ),
                )
            val secondAccount =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "other-user",
                        displayName = "Other User",
                        createdAt = Clock.System.now(),
                    ),
                )
            val firstToken = env.tokenService.generateAccessToken(firstAccount.id.toString())
            val firstEnsured = env.atprotoIdentityService.ensureIdentity(firstAccount)
            val secondEnsured = env.atprotoIdentityService.ensureIdentity(secondAccount)
            val matchingExport = env.signingKeyService.exportActiveKey(firstEnsured.id, "import-secret")
            val mismatchedExport = env.signingKeyService.exportActiveKey(secondEnsured.id, "other-secret")

            val success =
                client.post("/api/v1/identity/signing-key/import") {
                    header(HttpHeaders.Authorization, "Bearer $firstToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            ImportSigningKeyRequest.serializer(),
                            ImportSigningKeyRequest(
                                passphrase = "import-secret",
                                exportedKey = matchingExport,
                            ),
                        ),
                    )
                }
            val migrated =
                client.post("/api/v1/identity/signing-key/import") {
                    header(HttpHeaders.Authorization, "Bearer $firstToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            ImportSigningKeyRequest.serializer(),
                            ImportSigningKeyRequest(
                                passphrase = "other-secret",
                                exportedKey = mismatchedExport,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, success.status)
            assertEquals(HttpStatusCode.OK, migrated.status)
            val payload = json.decodeFromString<ImportSigningKeyResponse>(success.bodyAsText())
            val migratedPayload = json.decodeFromString<ImportSigningKeyResponse>(migrated.bodyAsText())
            assertTrue(payload.success)
            assertEquals("importable.logdate.app", payload.data.handle)
            assertEquals("did:web:importable.logdate.app", payload.data.did)
            assertTrue(payload.data.publicKeyDidKey.startsWith("did:key:z"))
            assertEquals("did:web:importable.logdate.app", migratedPayload.data.did)
            assertEquals("importable.logdate.app", migratedPayload.data.handle)
            assertEquals(mismatchedExport.publicKeyDidKey, migratedPayload.data.publicKeyDidKey)
        }

    @Test
    fun `signing key import can migrate hosted plc accounts when published plc operations are enabled`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val tokenService = JwtTokenService("identity-api-route-secret")
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val hostedPlcOperationRepository = InMemoryHostedPlcOperationRepository()
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val identityService =
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
                            hostedPlcOperationRepository = hostedPlcOperationRepository,
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
                        ),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "plc-import",
                        displayName = "PLC Import",
                        createdAt = Clock.System.now(),
                    ),
                )
            val donor =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "plc-donor",
                        displayName = "PLC Donor",
                        createdAt = Clock.System.now(),
                    ),
                )
            val ensured = identityService.ensureIdentity(account)
            seedPublishedGenesis(hostedPlcOperationRepository, requireNotNull(ensured.did), published)
            val accessToken = tokenService.generateAccessToken(account.id.toString())
            val donorEnsured = identityService.ensureIdentity(donor)
            val donorExport = signingKeyService.exportActiveKey(donorEnsured.id, "donor-secret")

            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    route("/api/v1") {
                        identityApiRoutes(
                            accountRepository = accountRepository,
                            tokenService = tokenService,
                            atprotoIdentityService = identityService,
                            signingKeyService = signingKeyService,
                        )
                    }
                }
            }

            val response =
                client.post("/api/v1/identity/signing-key/import") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            ImportSigningKeyRequest.serializer(),
                            ImportSigningKeyRequest(
                                passphrase = "donor-secret",
                                exportedKey = donorExport,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<ImportSigningKeyResponse>(response.bodyAsText())
            assertTrue(payload.success)
            assertTrue(payload.data.did.startsWith("did:plc:"))
            assertEquals("plc-import.logdate.app", payload.data.handle)
            assertEquals(donorExport.publicKeyDidKey, payload.data.publicKeyDidKey)
            assertTrue(hostedPlcOperationRepository.listByDid(payload.data.did).size >= 2)
        }

    @Test
    fun `recovery signing key import prepare and complete flow accepts a client signed plc operation`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val tokenService = JwtTokenService("identity-api-route-secret")
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val hostedPlcOperationRepository = InMemoryHostedPlcOperationRepository()
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val identityService =
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
                            hostedPlcOperationRepository = hostedPlcOperationRepository,
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
                        ),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "recovery-import",
                        displayName = "Recovery Import",
                        createdAt = Clock.System.now(),
                    ),
                )
            val donor =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "recovery-donor",
                        displayName = "Recovery Donor",
                        createdAt = Clock.System.now(),
                    ),
                )
            val accessToken = tokenService.generateAccessToken(account.id.toString())
            val ensured = identityService.ensureIdentity(account)
            seedPublishedGenesis(hostedPlcOperationRepository, requireNotNull(ensured.did), published)
            val recoveryKey = signingKeyService.generateKeyPair(curve = EcCurve.P256)
            val recoveryDidKey = didKeyFor(recoveryKey.publicKeyMultibase)
            identityService.registerPlcRecoveryKey(ensured, recoveryDidKey)
            val donorEnsured = identityService.ensureIdentity(donor)
            val donorExport = signingKeyService.exportActiveKey(donorEnsured.id, "donor-secret")

            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    route("/api/v1") {
                        identityApiRoutes(
                            accountRepository = accountRepository,
                            tokenService = tokenService,
                            atprotoIdentityService = identityService,
                            signingKeyService = signingKeyService,
                        )
                    }
                }
            }

            val prepareResponse =
                client.post("/api/v1/identity/signing-key/import/recovery/prepare") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PrepareRecoverySigningKeyImportRequest.serializer(),
                            PrepareRecoverySigningKeyImportRequest(
                                passphrase = "donor-secret",
                                exportedKey = donorExport,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, prepareResponse.status)
            val prepared = json.decodeFromString<PrepareRecoverySigningKeyImportResponse>(prepareResponse.bodyAsText())
            val signature =
                Base64.UrlSafe
                    .encode(
                        EcKeySupport.signSha256(
                            privateKey = EcKeySupport.decodePrivateKey(recoveryKey.privateKeyPkcs8),
                            curve = EcCurve.P256,
                            payload = decodeBase64Url(prepared.data.signingPayloadBase64Url),
                        ),
                    ).trimEnd('=')

            val completeResponse =
                client.post("/api/v1/identity/signing-key/import/recovery/complete") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            CompleteRecoverySigningKeyImportRequest.serializer(),
                            CompleteRecoverySigningKeyImportRequest(
                                passphrase = "donor-secret",
                                exportedKey = donorExport,
                                signature = signature,
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, completeResponse.status)
            val payload = json.decodeFromString<ImportSigningKeyResponse>(completeResponse.bodyAsText())
            assertEquals(recoveryDidKey, prepared.data.recoveryDidKey)
            assertEquals(donorExport.publicKeyDidKey, prepared.data.nextPublicKeyDidKey)
            assertEquals(donorExport.publicKeyDidKey, payload.data.publicKeyDidKey)
        }

    @Test
    fun `identity status and plc operation history return hosted recovery state`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val tokenService = JwtTokenService("identity-api-route-secret")
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val hostedPlcOperationRepository = InMemoryHostedPlcOperationRepository()
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val identityService =
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
                            hostedPlcOperationRepository = hostedPlcOperationRepository,
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
                        ),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "history-user",
                        displayName = "History User",
                        createdAt = Clock.System.now(),
                    ),
                )
            val accessToken = tokenService.generateAccessToken(account.id.toString())
            val recoveryDidKey = didKeyFor(signingKeyService.ensureActiveKey(Uuid.random()).publicKeyMultibase)
            val ensured = identityService.ensureIdentity(account)
            seedPublishedGenesis(hostedPlcOperationRepository, requireNotNull(ensured.did), published)
            identityService.registerPlcRecoveryKey(ensured, recoveryDidKey)

            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    route("/api/v1") {
                        identityApiRoutes(
                            accountRepository = accountRepository,
                            tokenService = tokenService,
                            atprotoIdentityService = identityService,
                            signingKeyService = signingKeyService,
                        )
                    }
                }
            }

            val statusResponse =
                client.get("/api/v1/identity") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }
            val operationsResponse =
                client.get("/api/v1/identity/plc/operations") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                }

            assertEquals(HttpStatusCode.OK, statusResponse.status)
            assertEquals(HttpStatusCode.OK, operationsResponse.status)

            val statusJson = json.parseToJsonElement(statusResponse.bodyAsText()).jsonObject
            val statusData = statusJson.getValue("data").jsonObject
            assertEquals(true, statusJson.getValue("success").jsonPrimitive.boolean)
            assertTrue(
                statusData
                    .getValue("did")
                    .jsonPrimitive
                    .content
                    .startsWith("did:plc:"),
            )
            assertEquals(
                "history-user.logdate.app",
                statusData.getValue("handle").jsonPrimitive.content,
            )
            assertEquals(
                recoveryDidKey,
                statusData.getValue("plcRecoveryDidKey").jsonPrimitive.content,
            )
            assertTrue(
                statusData
                    .getValue("signingKeyDidKey")
                    .jsonPrimitive
                    .content
                    .startsWith("did:key:z"),
            )
            assertEquals(2, statusData.getValue("plcOperationCount").jsonPrimitive.int)

            val operationsJson = json.parseToJsonElement(operationsResponse.bodyAsText()).jsonObject
            val operationsData = operationsJson.getValue("data").jsonArray
            assertEquals(true, operationsJson.getValue("success").jsonPrimitive.boolean)
            assertEquals(2, operationsData.size)
            assertEquals(
                "plc_operation",
                operationsData
                    .last()
                    .jsonObject
                    .getValue("operationType")
                    .jsonPrimitive
                    .content,
            )
            assertTrue(
                operationsData
                    .last()
                    .jsonObject
                    .getValue("operationJson")
                    .jsonPrimitive
                    .content
                    .contains(recoveryDidKey),
            )
        }

    @Test
    fun `plc recovery key registration publishes hosted update and persists canonical did key`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val tokenService = JwtTokenService("identity-api-route-secret")
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val published = mutableMapOf<String, MutableList<PlcIndexedOperation>>()
            val hostedPlcOperationRepository = InMemoryHostedPlcOperationRepository()
            val identityService =
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
                            hostedPlcOperationRepository = hostedPlcOperationRepository,
                            plcDirectoryClient =
                                object : PlcDirectoryClient {
                                    override suspend fun getDocument(
                                        did: studio.hypertext.atproto.identity.AtprotoDid,
                                    ): Result<studio.hypertext.atproto.identity.DidDocument> {
                                        error("unused")
                                    }

                                    override suspend fun getOperationLog(
                                        did: studio.hypertext.atproto.identity.AtprotoDid,
                                    ): Result<List<PlcLogEntry>> {
                                        error("unused")
                                    }

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
            val ensured = identityService.ensureIdentity(account)
            seedPublishedGenesis(hostedPlcOperationRepository, requireNotNull(ensured.did), published)
            val accessToken = tokenService.generateAccessToken(account.id.toString())
            val recoveryDidKey = didKeyFor(signingKeyService.ensureActiveKey(Uuid.random()).publicKeyMultibase)
            val stepUp = TestPasskeyStepUp()

            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    route("/api/v1") {
                        identityApiRoutes(
                            accountRepository = accountRepository,
                            tokenService = tokenService,
                            atprotoIdentityService = identityService,
                            signingKeyService = signingKeyService,
                            passkeyStepUp = stepUp,
                        )
                    }
                }
            }

            val response =
                client.post("/api/v1/identity/plc/recovery-key") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            RegisterPlcRecoveryKeyRequest.serializer(),
                            RegisterPlcRecoveryKeyRequest(
                                recoveryDidKey = "  DID:KEY:${recoveryDidKey.removePrefix("did:key:")}  ",
                                stepUp = stepUp.proofFor(account.id),
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString<RegisterPlcRecoveryKeyResponse>(response.bodyAsText())

            assertTrue(payload.success)
            assertEquals(recoveryDidKey, payload.data.recoveryDidKey)
            assertTrue(payload.data.did.startsWith("did:plc:"))
            assertEquals(recoveryDidKey, accountRepository.findById(account.id)?.plcRecoveryDidKey)
            assertEquals(2, published.getValue(payload.data.did).size)
        }

    @Test
    fun `plc recovery key registration rejects invalid did keys and did web identities`() =
        testApplication {
            val invalidAccountRepository = InMemoryAccountRepository()
            val invalidEnv = configureAuthV1TestApp(accountRepository = invalidAccountRepository)
            val invalidAccount =
                invalidAccountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "invalid-recovery",
                        displayName = "Invalid Recovery",
                        createdAt = Clock.System.now(),
                    ),
                )
            val invalidToken = invalidEnv.tokenService.generateAccessToken(invalidAccount.id.toString())
            val invalidResponse =
                client.post("/api/v1/identity/plc/recovery-key") {
                    header(HttpHeaders.Authorization, "Bearer $invalidToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"recoveryDidKey":"not-a-did-key"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, invalidResponse.status)
            assertEquals(
                "PLC_RECOVERY_KEY_INVALID",
                json
                    .parseToJsonElement(invalidResponse.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `plc recovery key registration rejects did web accounts`() =
        testApplication {
            val accountRepository = InMemoryAccountRepository()
            val env =
                configureAuthV1TestApp(
                    accountRepository = accountRepository,
                    atprotoIdentityConfig =
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
                        username = "web-recovery",
                        displayName = "Web Recovery",
                        createdAt = Clock.System.now(),
                    ),
                )
            val accessToken = env.tokenService.generateAccessToken(account.id.toString())
            val recoveryDidKey = didKeyFor(env.signingKeyService.ensureActiveKey(Uuid.random()).publicKeyMultibase)

            val response =
                client.post("/api/v1/identity/plc/recovery-key") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("""{"recoveryDidKey":"$recoveryDidKey"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(
                "RECOVERY_KEYS_UNAVAILABLE",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `identity export models serialize explicitly`() {
        val request = ExportSigningKeyRequest(passphrase = "secret")
        val exportedKey =
            SigningKeyService.ExportedSigningKey(
                algorithm = "P-256",
                publicKeyMultibase = "zPublic",
                publicKeyDidKey = "did:key:zPublic",
                encryptedPrivateKey = "ciphertext",
                salt = "salt",
                iv = "iv",
            )
        val data =
            ExportSigningKeyData(
                did = "did:plc:alice123",
                handle = "alice.logdate.app",
                exportedKey = exportedKey,
            )
        val rotateRequest = RotateSigningKeyRequest(passphrase = "rotate-secret")
        val rotateData =
            RotateSigningKeyData(
                did = "did:web:alice.logdate.app",
                handle = "alice.logdate.app",
                previousPublicKeyDidKey = "did:key:zOld",
                exportedKey = exportedKey,
            )
        val importRequest = ImportSigningKeyRequest(passphrase = "import-secret", exportedKey = exportedKey)
        val importData =
            ImportSigningKeyData(
                did = "did:web:alice.logdate.app",
                handle = "alice.logdate.app",
                publicKeyDidKey = "did:key:zPublic",
            )
        val recoveryKeyRequest = RegisterPlcRecoveryKeyRequest(recoveryDidKey = "did:key:zRecovery")
        val recoveryKeyData =
            RegisterPlcRecoveryKeyData(
                did = "did:plc:alice123",
                handle = "alice.logdate.app",
                recoveryDidKey = "did:key:zRecovery",
            )

        val requestJson = json.encodeToString(ExportSigningKeyRequest.serializer(), request)
        val dataJson = json.encodeToString(ExportSigningKeyData.serializer(), data)
        val rotateRequestJson = json.encodeToString(RotateSigningKeyRequest.serializer(), rotateRequest)
        val rotateDataJson = json.encodeToString(RotateSigningKeyData.serializer(), rotateData)
        val importRequestJson = json.encodeToString(ImportSigningKeyRequest.serializer(), importRequest)
        val importDataJson = json.encodeToString(ImportSigningKeyData.serializer(), importData)
        val recoveryKeyRequestJson = json.encodeToString(RegisterPlcRecoveryKeyRequest.serializer(), recoveryKeyRequest)
        val recoveryKeyDataJson = json.encodeToString(RegisterPlcRecoveryKeyData.serializer(), recoveryKeyData)
        val exportedKeyJson = json.encodeToString(SigningKeyService.ExportedSigningKey.serializer(), exportedKey)

        assertEquals(request, json.decodeFromString(ExportSigningKeyRequest.serializer(), requestJson))
        assertEquals(data, json.decodeFromString(ExportSigningKeyData.serializer(), dataJson))
        assertEquals(rotateRequest, json.decodeFromString(RotateSigningKeyRequest.serializer(), rotateRequestJson))
        assertEquals(rotateData, json.decodeFromString(RotateSigningKeyData.serializer(), rotateDataJson))
        assertEquals(importRequest, json.decodeFromString(ImportSigningKeyRequest.serializer(), importRequestJson))
        assertEquals(importData, json.decodeFromString(ImportSigningKeyData.serializer(), importDataJson))
        assertEquals(recoveryKeyRequest, json.decodeFromString(RegisterPlcRecoveryKeyRequest.serializer(), recoveryKeyRequestJson))
        assertEquals(recoveryKeyData, json.decodeFromString(RegisterPlcRecoveryKeyData.serializer(), recoveryKeyDataJson))
        assertEquals(exportedKey, json.decodeFromString(SigningKeyService.ExportedSigningKey.serializer(), exportedKeyJson))
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val padding = "=".repeat((4 - value.length % 4) % 4)
        return Base64.UrlSafe.decode(value + padding)
    }
}
