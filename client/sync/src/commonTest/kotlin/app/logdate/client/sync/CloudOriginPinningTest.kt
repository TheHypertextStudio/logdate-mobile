package app.logdate.client.sync

import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.cloud.DefaultCloudBackupDataSource
import app.logdate.client.sync.cloud.LogDateCloudApiClient
import app.logdate.client.sync.cloud.account.DefaultCloudAccountRepository
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.test.FakeSessionStorage
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import app.logdate.shared.model.diagnostics.SyncDiagnosticReport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CloudOriginPinningTest {
    private val serverA = "https://a.example"
    private val serverB = "https://b.example"

    @Test
    fun `old account queued note cannot upload its attachment with the new session`() =
        runTest {
            val api =
                app.logdate.client.sync.test
                    .fakeCloudApiClient()
            val media =
                app.logdate.client.media
                    .InMemoryMediaManager()
            val uri =
                media.saveMedia(
                    app.logdate.client.media
                        .MediaPayload("fixture.jpg", "image/jpeg", 1, byteArrayOf(1)),
                )
            val notes =
                app.logdate.client.sync.test
                    .fakeJournalNotesRepository()
            val note =
                app.logdate.client.repository.journals.JournalNote.Image(
                    Uuid.random(),
                    kotlin.time.Instant.fromEpochMilliseconds(1),
                    kotlin.time.Instant.fromEpochMilliseconds(1),
                    uri,
                )
            notes.create(note)
            val metadata =
                app.logdate.client.sync.test
                    .FakeSyncMetadataService(trackOperationIdentity = true)
            metadata.addPending(note.uid, app.logdate.client.sync.metadata.EntityType.NOTE)
            val manager =
                app.logdate.client.sync.test.testDefaultSyncManager(
                    cloudMediaDataSource =
                        app.logdate.client.sync.cloud
                            .DefaultCloudMediaDataSource(api),
                    mediaManager = media,
                    journalNotesRepository = notes,
                    syncMetadataService = metadata,
                )
            manager.uploadPendingChanges()
            assertTrue(api.uploadMediaCalls.isEmpty())
        }

    @Test
    fun `a delayed descriptor cannot enable rich drafts on a different server`() =
        runTest {
            val config = DefaultLogDateConfigRepository(initialBackendUrl = serverA)
            val descriptor =
                app.logdate.shared.model.ServerDescriptor(
                    serverOrigin = serverA,
                    apiBaseUrl = "$serverA/api/v1",
                    deploymentKind = app.logdate.shared.model.DeploymentKind.SELF_HOSTED,
                    displayName = "Fixture",
                    protocolFeatures = listOf(app.logdate.shared.model.ServerProtocolFeature.RICH_DRAFTS_V1),
                )
            config.updateBackendUrl(serverB)
            config.updateServerDescriptor(descriptor)
            kotlin.test.assertNull(config.getCurrentServerDescriptor())
        }

    @Test
    fun `a cancellation carried by a failed result remains an interruption`() =
        runTest {
            val refresher =
                SyncTokenRefresher(
                    app.logdate.client.sync.test
                        .fakeSessionStorage(),
                    app.logdate.client.sync.test
                        .fakeAccountRepository(),
                )
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                refresher.withFreshToken(
                    { Result.failure<Unit>(kotlinx.coroutines.CancellationException("private-cancel-marker")) },
                    "fixture",
                )
            }
        }

    @Test
    fun `successful responses after an account switch cannot authorize local settlement`() =
        runTest {
            for (refreshFirst in listOf(false, true)) {
                val config = DefaultLogDateConfigRepository(initialBackendUrl = serverA)
                val sessions =
                    FakeSessionStorage().apply {
                        currentOrigin = serverA
                        saveSession(UserSession("a-access", "a-refresh", "owner-a"))
                    }
                HttpClient(
                    MockEngine {
                        respond(
                            """{"success":true,"data":{"accessToken":"new-a-access"}}""",
                            HttpStatusCode.OK,
                            io.ktor.http.headersOf("Content-Type", "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }.use { http ->
                    val accounts =
                        DefaultCloudAccountRepository(
                            LogDateCloudApiClient(config, http),
                            InMemoryKeyValueStorage(),
                            config,
                            backgroundScope,
                            sessionStorage = sessions,
                        )
                    var calls = 0
                    val result =
                        SyncTokenRefresher(sessions, accounts).withFreshToken(
                            operation = {
                                calls++
                                if (refreshFirst && calls == 1) {
                                    Result.failure(
                                        app.logdate.client.sync.cloud
                                            .CloudApiException("EXPIRED", "Expired", 401),
                                    )
                                } else {
                                    config.updateBackendUrl(serverB)
                                    sessions.currentOrigin = serverB
                                    sessions.saveSession(UserSession("b-access", "b-refresh", "owner-b"))
                                    Result.success(Unit)
                                }
                            },
                            operationName = "fixture",
                            expectedScope = UploadScope("owner-a", serverA),
                        )
                    assertEquals(if (refreshFirst) 2 else 1, calls)
                    assertTrue(result.isFailure)
                    assertEquals("owner-b", sessions.getSession()?.accountId)
                }
            }
        }

    @Test
    fun `cloud API dispatch supplies request correlation without reporting response content`() =
        runTest {
            val config = DefaultLogDateConfigRepository(initialBackendUrl = serverA)
            val events = mutableListOf<SyncDiagnosticEvent>()
            var requestId: String? = null
            HttpClient(
                MockEngine { request ->
                    requestId = request.headers["X-Request-ID"]
                    respond("private-response-marker", HttpStatusCode.ServiceUnavailable)
                },
            ).use { http ->
                LogDateCloudApiClient(config, http, diagnostics = { events += it }).listBackups("private-token-marker")
            }
            assertTrue(
                requestId != null &&
                    DiagnosticReportCodec
                        .isCorrelationId(requestId!!),
            )
            assertTrue(events.isNotEmpty())
            assertEquals(requestId, events.last().requestId)
            assertEquals(503, events.last().httpStatus)
            val report =
                DiagnosticReportCodec.encode(
                    SyncDiagnosticReport(
                        Uuid.random().toString(),
                        events = events,
                    ),
                )
            assertTrue(
                !report.contains("private-response-marker") && !report.contains("private-token-marker") && !report.contains("a.example"),
            )
        }

    @Test
    fun `queued work cannot adopt a different server or account before its request begins`() =
        runTest {
            val config = DefaultLogDateConfigRepository(initialBackendUrl = serverB)
            val sessions =
                FakeSessionStorage().apply {
                    currentOrigin = serverB
                    saveSession(UserSession("b-access", "b-refresh", "owner-b"))
                }
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond("", HttpStatusCode.ServiceUnavailable)
                },
            ).use { http ->
                val api = LogDateCloudApiClient(config, http, sessions)
                val accounts =
                    DefaultCloudAccountRepository(api, InMemoryKeyValueStorage(), config, backgroundScope, sessionStorage = sessions)
                val refresher = SyncTokenRefresher(sessions, accounts)
                for (scope in listOf(
                    UploadScope("owner-b", serverA),
                    UploadScope("owner-a", serverB),
                )) {
                    assertTrue(refresher.withFreshToken(api::getAccountInfo, "account", expectedScope = scope).isFailure)
                }
            }
            assertEquals(0, requests)
        }

    @Test
    fun `direct backup call cannot send captured access token after URL switches servers`() =
        runTest {
            val delegate = DefaultLogDateConfigRepository(initialBackendUrl = serverA)
            val config =
                object : LogDateConfigRepository by delegate {
                    override val apiBaseUrl: Flow<String> =
                        flow {
                            delegate.updateBackendUrl(serverB)
                            emit(delegate.getCurrentApiBaseUrl())
                        }
                }
            val requestedHosts = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    requestedHosts += request.url.host
                    respond("", HttpStatusCode.ServiceUnavailable)
                },
            ) {
                install(ContentNegotiation) { json() }
            }.use { http ->
                DefaultCloudBackupDataSource(LogDateCloudApiClient(config, http)).listBackups("a-access")
            }

            assertTrue(requestedHosts.none { it == "b.example" })
        }

    @Test
    fun `direct media or backup call rejects old account token after destination already changed`() =
        runTest {
            val config = DefaultLogDateConfigRepository(initialBackendUrl = serverA)
            val sessions =
                FakeSessionStorage().apply {
                    currentOrigin = serverA
                    saveSession(UserSession("a-access", "a-refresh", "owner-a"))
                }
            val requestedHosts = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    requestedHosts += request.url.host
                    respond("", HttpStatusCode.ServiceUnavailable)
                },
            ) {
                install(ContentNegotiation) { json() }
            }.use { http ->
                val api = LogDateCloudApiClient(config, http, sessions)
                config.updateBackendUrl(serverB)
                DefaultCloudBackupDataSource(api).listBackups("a-access")
                api.downloadMedia("a-access", "media-id")
                api.getAccountInfo("a-access")
                api.refreshAccessToken("a-refresh")
            }

            assertTrue(requestedHosts.isEmpty())
        }

    @Test
    fun `captured access token cannot follow mutable API URL to another server`() =
        runTest {
            val delegate = DefaultLogDateConfigRepository(initialBackendUrl = serverA)
            val config =
                object : LogDateConfigRepository by delegate {
                    override val apiBaseUrl: Flow<String> =
                        flow {
                            delegate.updateBackendUrl(serverB)
                            emit(delegate.getCurrentApiBaseUrl())
                        }
                }
            val requestedHosts = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    requestedHosts += request.url.host
                    respond("", HttpStatusCode.ServiceUnavailable)
                },
            ) {
                install(ContentNegotiation) { json() }
            }.use { http ->
                val api = LogDateCloudApiClient(config, http)
                val sessions =
                    FakeSessionStorage().apply {
                        currentOrigin = serverA
                        saveSession(UserSession("a-access", "a-refresh", "owner-a"))
                    }
                val accounts =
                    DefaultCloudAccountRepository(api, InMemoryKeyValueStorage(), config, backgroundScope, sessionStorage = sessions)

                SyncTokenRefresher(sessions, accounts).withFreshToken(api::getAccountInfo, "getAccountInfo")
            }

            assertTrue(requestedHosts.isNotEmpty())
            assertTrue(requestedHosts.none { it == "b.example" })
        }

    @Test
    fun `refresh after account switch neither sends old refresh token to new server nor overwrites its vault`() =
        runTest {
            val config = DefaultLogDateConfigRepository(initialBackendUrl = serverA)
            val requestedHosts = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    requestedHosts += request.url.host
                    if (request.url.encodedPath.endsWith("/auth/me")) {
                        config.updateBackendUrl(serverB)
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        respond("""{"success":true,"data":{"accessToken":"new-a-access"}}""", HttpStatusCode.OK)
                    }
                },
            ) {
                install(ContentNegotiation) { json() }
            }.use { http ->
                val api = LogDateCloudApiClient(config, http)
                val storage = InMemoryKeyValueStorage()
                storage.values["cloud_access_token_b_example"] = "b-existing"
                val sessions =
                    FakeSessionStorage().apply {
                        currentOrigin = serverA
                        saveSession(UserSession("a-access", "a-refresh", "owner-a"))
                    }
                val accounts = DefaultCloudAccountRepository(api, storage, config, backgroundScope, sessionStorage = sessions)

                SyncTokenRefresher(sessions, accounts).withFreshToken(api::getAccountInfo, "getAccountInfo")

                assertEquals("b-existing", storage.values["cloud_access_token_b_example"])
                assertEquals("a-access", sessions.getSession()?.accessToken)
            }
            assertTrue(requestedHosts.none { it == "b.example" })
        }
}
