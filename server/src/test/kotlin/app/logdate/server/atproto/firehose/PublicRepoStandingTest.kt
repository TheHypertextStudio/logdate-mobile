package app.logdate.server.atproto.firehose

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.auth.AccountModeration
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBackupRepository
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.RepoAccountEvent
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.ExperimentalUuidApi

/**
 * What `com.atproto.sync.getRepoStatus` and the other sync methods say about an account that is not
 * active, the way a reference PDS says it: `active: false` with a status, to anyone, with no sign-in.
 */
@OptIn(ExperimentalUuidApi::class)
class PublicRepoStandingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val profile = "app.logdate.actor.profile"

    private fun profileRecord(): JsonObject =
        buildJsonObject {
            put("\$type", profile)
            put("displayName", "Alice")
        }

    private suspend fun HttpResponse.parsed(): JsonObject = json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun ApplicationTestBuilder.status(account: Account): HttpResponse =
        client.get("/xrpc/com.atproto.sync.getRepoStatus?did=${account.did}")

    private suspend fun ApplicationTestBuilder.published(h: FirehoseHarness): Account {
        val alice = h.createAccount("alice")
        h.put(client, alice, profile, "self", profileRecord())
        return alice
    }

    @Test
    fun `an active account reports active with its revision`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)

            val body = status(alice).parsed()

            assertEquals("true", body.getValue("active").jsonPrimitive.content)
            assertTrue("status" !in body)
            assertTrue("rev" in body)
        }

    @Test
    fun `a deactivated account reports inactive and deactivated without a sign-in`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.accounts.deactivateAccount(alice.id)

            val response = status(alice)
            val body = response.parsed()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("false", body.getValue("active").jsonPrimitive.content)
            assertEquals("deactivated", body.getValue("status").jsonPrimitive.content)
            assertTrue("rev" in body, "a deactivated repository keeps its revision")
        }

    @Test
    fun `a suspended account reports inactive and suspended`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.accounts.setModeration(alice.id, AccountModeration.SUSPENDED)

            val body = status(alice).parsed()

            assertEquals("false", body.getValue("active").jsonPrimitive.content)
            assertEquals("suspended", body.getValue("status").jsonPrimitive.content)
        }

    @Test
    fun `a taken down account reports inactive and takendown`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.accounts.setModeration(alice.id, AccountModeration.TAKENDOWN)

            val body = status(alice).parsed()

            assertEquals("false", body.getValue("active").jsonPrimitive.content)
            assertEquals("takendown", body.getValue("status").jsonPrimitive.content)
        }

    @Test
    fun `a deleted account that relays heard of reports inactive and deleted with no revision`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.announcer.identityChanged(alice)
            h.accounts.deleteAccount(alice.id)

            val body = status(alice).parsed()

            assertEquals("false", body.getValue("active").jsonPrimitive.content)
            assertEquals("deleted", body.getValue("status").jsonPrimitive.content)
            assertTrue("rev" !in body)
        }

    @Test
    fun `a deletion more than a week after the announcement reaches followers and reports deleted`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.announcer.identityChanged(alice)
            h.eventStore.deleteBefore(Clock.System.now() + 1.hours)
            AccountDeletionService(
                accountRepository = h.accounts,
                mediaBlobRepository =
                    CompositeLogDateMediaBlobRepository(
                        InMemoryLogDateMediaRepository(),
                        InMemoryLogDateAtprotoBlobRepository(),
                    ),
                backupRepository = InMemoryLogDateBackupRepository(),
                blobStorage = h.blobStorage,
                statusEvents = h.announcer,
            ).deleteAccount(alice.id)

            val event =
                assertIs<RepoAccountEvent>(
                    h.eventStore
                        .read(0, 100)
                        .single()
                        .decode(),
                )
            val body = status(alice).parsed()

            assertEquals("deleted", event.status)
            assertEquals("deleted", body.getValue("status").jsonPrimitive.content)
            assertEquals("false", body.getValue("active").jsonPrimitive.content)
        }

    @Test
    fun `an account announced before it wrote anything reports active without a revision`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            h.announcer.identityChanged(alice)

            val response = status(alice)
            val body = response.parsed()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("true", body.getValue("active").jsonPrimitive.content)
            assertTrue("rev" !in body)
        }

    @Test
    fun `a repository still holding private records reveals no head or revision`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            val other = h.createAccount("other")
            h.put(client, other, profile, "self", profileRecord())
            DefaultRepoEngine(h.rawBlocks)
                .putRecord(
                    RepoRecordId(h.did(alice), Nsid.require("studio.hypertext.logdate.content"), RecordKey.require("entry-1")),
                    buildJsonObject { put("content", "diary") },
                ).getOrThrow()
            val head = h.rawBlocks.readHead(h.did(alice)).getOrThrow()!!

            val latest = client.get("/xrpc/com.atproto.sync.getLatestCommit?did=${alice.did}")
            val status = status(alice)
            val repos = client.get("/xrpc/com.atproto.sync.listRepos").bodyAsText()

            assertEquals(HttpStatusCode.Unauthorized, latest.status)
            assertEquals(HttpStatusCode.OK, status.status)
            assertTrue("rev" !in status.parsed(), "the revision is a write timestamp")
            assertFalse(head.commitCid.toString() in repos + latest.bodyAsText() + status.bodyAsText())
            assertFalse(alice.did!! in repos, "an account whose tree holds private records is not listed")
            assertTrue(other.did!! in repos)
        }

    @Test
    fun `a moderated account whose tree holds private records reports no revision either`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            DefaultRepoEngine(h.rawBlocks)
                .putRecord(
                    RepoRecordId(h.did(alice), Nsid.require("studio.hypertext.logdate.content"), RecordKey.require("entry-1")),
                    buildJsonObject { put("content", "diary") },
                ).getOrThrow()
            h.accounts.setModeration(alice.id, AccountModeration.SUSPENDED)

            val body = status(alice).parsed()

            assertEquals("suspended", body.getValue("status").jsonPrimitive.content)
            assertTrue("rev" !in body)
        }

    @Test
    fun `restoring a moderated account makes it active again`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.accounts.setModeration(alice.id, AccountModeration.TAKENDOWN)
            h.accounts.setModeration(alice.id, null)

            val body = status(alice).parsed()

            assertEquals("true", body.getValue("active").jsonPrimitive.content)
            assertTrue("status" !in body)
        }

    @Test
    fun `a DID this server never announced and an account on legacy storage reveal nothing`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val legacy = h.createAccount("legacy", personalSpace = false)
            h.accounts.deactivateAccount(legacy.id)
            val gone = h.createAccount("gone")
            h.accounts.deleteAccount(gone.id)

            assertEquals(HttpStatusCode.Unauthorized, status(legacy).status)
            assertEquals(HttpStatusCode.Unauthorized, status(gone).status)
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/xrpc/com.atproto.sync.getRepoStatus?did=did:web:nobody.logdate.app").status,
            )
        }

    @Test
    fun `the methods that need the repository name the reason it is not available`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val deactivated = published(h)
            val suspended = h.createAccount("suspended").also { h.put(client, it, profile, "self", profileRecord()) }
            val takenDown = h.createAccount("takendown").also { h.put(client, it, profile, "self", profileRecord()) }
            val deleted = h.createAccount("deleted").also { h.put(client, it, profile, "self", profileRecord()) }
            h.accounts.deactivateAccount(deactivated.id)
            h.accounts.setModeration(suspended.id, AccountModeration.SUSPENDED)
            h.accounts.setModeration(takenDown.id, AccountModeration.TAKENDOWN)
            h.announcer.identityChanged(deleted)
            h.accounts.deleteAccount(deleted.id)

            val expected =
                mapOf(
                    deactivated to ("RepoDeactivated" to HttpStatusCode.BadRequest),
                    suspended to ("RepoSuspended" to HttpStatusCode.BadRequest),
                    takenDown to ("RepoTakendown" to HttpStatusCode.BadRequest),
                    deleted to ("RepoNotFound" to HttpStatusCode.NotFound),
                )
            expected.forEach { (account, outcome) ->
                listOf("getRepo", "getLatestCommit").forEach { method ->
                    val response = client.get("/xrpc/com.atproto.sync.$method?did=${account.did}")
                    assertEquals(outcome.second, response.status, "$method for ${account.username}")
                    assertEquals(
                        outcome.first,
                        response
                            .parsed()
                            .getValue("error")
                            .jsonPrimitive.content,
                        "$method for ${account.username}",
                    )
                }
            }
        }

    @Test
    fun `a moderated account is no longer shown anywhere else`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.accounts.setModeration(alice.id, AccountModeration.TAKENDOWN)

            val record = client.get("/xrpc/com.atproto.repo.getRecord?repo=${alice.did}&collection=$profile&rkey=self")
            val list = client.get("/xrpc/com.atproto.repo.listRecords?repo=${alice.did}&collection=$profile")
            val repos =
                client
                    .get("/xrpc/com.atproto.sync.listRepos")
                    .parsed()
                    .getValue("repos")
                    .jsonArray

            assertEquals(HttpStatusCode.Unauthorized, record.status)
            assertEquals(HttpStatusCode.Unauthorized, list.status)
            assertTrue(repos.isEmpty())
        }

    @Test
    fun `a moderated account announces nothing more while it is moderated`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.accounts.setModeration(alice.id, AccountModeration.SUSPENDED)
            val before = h.eventStore.read(0, 100).size

            h.put(client, alice, profile, "self", profileRecord())

            assertEquals(before, h.eventStore.read(0, 100).size)
            assertFalse(h.gate.mayShow(h.did(alice)))
            assertEquals(AccountModeration.SUSPENDED, h.accounts.findById(alice.id)?.moderation)
        }

    @Test
    fun `saving an account never changes its moderation`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = published(h)
            h.accounts.setModeration(alice.id, AccountModeration.TAKENDOWN)

            h.accounts.save(alice.copy(displayName = "Alice again"))

            assertEquals(AccountModeration.TAKENDOWN, h.accounts.findById(alice.id)?.moderation)
        }
}
