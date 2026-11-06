package app.logdate.server.routes

import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.auth.TokenService
import app.logdate.server.inMemoryMomentsRepository
import app.logdate.server.logdate.asLogDateBackupRepository
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.logdate.asLogDateMediaBlobRepository
import app.logdate.server.logdate.asLogDateMediaRepository
import app.logdate.server.module
import app.logdate.server.routes.support.DeviceLinkTestKit
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koin.ktor.ext.inject
import studio.hypertext.atproto.repo.Cid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pins which routes may accept a linked device token (`scp=sync`).
 *
 * The routes come from the running server's routing table, not from a hand-written list, so a route added
 * later is swept the day it is registered. A route counts as protected when the OpenAPI document gives
 * it a security requirement, or when it is listed in [HIDDEN_PROTECTED_ROUTES]; a route that is neither
 * documented nor listed fails the sweep until someone classifies it. A protected route must refuse a
 * linked device token or be added to [SYNC_DATA_ROUTES] or [ACCOUNT_DEVICE_ROUTES], which is a deliberate
 * decision about what a watch may do.
 */
@OptIn(ExperimentalUuidApi::class)
class LinkedDeviceRouteScopeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the server refuses a linked device token on every protected route outside the sync data routes`() =
        testApplication {
            lateinit var server: Application
            application {
                module()
                server = this
            }
            val openApi = json.parseToJsonElement(client.request("/openapi.json").bodyAsText()).jsonObject
            val documented = documentedOperations(openApi)
            val tokenService by server.inject<TokenService>()
            val deviceToken = tokenService.generateDeviceAccessToken(Uuid.random().toString(), null, Uuid.random().toString())

            val routes = registeredRoutes(server)
            val unclassified = routes.filter { it.label !in LINKED_DEVICE_ROUTES && classify(it, documented) == null }
            assertTrue(unclassified.isEmpty(), "Classify these routes as public or protected: ${unclassified.map { it.label }}")
            assertEquals(emptySet(), LINKED_DEVICE_ROUTES - routes.map { it.label }.toSet(), "Allowlisted routes that are not registered")

            val protectedRoutes = routes.filter { it.label !in LINKED_DEVICE_ROUTES && classify(it, documented) == RouteAccess.Protected }
            val accepted =
                protectedRoutes.mapNotNull { route ->
                    val response = client.send(route, deviceToken)
                    val body = response.bodyAsText()
                    val refused = response.status in REFUSALS && DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED !in body
                    "${route.label} answered ${response.status}".takeUnless { refused }
                }

            assertTrue(accepted.isEmpty(), "A linked device token reached routes it must not: $accepted")
            assertTrue(KNOWN_PROTECTED_ROUTES.all { known -> protectedRoutes.any { it.label == known } }, "The sweep skipped a known route")
        }

    @Test
    fun `every route a linked device may call asks the session store about its token`() =
        testApplication {
            lateinit var server: Application
            application {
                module()
                server = this
            }
            client.request("/health")
            val tokenService by server.inject<TokenService>()
            val deviceToken = tokenService.generateDeviceAccessToken(Uuid.random().toString(), null, Uuid.random().toString())

            val answers =
                registeredRoutes(server)
                    .filter { it.label in LINKED_DEVICE_ROUTES }
                    .associate { route ->
                        val response = client.send(route, deviceToken)
                        route.label to (response.status to response.bodyAsText().contains(DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED))
                    }

            assertEquals(LINKED_DEVICE_ROUTES.associateWith { HttpStatusCode.Unauthorized to true }, answers)
        }

    @Test
    fun `with an active session only the sync data routes accept a linked device token`() =
        testApplication {
            val tokenService = JwtTokenService("linked-device-route-scope-secret")
            val accounts = InMemoryAccountRepository()
            val kit = DeviceLinkTestKit(tokenService, accounts)
            val repository = InMemorySyncRepository()
            lateinit var server: Application
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") {
                        syncRoutes(
                            tokenService = tokenService,
                            metrics = SyncMetricsRegistry(),
                            collectionsRepository = repository.asLogDateCollectionsRepository(),
                            mediaBlobRepository = repository.asLogDateMediaRepository().asLogDateMediaBlobRepository(),
                            backupRepository = repository.asLogDateBackupRepository(),
                            accessTokenVerifier = kit.verifier,
                            momentsRepository = inMemoryMomentsRepository(accounts),
                        )
                    }
                }
                server = this
            }
            startApplication()
            val watchToken = kit.linkWatch(kit.newAccount("route_scope_owner")).tokens.accessToken

            val answers =
                registeredRoutes(server).associate { route ->
                    val response = client.send(route, watchToken)
                    val scopeDenied = DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED in response.bodyAsText()
                    route.label to (response.status != HttpStatusCode.Unauthorized && !scopeDenied)
                }

            assertEquals(SYNC_DATA_ROUTES - answers.keys, emptySet(), "Allowlisted routes that syncRoutes does not register")
            val accepting = answers.filterValues { it }.keys
            assertEquals(SYNC_DATA_ROUTES, accepting, "Routes that accept a linked device token with an active session")
        }

    private enum class RouteAccess { Public, Protected }

    private data class RegisteredRoute(
        val method: String,
        val template: String,
    ) {
        val label get() = "$method $template"
        val path
            get() =
                if (template == "/xrpc/{method}") {
                    "/xrpc/com.atproto.moderation.createReport"
                } else {
                    template.replace(PATH_PARAMETER, "sweep")
                }
    }

    private fun registeredRoutes(server: Application): List<RegisteredRoute> {
        val found = mutableListOf<RegisteredRoute>()

        fun visit(route: Route) {
            (route.selector as? HttpMethodRouteSelector)?.let {
                found += RegisteredRoute(it.method.value, route.toString().substringBeforeLast("/(method:").ifEmpty { "/" })
            }
            route.children.forEach(::visit)
        }
        visit(server.routing { })
        return found
    }

    /** The routes the OpenAPI document lists, each marked with whether it declares a security requirement. */
    private fun documentedOperations(openApi: JsonObject): Map<String, RouteAccess> =
        openApi
            .getValue("paths")
            .jsonObject
            .flatMap { (path, item) ->
                item.jsonObject.filterKeys { it.uppercase() in HTTP_METHODS }.map { (method, operation) ->
                    val secured = (operation.jsonObject["security"] as? JsonArray)?.isNotEmpty() == true
                    "${method.uppercase()} $path" to if (secured) RouteAccess.Protected else RouteAccess.Public
                }
            }.toMap()

    private fun classify(
        route: RegisteredRoute,
        documented: Map<String, RouteAccess>,
    ): RouteAccess? =
        documented[route.label]
            ?: when (route.label) {
                in HIDDEN_PUBLIC_ROUTES -> RouteAccess.Public
                in HIDDEN_PROTECTED_ROUTES -> RouteAccess.Protected
                else -> null
            }

    private suspend fun HttpClient.send(
        route: RegisteredRoute,
        token: String,
    ): HttpResponse =
        request(route.path + ROUTE_QUERIES[route.label].orEmpty()) {
            method = HttpMethod.parse(route.method)
            header(HttpHeaders.Authorization, "Bearer $token")
            if (method !in BODYLESS_METHODS) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }

    private companion object {
        val PATH_PARAMETER = Regex("\\{[^}]+}")
        val HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        val REFUSALS = setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)
        val BODYLESS_METHODS = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Delete)

        const val SWEEP_DID = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"
        val SWEEP_CID = Cid.rawSha256(byteArrayOf(1)).toString()

        /** Routes that validate their query before they read the token, so the sweep sends the query they need. */
        val ROUTE_QUERIES =
            mapOf(
                "GET /oauth/authorize" to "?request_uri=urn:ietf:params:oauth:request_uri:sweep",
                "GET /xrpc/com.atproto.sync.getRepo" to "?did=$SWEEP_DID",
                "GET /xrpc/com.atproto.sync.getLatestCommit" to "?did=$SWEEP_DID",
                "GET /xrpc/com.atproto.sync.getRepoStatus" to "?did=$SWEEP_DID",
                "GET /xrpc/com.atproto.sync.getBlob" to "?did=$SWEEP_DID&cid=$SWEEP_CID",
                "GET /xrpc/com.atproto.repo.getRecord" to "?repo=$SWEEP_DID&collection=app.bsky.feed.post&rkey=sweep",
                "GET /xrpc/com.atproto.repo.listRecords" to "?repo=$SWEEP_DID&collection=app.bsky.feed.post",
            )

        /** Everything a linked device may call today: sync status and the content, journal, association, draft, media and moment routes. */
        val SYNC_DATA_ROUTES =
            setOf(
                "GET /api/v1/ops/sync/status",
                "PUT /api/v1/contents/{contentId}",
                "GET /api/v1/contents",
                "GET /api/v1/contents/{contentId}",
                "HEAD /api/v1/contents/{contentId}",
                "PATCH /api/v1/contents/{contentId}",
                "DELETE /api/v1/contents/{contentId}",
                "PUT /api/v1/journals/{journalId}",
                "GET /api/v1/journals",
                "GET /api/v1/journals/{journalId}",
                "PATCH /api/v1/journals/{journalId}",
                "DELETE /api/v1/journals/{journalId}",
                "POST /api/v1/associations",
                "GET /api/v1/associations",
                "PUT /api/v1/associations/{journalId}/{contentId}",
                "DELETE /api/v1/associations/{journalId}/{contentId}",
                "DELETE /api/v1/associations",
                "PUT /api/v1/drafts/{draftId}",
                "GET /api/v1/drafts/changes",
                "DELETE /api/v1/drafts/{draftId}",
                "POST /api/v1/media",
                "GET /api/v1/media/receipts/{intent}",
                "GET /api/v1/media/{mediaId}",
                "GET /api/v1/media/{mediaId}/binary",
                "DELETE /api/v1/media/{mediaId}",
                "PUT /api/v1/moments/{momentId}",
                "GET /api/v1/moments",
                "GET /api/v1/moments/{momentId}",
                "PATCH /api/v1/moments/{momentId}",
                "DELETE /api/v1/moments/{momentId}",
            )

        /**
         * Routes outside `syncRoutes` that a linked device may call: read its account, list the linked devices
         * and unlink itself. Every other `/auth` route refuses it.
         */
        val ACCOUNT_DEVICE_ROUTES =
            setOf(
                "GET /api/v1/auth/me",
                "GET /api/v1/auth/devices",
                "DELETE /api/v1/auth/devices/self",
            )

        /** The account's own personal space: a linked watch is one of the owner's own devices. */
        val PERSONAL_SPACE_ROUTES =
            setOf(
                "POST /xrpc/app.logdate.space.putRecord",
                "POST /xrpc/app.logdate.space.deleteRecord",
                "GET /xrpc/app.logdate.space.getRecord",
                "GET /xrpc/app.logdate.space.listRepoOps",
                "POST /xrpc/app.logdate.space.uploadBlob",
                "GET /xrpc/app.logdate.space.getBlob",
                "GET /xrpc/app.logdate.space.getBlobUploadReceipt",
            )

        val LINKED_DEVICE_ROUTES = SYNC_DATA_ROUTES + ACCOUNT_DEVICE_ROUTES + PERSONAL_SPACE_ROUTES

        /** Routes missing from the OpenAPI document that never read an access token. */
        val HIDDEN_PUBLIC_ROUTES =
            setOf(
                "GET /",
                "GET /health",
                "GET /docs",
                "GET /docs/scalar.js",
                "GET /favicon.svg",
                "GET /openapi.json",
                "GET /openapi.yaml",
                "POST /xrpc/com.atproto.server.createAccount",
                "POST /xrpc/com.atproto.server.createSession",
                "OPTIONS /.well-known/oauth-authorization-server/{...}",
                "OPTIONS /.well-known/oauth-protected-resource/{...}",
                "OPTIONS /oauth/jwks/{...}",
                "OPTIONS /oauth/par/{...}",
                "OPTIONS /oauth/token/{...}",
                "OPTIONS /oauth/revoke/{...}",
                "OPTIONS /xrpc/{...}",
            )

        /** Routes missing from the OpenAPI document that need a signed-in account. */
        val HIDDEN_PROTECTED_ROUTES =
            setOf(
                "GET /api/v1/auth/metrics",
                "GET /api/v1/auth/metrics/prometheus",
                "GET /api/v1/ops/sync/metrics",
                "GET /api/v1/ops/sync/metrics/prometheus",
                "POST /api/v1/ops/sync/tombstones:purge",
                "POST /api/v1/ops/backups:purge",
                "GET /xrpc/{method}",
                "POST /xrpc/{method}",
            )

        /** One route per way the server reads an access token, so a mislabelled route cannot slip out of the sweep. */
        val KNOWN_PROTECTED_ROUTES =
            setOf(
                "GET /api/v1/backups",
                "PUT /api/v1/auth/me",
                "POST /api/v1/auth/devices/link",
                "DELETE /api/v1/auth/devices/{id}",
                "GET /api/v1/identity",
                "GET /api/v1/location-history",
                "GET /api/v1/quota",
                "GET /api/v1/diagnostics/reports",
                "GET /api/v1/devices",
                "GET /api/v1/account/key-envelopes/{credentialId}",
                "POST /api/v1/device-enrollments",
                "POST /api/v1/transcription/sessions",
                "POST /api/v1/ops/backups:purge",
                "GET /api/v1/ops/sync/metrics",
                "GET /oauth/authorize",
                "GET /xrpc/com.atproto.server.getSession",
                "GET /xrpc/com.atproto.sync.getRepo",
            )
    }
}
