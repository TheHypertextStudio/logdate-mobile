package app.logdate.wear.v2.continuity

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.continuity.HandoffFailure
import app.logdate.client.sync.continuity.PlaybackHandoffResult
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LivePlaybackCommand
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.PlaybackCommandType
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import app.logdate.wear.v2.playback.MomentPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

class WatchPlaybackReceiver(
    private val peer: WatchDeliveryPeer,
    private val notes: JournalNotesRepository,
    private val player: MomentPlayer,
) {
    private val mutex = Mutex()

    suspend fun answer(
        node: String,
        bytes: ByteArray,
    ): ByteArray =
        mutex.withLock {
            val context = peer.current()
            val decoded = WearJson.decode(bytes, WearProtocol.LIVE_SCHEMA, LivePlaybackCommand.serializer())
            val command = (decoded as? Decoded.Supported)?.value
            val id = command?.noteId?.let(Uuid::parseOrNull)
            val failure =
                when {
                    context == null || context.phoneNode != node -> {
                        HandoffFailure.NOT_ALLOWED
                    }

                    command == null ||
                        id == null ||
                        command.command !in
                        setOf(
                            PlaybackCommandType.CONTINUE_ON_WATCH,
                            PlaybackCommandType.PREPARE_ON_WATCH,
                        )
                    -> {
                        HandoffFailure.NOT_ALLOWED
                    }

                    (command.positionMs ?: 0) < 0 ||
                        command.speed?.let {
                            !it.isFinite() || it !in 0.5f..3f
                        } == true -> {
                        HandoffFailure.NOT_ALLOWED
                    }

                    else -> {
                        val note = awaitNote(id, context)
                        if (command.command == PlaybackCommandType.PREPARE_ON_WATCH) {
                            val failure =
                                when {
                                    !peer.isCurrent(context) -> HandoffFailure.NOT_ALLOWED
                                    note == null -> HandoffFailure.AUDIO_NOT_ON_WATCH
                                    else -> null
                                }
                            return@withLock WearJson.encodeToBytes(
                                PlaybackHandoffResult.serializer(),
                                PlaybackHandoffResult(accepted = failure == null, failure = failure),
                            )
                        }
                        val started = continuePlayback(note, command)
                        when {
                            note == null -> {
                                HandoffFailure.AUDIO_NOT_ON_WATCH
                            }

                            !started -> {
                                HandoffFailure.PLAYBACK_FAILED
                            }

                            !peer.isCurrent(context) -> {
                                withContext(Dispatchers.Main.immediate) { player.stopIfPlaying(id) }
                                HandoffFailure.NOT_ALLOWED
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
            WearJson.encodeToBytes(PlaybackHandoffResult.serializer(), PlaybackHandoffResult(accepted = failure == null, failure = failure))
        }

    private suspend fun continuePlayback(
        note: JournalNote.Audio?,
        command: LivePlaybackCommand,
    ): Boolean =
        if (note == null) {
            false
        } else {
            withContext(Dispatchers.Main.immediate) {
                withTimeoutOrNull(20_000) {
                    player.continuePlayback(note, command.positionMs ?: 0, command.speed ?: 1f)
                } ?: false
            }
        }

    private suspend fun awaitNote(
        id: Uuid,
        context: PeerDeliveryContext,
    ): JournalNote.Audio? =
        withTimeoutOrNull(8_000) {
            var found = notes.getNoteById(id) as? JournalNote.Audio
            while (found == null && peer.isCurrent(context)) {
                delay(200)
                found = notes.getNoteById(id) as? JournalNote.Audio
            }
            found
        }
}
