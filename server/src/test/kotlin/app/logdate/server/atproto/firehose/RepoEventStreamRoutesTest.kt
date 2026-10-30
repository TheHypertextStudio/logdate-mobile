package app.logdate.server.atproto.firehose

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.RepoCommitEvent
import studio.hypertext.atproto.repo.RepoErrorMessage
import studio.hypertext.atproto.repo.RepoEventFrames
import studio.hypertext.atproto.repo.RepoInfoMessage
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.RepoStreamMessage
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

/**
 * How a relay follows the stream over a real WebSocket: from the present, from a cursor, and when
 * its cursor is wrong.
 */
@OptIn(ExperimentalUuidApi::class)
class RepoEventStreamRoutesTest {
    private val profile = "app.logdate.actor.profile"
    private val subscribe = "/xrpc/com.atproto.sync.subscribeRepos"

    private fun profileRecord(name: String): JsonObject =
        buildJsonObject {
            put("\$type", profile)
            put("displayName", name)
        }

    private fun ApplicationTestBuilder.socketClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun io.ktor.client.plugins.websocket.ClientWebSocketSession.next(): RepoStreamMessage =
        withTimeout(10.seconds) { RepoEventFrames.decode((incoming.receive() as Frame.Binary).data) }

    private suspend fun FirehoseHarness.writeProfile(
        account: app.logdate.server.auth.Account,
        name: String,
    ) {
        DefaultRepoEngine(blocks)
            .putRecord(RepoRecordId(did(account), Nsid.require(profile), RecordKey.require("self")), profileRecord(name))
            .getOrThrow()
    }

    @Test
    fun `a follower without a cursor receives the commits made after it connected`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            h.writeProfile(alice, "before")

            socketClient().webSocket(subscribe) {
                h.waitForFollowers(1)
                kotlinx.coroutines.delay(200)
                h.writeProfile(alice, "after")

                val event = assertIs<RepoCommitEvent>(next())
                assertEquals(2, event.seq)
                assertEquals("app.logdate.actor.profile/self", event.ops.single().path)
            }
        }

    @Test
    fun `a follower that sends cursor 0 receives every event from the start`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            h.writeProfile(alice, "one")
            h.writeProfile(alice, "two")
            h.writeProfile(alice, "three")

            socketClient().webSocket("$subscribe?cursor=0") {
                val seqs = List(3) { assertIs<RepoCommitEvent>(next()).seq }
                assertEquals(listOf(1L, 2L, 3L), seqs)
            }
        }

    @Test
    fun `a follower resumes after the last event it saw and then keeps receiving`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            h.writeProfile(alice, "one")
            h.writeProfile(alice, "two")

            socketClient().webSocket("$subscribe?cursor=1") {
                assertEquals(2L, assertIs<RepoCommitEvent>(next()).seq)
                h.writeProfile(alice, "three")
                assertEquals(3L, assertIs<RepoCommitEvent>(next()).seq)
            }
        }

    @Test
    fun `a cursor ahead of the newest event is answered with a FutureCursor error`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            h.writeProfile(alice, "one")

            socketClient().webSocket("$subscribe?cursor=99") {
                val error = assertIs<RepoErrorMessage>(next())
                assertEquals("FutureCursor", error.error)
            }
        }

    @Test
    fun `a cursor older than the oldest kept event gets an OutdatedCursor notice and then what remains`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }
            val alice = h.createAccount("alice")
            h.writeProfile(alice, "one")
            h.writeProfile(alice, "two")
            h.eventStore.deleteBefore(Clock.System.now() + 1.hours)
            h.writeProfile(alice, "three")

            socketClient().webSocket("$subscribe?cursor=0") {
                val info = assertIs<RepoInfoMessage>(next())
                assertEquals("OutdatedCursor", info.name)
                assertEquals(3L, assertIs<RepoCommitEvent>(next()).seq)
            }
        }

    @Test
    fun `a cursor that is not a number is refused`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }

            socketClient().webSocket("$subscribe?cursor=yesterday") {
                assertEquals("InvalidRequest", assertIs<RepoErrorMessage>(next()).error)
            }
        }

    @Test
    fun `a follower beyond the limit is turned away`() =
        testApplication {
            val h = FirehoseHarness(maxFollowers = 1).also { it.install(this) }
            val client = socketClient()

            client.webSocket(subscribe) {
                client.webSocket(subscribe) {
                    val reason = withTimeout(10.seconds) { closeReason.await() }
                    assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
                }
                close()
            }
        }

    @Test
    fun `the limit is freed when a follower disconnects`() =
        testApplication {
            val h = FirehoseHarness(maxFollowers = 1).also { it.install(this) }
            val client = socketClient()

            client.webSocket(subscribe) { close() }
            h.waitForFollowers(0)

            client.webSocket(subscribe) {
                close()
            }
        }

    @Test
    fun `a follower that sends a message is disconnected`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }

            socketClient().webSocket(subscribe) {
                send(Frame.Text("hello"))
                val reason = withTimeout(10.seconds) { closeReason.await() }
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            }
            h.waitForFollowers(0)
        }

    @Test
    fun `a follower that sends a large frame is disconnected without the server reading it`() =
        testApplication {
            val h = FirehoseHarness().also { it.install(this) }

            socketClient().webSocket(subscribe) {
                send(Frame.Binary(true, ByteArray(64 * 1024)))
                val reason = withTimeout(10.seconds) { closeReason.await() }
                assertEquals(CloseReason.Codes.TOO_BIG.code, reason?.code)
            }
            h.waitForFollowers(0)
        }

    private suspend fun FirehoseHarness.waitForFollowers(count: Int) {
        withTimeout(10.seconds) {
            while (stream.followerCount != count) kotlinx.coroutines.delay(10)
        }
    }
}
