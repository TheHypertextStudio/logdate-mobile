package app.logdate.client.sync.v2.continuity

import app.logdate.client.sync.continuity.HandoffFailure
import app.logdate.client.sync.continuity.PlaybackHandoffResult
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LivePlaybackCommand
import app.logdate.client.sync.datalayer.v2.PlaybackCommandType
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearProtocol
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

class PhonePlaybackHandoffHandler(
    private val accepts: suspend (String) -> Boolean,
    private val audience: suspend (String) -> String? = { node -> if (accepts(node)) node else null },
    private val stopRejectedPlayback: suspend () -> Unit = {},
    private val play: suspend (Uuid, Long, Float) -> HandoffFailure?,
) {
    private val mutex = Mutex()

    suspend fun answer(
        node: String,
        bytes: ByteArray,
    ): ByteArray =
        mutex.withLock {
            val decoded = WearJson.decode(bytes, WearProtocol.LIVE_SCHEMA, LivePlaybackCommand.serializer())
            val originalAudience = audience(node)
            val failure =
                if (!accepts(node) || originalAudience == null) {
                    HandoffFailure.PHONE_NEEDS_SETUP
                } else if (decoded !is Decoded.Supported) {
                    HandoffFailure.NOT_ALLOWED
                } else {
                    val command = decoded.value
                    val id = runCatching { Uuid.parse(command.noteId) }.getOrNull()
                    when {
                        id == null || command.command != PlaybackCommandType.CONTINUE_ON_PHONE -> {
                            HandoffFailure.NOT_ALLOWED
                        }

                        (command.positionMs ?: 0) < 0 ||
                            command.speed?.let { !it.isFinite() || it !in 0.5f..3f } == true -> {
                            HandoffFailure.NOT_ALLOWED
                        }

                        else -> {
                            try {
                                play(id, command.positionMs ?: 0, command.speed ?: 1f)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Napier.w("Couldn't continue watch audio on the phone", e)
                                HandoffFailure.PLAYBACK_FAILED
                            }
                        }
                    }
                }
            val verifiedFailure =
                if (failure == null && audience(node) != originalAudience) {
                    stopRejectedPlayback()
                    HandoffFailure.PHONE_NEEDS_SETUP
                } else {
                    failure
                }
            WearJson.encodeToBytes(
                PlaybackHandoffResult.serializer(),
                PlaybackHandoffResult(accepted = verifiedFailure == null, failure = verifiedFailure),
            )
        }
}
