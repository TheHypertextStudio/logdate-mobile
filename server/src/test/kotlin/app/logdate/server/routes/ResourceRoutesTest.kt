package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.database.toJavaUUID
import app.logdate.server.logdate.InMemoryResourceRouteRepository
import app.logdate.server.logdate.LogDateEntry
import app.logdate.server.logdate.LogDateJournal
import app.logdate.server.logdate.ResourceKind
import app.logdate.server.logdate.ResourceRoute
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.shared.model.sync.DeviceId
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ResourceRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val ownerId = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
    private val ownerHandle = "williecubed.logdate.app"

    @Test
    fun `resolves journal resource id to owner handle and canonical web path`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount())
            collections.upsertJournal(
                ownerId.toJavaUUID(),
                LogDateJournal(
                    id = "11111111-1111-1111-1111-111111111111",
                    title = "Spring launch",
                    description = "Notes for web launch",
                    createdAt = 1_700_000_000_000,
                    lastUpdated = 1_700_000_000_000,
                    version = 1,
                    deviceId = DeviceId.UNKNOWN,
                ),
            )
            installRoutes(accounts, collections, resourceRoutes)

            val response = client.get("/api/v1/resources/11111111-1111-1111-1111-111111111111")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = json.decodeFromString<ResourceResponse>(response.bodyAsText())
            assertEquals(ownerHandle, body.ownerHandle)
            assertEquals("journal", body.kind)
            assertEquals("/journal/11111111-1111-1111-1111-111111111111", body.canonicalPath)
            assertEquals(
                "https://$ownerHandle/journal/11111111-1111-1111-1111-111111111111",
                body.canonicalUrl,
            )
        }

    @Test
    fun `resolves content resource id as note even when caller does not know owner`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount())
            collections.upsertEntry(
                ownerId.toJavaUUID(),
                LogDateEntry(
                    id = "22222222-2222-2222-2222-222222222222",
                    type = "TEXT",
                    content = "A note",
                    mediaUri = null,
                    durationMs = null,
                    createdAt = 1_700_000_000_000,
                    lastUpdated = 1_700_000_000_000,
                    version = 1,
                    deviceId = DeviceId.UNKNOWN,
                ),
            )
            installRoutes(accounts, collections, resourceRoutes)

            val response = client.get("/api/v1/resources/22222222-2222-2222-2222-222222222222")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = json.decodeFromString<ResourceResponse>(response.bodyAsText())
            assertEquals(ownerHandle, body.ownerHandle)
            assertEquals("note", body.kind)
            assertEquals("/note/22222222-2222-2222-2222-222222222222", body.canonicalPath)
            assertEquals(
                "https://$ownerHandle/note/22222222-2222-2222-2222-222222222222",
                body.canonicalUrl,
            )
        }

    @Test
    fun `resolves registered rewind resource id to owner handle and canonical web path`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount())
            resourceRoutes.upsert(
                ResourceRoute(
                    resourceId = "33333333-3333-3333-3333-333333333333",
                    accountId = ownerId.toJavaUUID(),
                    kind = ResourceKind.REWIND,
                ),
            )
            installRoutes(accounts, collections, resourceRoutes)

            val response = client.get("/api/v1/resources/33333333-3333-3333-3333-333333333333")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = json.decodeFromString<ResourceResponse>(response.bodyAsText())
            assertEquals(ownerHandle, body.ownerHandle)
            assertEquals("rewind", body.kind)
            assertEquals("/rewind/33333333-3333-3333-3333-333333333333", body.canonicalPath)
            assertEquals(
                "https://$ownerHandle/rewind/33333333-3333-3333-3333-333333333333",
                body.canonicalUrl,
            )
        }

    @Test
    fun `registered routes take precedence over collection type guesses`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount())
            collections.upsertJournal(
                ownerId.toJavaUUID(),
                LogDateJournal(
                    id = "66666666-6666-6666-6666-666666666666",
                    title = "Registered as rewind",
                    description = "",
                    createdAt = 1,
                    lastUpdated = 1,
                    version = 1,
                    deviceId = DeviceId.UNKNOWN,
                ),
            )
            resourceRoutes.upsert(
                ResourceRoute(
                    resourceId = "66666666-6666-6666-6666-666666666666",
                    accountId = ownerId.toJavaUUID(),
                    kind = ResourceKind.REWIND,
                ),
            )
            installRoutes(accounts, collections, resourceRoutes)

            val response = client.get("/api/v1/resources/66666666-6666-6666-6666-666666666666")

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val body = json.decodeFromString<ResourceResponse>(response.bodyAsText())
            assertEquals("rewind", body.kind)
            assertEquals("/rewind/66666666-6666-6666-6666-666666666666", body.canonicalPath)
        }

    @Test
    fun `registered resources still require an active owner handle`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount(handle = null))
            resourceRoutes.upsert(
                ResourceRoute(
                    resourceId = "77777777-7777-7777-7777-777777777777",
                    accountId = ownerId.toJavaUUID(),
                    kind = ResourceKind.REWIND,
                ),
            )
            installRoutes(accounts, collections, resourceRoutes)

            val response = client.get("/api/v1/resources/77777777-7777-7777-7777-777777777777")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `deleted registered resources return 404`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount())
            resourceRoutes.upsert(
                ResourceRoute(
                    resourceId = "88888888-8888-8888-8888-888888888888",
                    accountId = ownerId.toJavaUUID(),
                    kind = ResourceKind.REWIND,
                ),
            )
            resourceRoutes.delete("88888888-8888-8888-8888-888888888888")
            installRoutes(accounts, collections, resourceRoutes)

            val response = client.get("/api/v1/resources/88888888-8888-8888-8888-888888888888")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `returns 404 for unknown or deleted resources`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount())
            collections.upsertJournal(
                ownerId.toJavaUUID(),
                LogDateJournal(
                    id = "99999999-9999-9999-9999-999999999999",
                    title = "Deleted",
                    description = "",
                    createdAt = 1,
                    lastUpdated = 1,
                    version = 1,
                    deviceId = DeviceId.UNKNOWN,
                ),
            )
            collections.deleteJournal(ownerId.toJavaUUID(), "99999999-9999-9999-9999-999999999999", 2)
            installRoutes(accounts, collections, resourceRoutes)

            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/resources/99999999-9999-9999-9999-999999999999").status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/resources/44444444-4444-4444-4444-444444444444").status,
            )
        }

    @Test
    fun `does not publish a route for accounts without a handle`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount(handle = null))
            collections.upsertEntry(
                ownerId.toJavaUUID(),
                LogDateEntry(
                    id = "55555555-5555-5555-5555-555555555555",
                    type = "TEXT",
                    content = "Private until handle exists",
                    mediaUri = null,
                    durationMs = null,
                    createdAt = 1,
                    lastUpdated = 1,
                    version = 1,
                    deviceId = DeviceId.UNKNOWN,
                ),
            )
            installRoutes(accounts, collections, resourceRoutes)

            val response = client.get("/api/v1/resources/55555555-5555-5555-5555-555555555555")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    private fun testAccount(handle: String? = ownerHandle): Account =
        Account(
            id = ownerId,
            username = "williecubed",
            displayName = "Willie",
            handle = handle,
            createdAt = Clock.System.now(),
        )

    @Test
    fun `lookups that search every account are budgeted per address and registered ones are not`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            val collections = InMemorySyncRepository().asLogDateCollectionsRepository()
            val resourceRoutes = InMemoryResourceRouteRepository()
            accounts.save(testAccount())
            resourceRoutes.upsert(ResourceRoute("registered-journal", ownerId.toJavaUUID(), ResourceKind.JOURNAL))
            installRoutes(accounts, collections, resourceRoutes)

            repeat(30) { assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/resources/missing-$it").status) }
            val limited = client.get("/api/v1/resources/missing-31")

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/resources/registered-journal").status)
        }

    @Test
    fun `an overlong resource id is not looked up`() =
        testApplication {
            val accounts = InMemoryAccountRepository()
            accounts.save(testAccount())
            installRoutes(accounts, InMemorySyncRepository().asLogDateCollectionsRepository(), InMemoryResourceRouteRepository())

            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/resources/${"a".repeat(129)}").status)
        }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installRoutes(
        accounts: InMemoryAccountRepository,
        collections: app.logdate.server.logdate.LogDateCollectionsRepository,
        resourceRoutes: InMemoryResourceRouteRepository,
    ) {
        application {
            install(ContentNegotiation) {
                json(json)
            }
            routing {
                route("/api/v1") {
                    resourceRoutes(
                        accountRepository = accounts,
                        collectionsRepository = collections,
                        resourceRouteRepository = resourceRoutes,
                    )
                }
            }
        }
    }
}
