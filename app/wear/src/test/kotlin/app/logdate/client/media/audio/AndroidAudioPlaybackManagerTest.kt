package app.logdate.client.media.audio

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import app.logdate.client.media.storage.AndroidMediaDirectories
import app.logdate.client.media.storage.MediaFileResolver
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Comprehensive tests for [AndroidAudioPlaybackManager], the Media3-based audio engine
 * for playback on Android and Wear OS.
 *
 * This test suite validates the lifecycle of the media session and controller, ensuring
 * accurate progress tracking, responsive playback controls (seek, pause, stop), and the
 * correct handling of hardware-level events such as audio output suppression and
 * completion signals.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAudioPlaybackManagerTest {
    private val context = mockk<Context>()
    private val filesDir = Files.createTempDirectory("logdate-files").toFile()
    private val mediaFiles = MediaFileResolver(AndroidMediaDirectories(filesDir))
    private val controller = mockk<MediaController>()
    private val controllerListener = slot<Player.Listener>()
    private val directExecutor = Executor { runnable -> runnable.run() }
    private val skipSilenceRecorder = RecordingSkipSilence()
    private var speed = 1f

    @BeforeTest
    fun mockSystemClock() {
        // PlaybackException's constructor reads SystemClock.elapsedRealtime() for its
        // timestamp; the plain android.jar stub used by these JVM unit tests throws unless
        // it's mocked.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @AfterTest
    fun unmockSystemClock() {
        unmockkStatic(SystemClock::class)
        filesDir.deleteRecursively()
    }

    @Test
    fun `startPlayback plays a media reference from its recording file`() =
        runTest {
            val itemFactory = RecordingAudioPlaybackItemFactory()
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L), itemFactory = itemFactory)
            every { controller.isPlaying } returns true

            manager.startPlayback(
                uri = "logdate-media://recordings/voice%20note.m4a",
                metadata = null,
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )

            assertEquals("logdate-media://recordings/voice%20note.m4a", manager.playbackStatus.value.currentUri)
            val recording = File(filesDir.canonicalFile, "audio_notes/voice note.m4a")
            assertEquals("file://${recording.toURI().rawPath}", itemFactory.lastUri)
            manager.release()
        }

    @Test
    fun `startPlayback starts service and configures media controller with metadata`() =
        runTest {
            val currentPosition = PlaybackStateHolder(250L)
            val noteId = Uuid.random()
            val serviceStarter = RecordingAudioPlaybackServiceStarter()
            val itemFactory = RecordingAudioPlaybackItemFactory()
            val metadata =
                AudioPlaybackMetadata(
                    title = "Morning Recording",
                    subtitle = "March 31",
                    noteId = noteId,
                )
            val manager =
                createManager(
                    this,
                    currentPosition = currentPosition,
                    itemFactory = itemFactory,
                    serviceStarter = serviceStarter,
                )

            every { controller.isPlaying } returns true

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                metadata = metadata,
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )

            assertEquals(1, serviceStarter.startCount)
            verify(exactly = 1) { controller.setMediaItem(itemFactory.mediaItem) }
            verify(exactly = 1) { controller.prepare() }
            verify(exactly = 1) { controller.play() }
            assertTrue(manager.playbackStatus.value.isPlaying)
            assertEquals(0.25f, manager.playbackStatus.value.progress)
            assertEquals("file:///tmp/voice-note.m4a", manager.playbackStatus.value.currentUri)
            assertEquals(metadata, manager.playbackStatus.value.metadata)
            assertEquals("file:///tmp/voice-note.m4a", itemFactory.lastUri)
            assertEquals(metadata, itemFactory.lastMetadata)
            manager.release()
        }

    @Test
    fun `startPlayback starts playback service before controller playback`() =
        runTest {
            val events = mutableListOf<String>()
            val serviceStarter = AudioPlaybackServiceStarter { events += "service" }
            val itemFactory =
                AudioPlaybackItemFactory { _, _ ->
                    events += "item"
                    mockk(relaxed = true)
                }
            val manager =
                createManager(
                    this,
                    currentPosition = PlaybackStateHolder(0L),
                    itemFactory = itemFactory,
                    serviceStarter = serviceStarter,
                )
            every { controller.play() } answers {
                events += "play"
            }

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )

            assertEquals(listOf("service", "item", "play"), events)
            manager.release()
        }

    @Test
    fun `pause seek and stop delegate to controller and reset progress`() =
        runTest {
            val manager = createManager(this, currentPosition = PlaybackStateHolder(400L))

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )
            manager.pausePlayback()
            manager.seekTo(0.5f)
            manager.stopPlayback()

            verify(exactly = 1) { controller.pause() }
            verify(exactly = 1) { controller.seekTo(500L) }
            verify(exactly = 1) { controller.stop() }
            assertEquals(0f, manager.playbackStatus.value.progress)
            manager.release()
        }

    @Test
    fun `playback progress resumes after focus-style pause and refocus`() =
        runTest {
            val progressUpdates = mutableListOf<Float>()
            val currentPosition = PlaybackStateHolder(100L)
            val dispatcher = StandardTestDispatcher(testScheduler)
            var playing = true
            val manager = createManager(this, dispatcher = dispatcher, currentPosition = currentPosition)

            every { controller.isPlaying } answers { playing }

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = { progressUpdates += it },
                onPlaybackCompleted = {},
            )

            controllerListener.captured.onIsPlayingChanged(true)
            runCurrent()
            currentPosition.value = 300L
            advanceTimeBy(100)
            runCurrent()

            playing = false
            controllerListener.captured.onIsPlayingChanged(false)
            currentPosition.value = 600L
            advanceTimeBy(300)
            runCurrent()

            assertEquals(0.3f, progressUpdates.last())

            playing = true
            controllerListener.captured.onIsPlayingChanged(true)
            runCurrent()
            currentPosition.value = 800L
            advanceTimeBy(100)
            runCurrent()

            assertEquals(0.8f, progressUpdates.last())
            assertTrue(manager.playbackStatus.value.isPlaying)
            manager.release()
        }

    @Test
    fun `playback ended listener reports completion and full progress`() =
        runTest {
            val progressUpdates = mutableListOf<Float>()
            var completedCount = 0
            val currentPosition = PlaybackStateHolder(0L)
            val manager = createManager(this, currentPosition = currentPosition)

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = { progressUpdates += it },
                onPlaybackCompleted = { completedCount += 1 },
            )

            currentPosition.value = 1_000L
            controllerListener.captured.onPlaybackStateChanged(Player.STATE_ENDED)

            assertEquals(1f, progressUpdates.last())
            assertEquals(1, completedCount)
            assertEquals(1f, manager.playbackStatus.value.progress)
            assertFalse(manager.playbackStatus.value.isPlaying)
            manager.release()
        }

    @Test
    fun `player error stops playback, reports completion, and surfaces the failure`() =
        runTest {
            var completedCount = 0
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L))

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = { completedCount += 1 },
            )

            val error =
                PlaybackException(
                    "Source error",
                    null,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                )
            controllerListener.captured.onPlayerError(error)

            assertEquals(1, completedCount)
            assertFalse(manager.playbackStatus.value.isPlaying)
            assertEquals(error.errorCodeName, manager.playbackStatus.value.errorMessage)
            manager.release()
        }

    @Test
    fun `starting playback again clears a previous error`() =
        runTest {
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L))

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )
            controllerListener.captured.onPlayerError(
                PlaybackException("Source error", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
            )
            assertEquals("ERROR_CODE_IO_FILE_NOT_FOUND", manager.playbackStatus.value.errorMessage)

            manager.startPlayback(
                uri = "file:///tmp/voice-note-2.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )

            assertEquals(null, manager.playbackStatus.value.errorMessage)
            manager.release()
        }

    @Test
    fun `unsuitable audio output suppression updates playback status`() =
        runTest {
            val manager = createManager(this, currentPosition = PlaybackStateHolder(100L))

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )

            every {
                controller.playbackSuppressionReason
            } returns Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT

            controllerListener.captured.onPlaybackSuppressionReasonChanged(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
            )

            assertTrue(manager.playbackStatus.value.isSuppressedForUnsuitableOutput)
            manager.release()
        }

    @Test
    fun `isPlaying listener starts progress tracking on injected dispatcher`() =
        runTest {
            val progressUpdates = mutableListOf<Float>()
            val currentPosition = PlaybackStateHolder(200L)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val manager = createManager(this, dispatcher = dispatcher, currentPosition = currentPosition)

            every { controller.isPlaying } returns true

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = { progressUpdates += it },
                onPlaybackCompleted = {},
            )

            controllerListener.captured.onIsPlayingChanged(true)
            runCurrent()
            currentPosition.value = 500L
            advanceTimeBy(100)
            runCurrent()

            assertEquals(0.5f, progressUpdates.last())
            assertEquals(0.5f, manager.playbackStatus.value.progress)
            controllerListener.captured.onIsPlayingChanged(false)
            manager.release()
        }

    @Test
    fun `release removes listener and releases controller`() =
        runTest {
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L))

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )
            manager.release()

            verify(exactly = 1) { controller.removeListener(controllerListener.captured) }
            verify(exactly = 1) { controller.release() }
        }

    @Test
    fun `controller creation failure invokes completion callback`() =
        runTest {
            var completedCount = 0
            val manager =
                createManager(
                    scope = this,
                    currentPosition = PlaybackStateHolder(0L),
                    controllerFutureFactory =
                        MediaControllerFutureFactory {
                            Futures.immediateFailedFuture(IllegalStateException("boom"))
                        },
                )

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                onProgressUpdated = {},
                onPlaybackCompleted = { completedCount += 1 },
            )

            assertEquals(1, completedCount)
        }

    @Test
    fun `playback starts at the position and speed asked for`() =
        runTest {
            val itemFactory = RecordingAudioPlaybackItemFactory()
            val manager = createManager(this, currentPosition = PlaybackStateHolder(250L), itemFactory = itemFactory)

            manager.startPlayback(
                uri = "file:///tmp/voice-note.m4a",
                metadata = null,
                options = AudioPlaybackOptions(startPositionMs = 250L, speed = 1.5f, skipSilence = true),
                onProgressUpdated = {},
                onPlaybackCompleted = {},
            )

            verify(exactly = 1) { controller.setMediaItem(itemFactory.mediaItem, 250L) }
            verify(exactly = 1) { controller.setPlaybackSpeed(1.5f) }
            assertEquals(listOf(true), skipSilenceRecorder.requests)
            val status = manager.playbackStatus.value
            assertEquals(250L, status.positionMs)
            assertEquals(1.5f, status.playbackSpeed)
            assertTrue(status.skipSilenceEnabled)
            manager.release()
        }

    @Test
    fun `plain playback starts at the beginning at normal speed and plays silence`() =
        runTest {
            val itemFactory = RecordingAudioPlaybackItemFactory()
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L), itemFactory = itemFactory)

            manager.startPlayback(uri = "file:///tmp/voice-note.m4a", onProgressUpdated = {}, onPlaybackCompleted = {})

            verify(exactly = 1) { controller.setMediaItem(itemFactory.mediaItem) }
            verify(exactly = 0) { controller.setMediaItem(any(), any<Long>()) }
            verify(exactly = 1) { controller.setPlaybackSpeed(1f) }
            assertEquals(listOf(false), skipSilenceRecorder.requests)
            assertFalse(manager.playbackStatus.value.skipSilenceEnabled)
            manager.release()
        }

    @Test
    fun `seeking to a position stays inside the recording`() =
        runTest {
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L))
            manager.startPlayback(uri = "file:///tmp/voice-note.m4a", onProgressUpdated = {}, onPlaybackCompleted = {})

            manager.seekToPosition(5_000L)
            manager.seekToPosition(-20L)
            manager.seekToPosition(400L)

            verifyOrder {
                controller.seekTo(1_000L)
                controller.seekTo(0L)
                controller.seekTo(400L)
            }
            manager.release()
        }

    @Test
    fun `a new speed applies at once and is reported`() =
        runTest {
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L))
            manager.startPlayback(uri = "file:///tmp/voice-note.m4a", onProgressUpdated = {}, onPlaybackCompleted = {})

            manager.setPlaybackSpeed(2f)

            verify(exactly = 1) { controller.setPlaybackSpeed(2f) }
            assertEquals(2f, manager.playbackStatus.value.playbackSpeed)
            manager.release()
        }

    @Test
    fun `skip silence the session refuses stays off`() =
        runTest {
            skipSilenceRecorder.applies = false
            val manager = createManager(this, currentPosition = PlaybackStateHolder(0L))
            manager.startPlayback(uri = "file:///tmp/voice-note.m4a", onProgressUpdated = {}, onPlaybackCompleted = {})

            manager.setSkipSilenceEnabled(true)

            assertEquals(listOf(false, true), skipSilenceRecorder.requests)
            assertFalse(manager.playbackStatus.value.skipSilenceEnabled)
            manager.release()
        }

    @Test
    fun `progress tracking reports the position in milliseconds`() =
        runTest {
            val currentPosition = PlaybackStateHolder(200L)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val manager = createManager(this, dispatcher = dispatcher, currentPosition = currentPosition)
            every { controller.isPlaying } returns true
            manager.startPlayback(uri = "file:///tmp/voice-note.m4a", onProgressUpdated = {}, onPlaybackCompleted = {})

            controllerListener.captured.onIsPlayingChanged(true)
            runCurrent()
            currentPosition.value = 640L
            advanceTimeBy(100)
            runCurrent()

            assertEquals(640L, manager.playbackStatus.value.positionMs)
            controllerListener.captured.onIsPlayingChanged(false)
            manager.release()
        }

    @Test
    fun `switching before connection plays only the latest track with its identity`() =
        runTest {
            val future = SettableFuture.create<MediaController>()
            val itemFactory = RecordingAudioPlaybackItemFactory()
            val manager =
                createManager(
                    this,
                    currentPosition = PlaybackStateHolder(400L),
                    itemFactory = itemFactory,
                    controllerFutureFactory = MediaControllerFutureFactory { future },
                )
            val first = AudioPlaybackMetadata(noteId = Uuid.random())
            val second = AudioPlaybackMetadata(noteId = Uuid.random())
            var firstFinished = 0
            var secondFinished = 0
            manager.startPlayback("file:///first.m4a", first, {}, { firstFinished++ })
            manager.stopPlayback()
            manager.startPlayback("file:///second.m4a", second, {}, { secondFinished++ })

            future.set(controller)

            assertEquals(second, manager.playbackStatus.value.metadata)
            assertEquals("file:///second.m4a", manager.playbackStatus.value.currentUri)
            verify(exactly = 1) { controller.setMediaItem(any()) }
            controllerListener.captured.onPlaybackStateChanged(Player.STATE_ENDED)
            assertEquals(0, firstFinished)
            assertEquals(1, secondFinished)
            manager.release()
        }

    @Test
    fun `system playback intent changes are reported even while buffering`() =
        runTest {
            val manager = createManager(this, currentPosition = PlaybackStateHolder(400L))
            every { controller.playWhenReady } returns true
            manager.startPlayback("file:///first.m4a", null, {}, {})
            assertTrue(manager.playbackStatus.value.playWhenReady)
            every { controller.playWhenReady } returns false
            controllerListener.captured.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            assertFalse(manager.playbackStatus.value.playWhenReady)
            manager.release()
        }

    private fun createManager(
        scope: TestScope,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
        currentPosition: PlaybackStateHolder,
        itemFactory: AudioPlaybackItemFactory = RecordingAudioPlaybackItemFactory(),
        serviceStarter: AudioPlaybackServiceStarter = RecordingAudioPlaybackServiceStarter(),
        controllerFutureFactory: MediaControllerFutureFactory =
            MediaControllerFutureFactory { Futures.immediateFuture(controller) },
    ): AndroidAudioPlaybackManager {
        stubController(currentPosition)

        return AndroidAudioPlaybackManager(
            context = context,
            coroutineScope = scope,
            progressDispatcher = dispatcher,
            controllerFactory = controllerFutureFactory,
            controllerExecutor = directExecutor,
            mediaItemFactory = itemFactory,
            serviceStarter = serviceStarter,
            mediaFiles = mediaFiles,
            skipSilenceCommand = skipSilenceRecorder,
        )
    }

    private fun stubController(currentPosition: PlaybackStateHolder) {
        every { controller.addListener(capture(controllerListener)) } just runs
        every { controller.removeListener(any<Player.Listener>()) } just runs
        every { controller.setMediaItem(any()) } just runs
        every { controller.setMediaItem(any(), any<Long>()) } just runs
        every { controller.prepare() } just runs
        every { controller.play() } just runs
        every { controller.pause() } just runs
        every { controller.stop() } just runs
        every { controller.release() } just runs
        every { controller.seekTo(any<Long>()) } just runs
        every { controller.setPlaybackSpeed(any()) } answers { speed = firstArg() }
        every { controller.playbackParameters } answers { PlaybackParameters(speed) }
        every { controller.duration } returns 1_000L
        every { controller.currentPosition } answers { currentPosition.value }
        every { controller.isPlaying } returns false
        every { controller.playWhenReady } returns false
        every { controller.playbackSuppressionReason } returns Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }

    private class PlaybackStateHolder(
        var value: Long,
    )

    private class RecordingSkipSilence(
        var applies: Boolean = true,
    ) : SkipSilenceCommand {
        val requests = mutableListOf<Boolean>()

        override fun send(
            controller: MediaController,
            enabled: Boolean,
            onResult: (Boolean) -> Unit,
        ) {
            requests += enabled
            onResult(applies)
        }
    }

    private class RecordingAudioPlaybackItemFactory : AudioPlaybackItemFactory {
        val mediaItem: MediaItem = mockk(relaxed = true)
        var lastUri: String? = null
        var lastMetadata: AudioPlaybackMetadata? = null

        override fun create(
            uri: String,
            metadata: AudioPlaybackMetadata?,
        ): MediaItem {
            lastUri = uri
            lastMetadata = metadata
            return mediaItem
        }
    }

    private class RecordingAudioPlaybackServiceStarter : AudioPlaybackServiceStarter {
        var startCount: Int = 0

        override fun start(context: Context) {
            startCount += 1
        }
    }
}
