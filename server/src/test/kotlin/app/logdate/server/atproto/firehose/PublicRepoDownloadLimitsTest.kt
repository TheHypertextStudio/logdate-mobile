package app.logdate.server.atproto.firehose

import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.routes.PublicRepoDownloadLimits
import app.logdate.server.routes.PublicRepoReads
import app.logdate.server.transfer.TransferLimits
import app.logdate.server.transfer.TransferSlots
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Pins the limits on the downloads anyone may make from a public repository with no sign-in: each
 * network address has a per-minute budget, and an instance builds only a few downloads at once.
 */
@OptIn(ExperimentalUuidApi::class)
class PublicRepoDownloadLimitsTest {
    private val profile = "app.logdate.actor.profile"

    private fun profileRecord() =
        buildJsonObject {
            put("\$type", profile)
            put("displayName", "Alice")
        }

    private fun ApplicationTestBuilder.installLimitedGetRepo(reads: PublicRepoReads) {
        application {
            routing {
                get("/limited/getRepo/{did}") {
                    reads.tryGetRepo(call, AtprotoDid.require(checkNotNull(call.parameters["did"])), since = null)
                }
            }
        }
    }

    @Test
    fun `an address over its repository export budget is answered with 429`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val reads =
                PublicRepoReads(
                    h.access,
                    h.identity,
                    blobService = null,
                    downloads = PublicRepoDownloadLimits(repositories = RateLimitPolicy(maxRequests = 2, windowSeconds = 60)),
                )
            installLimitedGetRepo(reads)
            val alice = h.createAccount("alice")
            h.put(client, alice, profile, "self", profileRecord())

            repeat(2) { assertEquals(HttpStatusCode.OK, client.get("/limited/getRepo/${alice.did}").status) }
            val limited = client.get("/limited/getRepo/${alice.did}")

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
            assertTrue("RateLimitExceeded" in limited.bodyAsText())
        }

    @Test
    fun `a download is refused while every slot is taken and served once one frees`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val slots = TransferSlots(TransferLimits(downloads = 1))
            installLimitedGetRepo(
                PublicRepoReads(h.access, h.identity, blobService = null, downloads = PublicRepoDownloadLimits(transfers = slots)),
            )
            val alice = h.createAccount("alice")
            h.put(client, alice, profile, "self", profileRecord())

            val held = checkNotNull(slots.tryDownload())
            val busy = client.get("/limited/getRepo/${alice.did}")
            assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
            assertTrue("ServerBusy" in busy.bodyAsText())

            held.release()
            assertEquals(HttpStatusCode.OK, client.get("/limited/getRepo/${alice.did}").status)
            assertNotNull(slots.tryDownload(), "the download gave its slot back")
        }
}
