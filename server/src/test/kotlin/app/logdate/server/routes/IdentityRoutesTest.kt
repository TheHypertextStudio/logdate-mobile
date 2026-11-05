package app.logdate.server.routes

import app.logdate.server.AuthV1TestEnvironment
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.configureAuthV1TestApp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests the identity resolution and metadata endpoints for AT Protocol compatibility.
 *
 * This suite validates the server's ability to resolve DIDs via `.well-known` paths for
 * both PLC and Web methods. It also covers XRPC endpoints for handle resolution and
 * repository/server descriptions, ensuring that the server correctly identifies itself
 * and its users to the broader AT Protocol network.
 */
@OptIn(ExperimentalUuidApi::class)
class IdentityRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `well known identity routes resolve host mapped account`() =
        testApplication {
            val env = configureAuthV1TestApp()
            // This fixture represents an identity its owner has explicitly made public.
            runBlocking { env.publicAccount("alice") }

            val didResponse =
                client.get("/.well-known/atproto-did") {
                    header(HttpHeaders.Host, "alice.logdate.app")
                }
            assertEquals(HttpStatusCode.OK, didResponse.status)
            assertTrue(didResponse.bodyAsText().startsWith("did:plc:"))

            val didDocumentResponse =
                client.get("/.well-known/did.json") {
                    header(HttpHeaders.Host, "alice.logdate.app")
                }
            assertEquals(HttpStatusCode.NotFound, didDocumentResponse.status)
        }

    @Test
    fun `well known did document serves did web account identities`() =
        testApplication {
            val env =
                configureAuthV1TestApp(
                    atprotoIdentityConfig =
                        app.logdate.server.identity.AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://pds.logdate.app",
                            hostedAccountDidMethod = app.logdate.server.identity.HostedAccountDidMethod.WEB,
                        ),
                )
            runBlocking {
                env.accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "didweb",
                        displayName = "Did Web",
                        createdAt = Clock.System.now(),
                        handle = "didweb.logdate.app",
                        did = "did:web:didweb.logdate.app",
                        publicIdentityDid = "did:web:didweb.logdate.app",
                        signingKeyPublic = "zDidWebKey",
                    ),
                )
            }

            val didDocumentResponse =
                client.get("/.well-known/did.json") {
                    header(HttpHeaders.Host, "didweb.logdate.app")
                }

            assertEquals(HttpStatusCode.OK, didDocumentResponse.status)
            val payload = json.parseToJsonElement(didDocumentResponse.bodyAsText()).jsonObject
            assertEquals("did:web:didweb.logdate.app", payload["id"]?.jsonPrimitive?.content)
        }

    @Test
    fun `handle resolution follows a changed handle and stops after account deletion`() =
        testApplication {
            val env = configureAuthV1TestApp()
            val account = runBlocking { env.publicAccount("dana") }
            val did = requireNotNull(account.did)

            suspend fun wellKnownDid(host: String) =
                client.get("/.well-known/atproto-did") {
                    header(HttpHeaders.Host, host)
                }

            suspend fun xrpcDid(handle: String) = client.get("/xrpc/com.atproto.identity.resolveHandle?handle=$handle")

            assertEquals(did, wellKnownDid("dana.logdate.app").bodyAsText())

            runBlocking {
                assertNotNull(
                    env.accountRepository.updateIdentityIfUnchanged(account, account.copy(handle = "dana-renamed.logdate.app")),
                )
            }

            assertEquals(HttpStatusCode.NotFound, wellKnownDid("dana.logdate.app").status)
            assertTrue(xrpcDid("dana.logdate.app").bodyAsText().contains("HandleNotFound"))
            assertEquals(did, wellKnownDid("dana-renamed.logdate.app").bodyAsText())
            assertTrue(xrpcDid("dana-renamed.logdate.app").bodyAsText().contains(did))

            runBlocking { env.accountRepository.deleteAccount(account.id) }

            assertEquals(HttpStatusCode.NotFound, wellKnownDid("dana-renamed.logdate.app").status)
            assertTrue(xrpcDid("dana-renamed.logdate.app").bodyAsText().contains("HandleNotFound"))
        }

    @Test
    fun `xrpc resolveHandle returns expected success and errors`() =
        testApplication {
            val env = configureAuthV1TestApp()
            // This fixture represents an identity its owner has explicitly made public.
            runBlocking { env.publicAccount("brie") }

            val success = client.get("/xrpc/com.atproto.identity.resolveHandle?handle=brie.logdate.app")
            assertEquals(HttpStatusCode.OK, success.status)
            val successPayload = json.parseToJsonElement(success.bodyAsText()).jsonObject
            assertTrue(successPayload["did"]?.jsonPrimitive?.content?.startsWith("did:plc:") == true)

            val missingHandle = client.get("/xrpc/com.atproto.identity.resolveHandle")
            assertEquals(HttpStatusCode.BadRequest, missingHandle.status)
            assertTrue(missingHandle.bodyAsText().contains("InvalidRequest"))

            val missingAccount = client.get("/xrpc/com.atproto.identity.resolveHandle?handle=missing.logdate.app")
            assertEquals(HttpStatusCode.BadRequest, missingAccount.status)
            assertTrue(missingAccount.bodyAsText().contains("HandleNotFound"))
        }

    @Test
    fun `xrpc describeServer and describeRepo expose identity metadata`() =
        testApplication {
            val env = configureAuthV1TestApp()
            // This fixture represents an identity its owner has explicitly made public.
            runBlocking { env.publicAccount("cora") }
            env.atprotoIdentityService.setRepoCollectionsResolver {
                listOf(
                    studio.hypertext.atproto.syntax.Nsid
                        .require("studio.hypertext.logdate.content"),
                )
            }

            val describeServer = client.get("/xrpc/com.atproto.server.describeServer")
            assertEquals(HttpStatusCode.OK, describeServer.status)
            val serverPayload = json.parseToJsonElement(describeServer.bodyAsText()).jsonObject
            assertEquals("did:web:logdate.app", serverPayload["did"]?.jsonPrimitive?.content)
            assertEquals(
                "logdate.app",
                serverPayload["availableUserDomains"]
                    ?.jsonArray
                    ?.single()
                    ?.jsonPrimitive
                    ?.content,
            )

            val describeRepo = client.get("/xrpc/com.atproto.repo.describeRepo?repo=cora.logdate.app")
            assertEquals(HttpStatusCode.OK, describeRepo.status)
            val repoPayload = json.parseToJsonElement(describeRepo.bodyAsText()).jsonObject
            assertEquals("cora.logdate.app", repoPayload["handle"]?.jsonPrimitive?.content)
            assertEquals(0, repoPayload["collections"]?.jsonArray?.size)
            assertTrue(repoPayload["did"]?.jsonPrimitive?.content?.startsWith("did:plc:") == true)
        }

    @Test
    fun `anonymous lookups never create an identity for an account without one`() =
        testApplication {
            val env = configureAuthV1TestApp()
            val legacy =
                runBlocking {
                    env.accountRepository.save(
                        Account(id = Uuid.random(), username = "legacy", displayName = "Legacy", createdAt = Clock.System.now()),
                    )
                }

            val wellKnown =
                client.get("/.well-known/atproto-did") {
                    header(HttpHeaders.Host, "legacy.logdate.app")
                }
            val xrpc = client.get("/xrpc/com.atproto.identity.resolveHandle?handle=legacy.logdate.app")
            val describeRepo = client.get("/xrpc/com.atproto.repo.describeRepo?repo=legacy.logdate.app")

            assertEquals(HttpStatusCode.NotFound, wellKnown.status)
            assertTrue(xrpc.bodyAsText().contains("HandleNotFound"))
            assertTrue(describeRepo.status != HttpStatusCode.OK)
            assertEquals(legacy, runBlocking { env.accountRepository.findById(legacy.id) })
        }

    @Test
    fun `a failing account lookup is an outage and never a missing handle`() =
        testApplication {
            val accountRepository = mockk<AccountRepository>(relaxed = true)
            coEvery { accountRepository.findByHandle(any()) } throws IllegalStateException("database unavailable")
            configureAuthV1TestApp(accountRepository = accountRepository)

            val xrpc = client.get("/xrpc/com.atproto.identity.resolveHandle?handle=alice.logdate.app")
            val wellKnown =
                client.get("/.well-known/atproto-did") {
                    header(HttpHeaders.Host, "alice.logdate.app")
                }

            assertEquals(HttpStatusCode.InternalServerError, xrpc.status)
            assertTrue(xrpc.bodyAsText().contains("InternalServerError"))
            assertEquals(HttpStatusCode.InternalServerError, wellKnown.status)
        }

    private suspend fun AuthV1TestEnvironment.publicAccount(username: String): Account =
        accountRepository.save(
            Account(
                id = Uuid.random(),
                username = username,
                displayName = username,
                createdAt = Clock.System.now(),
                handle = "$username.logdate.app",
                did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
                signingKeyPublic = "zPublicFixtureKey",
                publicIdentityDid = "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
            ),
        )
}
