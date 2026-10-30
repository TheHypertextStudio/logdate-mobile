package app.logdate.server.routes

import app.logdate.server.atproto.firehose.PublicRepoAccess
import app.logdate.server.atproto.firehose.RepoEventStream
import app.logdate.server.logging.RepoEventNotices
import app.logdate.server.routes.docs.XrpcRepoDocs
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import studio.hypertext.atproto.repo.RepoErrorMessage
import studio.hypertext.atproto.repo.RepoEventFrames
import studio.hypertext.atproto.syntax.Tid
import kotlin.time.Duration.Companion.seconds

private const val SUBSCRIBE_REPOS = "/xrpc/com.atproto.sync.subscribeRepos"
private const val LIST_REPOS = "/xrpc/com.atproto.sync.listRepos"
private const val DEFAULT_LIST_LIMIT = 500
private const val MAX_LIST_LIMIT = 1000

/** A follower that takes longer than this to accept one frame is too slow to keep. */
private val SEND_TIMEOUT = 30.seconds

/**
 * The largest frame a follower may send. Followers only read; any message from one closes the
 * connection, and this keeps the server from buffering a large frame before it can do so.
 */
private const val FOLLOWER_MAX_FRAME_BYTES = 1_024L

/**
 * `com.atproto.sync.subscribeRepos` and `com.atproto.sync.listRepos`, the two methods a relay uses
 * to follow a server. Neither needs a sign-in. Both show only the repositories [PublicRepoAccess]
 * shows, and the stream carries only what the event log holds, which the sequencer filled with
 * public repositories only.
 */
internal fun Route.repoEventStreamRoutes(
    stream: RepoEventStream,
    access: PublicRepoAccess,
) {
    webSocket(SUBSCRIBE_REPOS) { follow(stream) }

    get(LIST_REPOS, XrpcRepoDocs.listRepos) {
        val limit =
            call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, MAX_LIST_LIMIT) ?: DEFAULT_LIST_LIMIT
        val cursor =
            call.request.queryParameters["cursor"]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        val page = access.listRepos(cursor, limit)
        call.respond(
            HttpStatusCode.OK,
            ListReposResponse(
                repos =
                    page.repos.map {
                        ListedRepo(
                            did = it.did,
                            head = it.head.commitCid,
                            rev = Tid.fromLong(it.head.revision),
                            active = true,
                        )
                    },
                cursor = page.cursor,
            ),
        )
    }
}

private suspend fun DefaultWebSocketServerSession.follow(stream: RepoEventStream) {
    maxFrameSize = FOLLOWER_MAX_FRAME_BYTES
    val cursorText = call.request.queryParameters["cursor"]
    val cursor = cursorText?.toLongOrNull()?.takeIf { it >= 0 }
    if (cursorText != null && cursor == null) {
        sendError(RepoErrorMessage("InvalidRequest", "cursor must be a non-negative integer"))
        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid cursor"))
        return
    }
    val reservation = stream.reserve()
    if (reservation == null) {
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many followers"))
        return
    }
    reservation.use {
        val sender =
            launch {
                try {
                    stream.frames(cursor).collect { frame -> withTimeout(SEND_TIMEOUT) { send(Frame.Binary(true, frame)) } }
                    close(CloseReason(CloseReason.Codes.NORMAL, "Done"))
                } catch (_: TimeoutCancellationException) {
                    sendError(RepoErrorMessage("ConsumerTooSlow", "The follower stopped reading events"))
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Consumer too slow"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Napier.e(RepoEventNotices.FOLLOWER_FAILED, failure)
                    close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Stream failed"))
                }
            }
        try {
            // Pings and the closing handshake don't arrive here, so anything that does is a message.
            if (incoming.receiveCatching().isSuccess) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "This stream does not accept messages"))
            }
        } finally {
            sender.cancel()
        }
    }
}

private suspend fun DefaultWebSocketServerSession.sendError(error: RepoErrorMessage) {
    runCatching { send(Frame.Binary(true, RepoEventFrames.encode(error))) }
}
