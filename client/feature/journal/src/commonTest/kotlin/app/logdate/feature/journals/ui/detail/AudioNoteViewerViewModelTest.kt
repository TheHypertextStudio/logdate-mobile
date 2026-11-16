package app.logdate.feature.journals.ui.detail

import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.audio.AudioContextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for [AudioNoteViewerViewModel], which manages the interactive playback and
 * visualization of audio-based journal notes.
 *
 * This suite verifies that audio metadata (like duration) and visualization context
 * (waveforms) are correctly loaded, and that playback controls like toggling and
 * seeking work as expected across various playback states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioNoteViewerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `audio note loads context without subscribing to every note`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Audio(
                    mediaRef = "audio://test",
                    durationMs = 0,
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val durationResolver = FakeAudioDurationResolver(durationMs = 5000L)
            val audioPlaybackManager = FakeAudioPlaybackManager()
            val audioContextProcessor =
                AudioContextProcessor(
                    amplitudeExtractor = FakeAmplitudeExtractor(),
                    waveformStorage = FakeWaveformStorage(),
                    coroutineContext = dispatcher,
                )
            val viewModel =
                AudioNoteViewerViewModel(
                    noteId = noteId,
                    notesRepository = repository,
                    audioContextProcessor = audioContextProcessor,
                    durationResolver = durationResolver,
                    audioPlaybackManager = audioPlaybackManager,
                    getJournalMembership = GetJournalMembershipUseCase(FakeDetailJournalContentRepository()),
                    audioTagRepository = FakeAudioTagRepository(),
                )

            advanceUntilIdle()

            val content = assertIs<AudioNoteViewerUiState.Ready>(viewModel.uiState.value)
            assertEquals("audio://test", content.mediaRef)
            assertEquals(5000L, content.durationMs)
            assertNotNull(content.context)
            assertEquals(0, repository.wholeTableSubscriptions)
        }

    @Test
    fun `toggle playback starts playback`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Audio(
                    mediaRef = "audio://test",
                    durationMs = 1000L,
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val audioPlaybackManager = FakeAudioPlaybackManager()
            val audioContextProcessor =
                AudioContextProcessor(
                    amplitudeExtractor = FakeAmplitudeExtractor(),
                    waveformStorage = FakeWaveformStorage(),
                    coroutineContext = dispatcher,
                )
            val viewModel =
                AudioNoteViewerViewModel(
                    noteId = noteId,
                    notesRepository = repository,
                    audioContextProcessor = audioContextProcessor,
                    durationResolver = FakeAudioDurationResolver(durationMs = 1000L),
                    audioPlaybackManager = audioPlaybackManager,
                    getJournalMembership = GetJournalMembershipUseCase(FakeDetailJournalContentRepository()),
                    audioTagRepository = FakeAudioTagRepository(),
                )

            advanceUntilIdle()

            viewModel.togglePlayback()
            advanceUntilIdle()

            assertEquals(1, audioPlaybackManager.startCalls)
            assertEquals("audio://test", audioPlaybackManager.lastUri)
            assertEquals(noteId, audioPlaybackManager.lastMetadata?.noteId)
        }

    @Test
    fun `seek clamps progress`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Audio(
                    mediaRef = "audio://test",
                    durationMs = 1000L,
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val audioPlaybackManager = FakeAudioPlaybackManager()
            val audioContextProcessor =
                AudioContextProcessor(
                    amplitudeExtractor = FakeAmplitudeExtractor(),
                    waveformStorage = FakeWaveformStorage(),
                    coroutineContext = dispatcher,
                )
            val viewModel =
                AudioNoteViewerViewModel(
                    noteId = noteId,
                    notesRepository = repository,
                    audioContextProcessor = audioContextProcessor,
                    durationResolver = FakeAudioDurationResolver(durationMs = 1000L),
                    audioPlaybackManager = audioPlaybackManager,
                    getJournalMembership = GetJournalMembershipUseCase(FakeDetailJournalContentRepository()),
                    audioTagRepository = FakeAudioTagRepository(),
                )

            advanceUntilIdle()

            viewModel.seekTo(1.5f)
            advanceUntilIdle()

            assertEquals(1f, audioPlaybackManager.lastSeek)
        }

    @Test
    fun `another recording does not appear to play in the opened note`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Audio(
                    mediaRef = "audio://opened",
                    durationMs = 1000L,
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val audioPlaybackManager = FakeAudioPlaybackManager()
            val viewModel =
                AudioNoteViewerViewModel(
                    noteId = noteId,
                    notesRepository = FakeJournalNotesRepository(listOf(note)),
                    audioContextProcessor =
                        AudioContextProcessor(
                            amplitudeExtractor = FakeAmplitudeExtractor(),
                            waveformStorage = FakeWaveformStorage(),
                            coroutineContext = dispatcher,
                        ),
                    durationResolver = FakeAudioDurationResolver(durationMs = 1000L),
                    audioPlaybackManager = audioPlaybackManager,
                    getJournalMembership = GetJournalMembershipUseCase(FakeDetailJournalContentRepository()),
                    audioTagRepository = FakeAudioTagRepository(),
                )
            advanceUntilIdle()

            audioPlaybackManager.updateStatus(
                audioPlaybackManager.playbackStatus.value.copy(
                    isPlaying = true,
                    progress = 0.25f,
                    metadata = AudioPlaybackMetadata(noteId = noteId),
                ),
            )
            advanceUntilIdle()
            assertTrue(assertIs<AudioNoteViewerUiState.Ready>(viewModel.uiState.value).playbackState.isPlaying)

            audioPlaybackManager.updateStatus(
                audioPlaybackManager.playbackStatus.value.copy(
                    isPlaying = true,
                    progress = 0.5f,
                    metadata = AudioPlaybackMetadata(noteId = Uuid.random()),
                ),
            )
            advanceUntilIdle()

            val ready = assertIs<AudioNoteViewerUiState.Ready>(viewModel.uiState.value)
            assertFalse(ready.playbackState.isPlaying)
            assertEquals(0f, ready.playbackState.progress)
        }

    @Test
    fun `playback status updates state`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Audio(
                    mediaRef = "audio://test",
                    durationMs = 1000L,
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val audioPlaybackManager = FakeAudioPlaybackManager()
            val audioContextProcessor =
                AudioContextProcessor(
                    amplitudeExtractor = FakeAmplitudeExtractor(),
                    waveformStorage = FakeWaveformStorage(),
                    coroutineContext = dispatcher,
                )
            val viewModel =
                AudioNoteViewerViewModel(
                    noteId = noteId,
                    notesRepository = repository,
                    audioContextProcessor = audioContextProcessor,
                    durationResolver = FakeAudioDurationResolver(durationMs = 1000L),
                    audioPlaybackManager = audioPlaybackManager,
                    getJournalMembership = GetJournalMembershipUseCase(FakeDetailJournalContentRepository()),
                    audioTagRepository = FakeAudioTagRepository(),
                )

            advanceUntilIdle()

            audioPlaybackManager.updateStatus(
                audioPlaybackManager.playbackStatus.value.copy(
                    isPlaying = true,
                    progress = 0.5f,
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val ready = assertIs<AudioNoteViewerUiState.Ready>(state)
            assertTrue(ready.playbackState.isPlaying)
            assertEquals(0.5f, ready.playbackState.progress)
        }

    @Test
    fun `playback failure surfaces as an error instead of hanging`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Audio(
                    mediaRef = "audio://test",
                    durationMs = 1000L,
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val audioPlaybackManager = FakeAudioPlaybackManager()
            val audioContextProcessor =
                AudioContextProcessor(
                    amplitudeExtractor = FakeAmplitudeExtractor(),
                    waveformStorage = FakeWaveformStorage(),
                    coroutineContext = dispatcher,
                )
            val viewModel =
                AudioNoteViewerViewModel(
                    noteId = noteId,
                    notesRepository = repository,
                    audioContextProcessor = audioContextProcessor,
                    durationResolver = FakeAudioDurationResolver(durationMs = 1000L),
                    audioPlaybackManager = audioPlaybackManager,
                    getJournalMembership = GetJournalMembershipUseCase(FakeDetailJournalContentRepository()),
                    audioTagRepository = FakeAudioTagRepository(),
                )

            advanceUntilIdle()

            audioPlaybackManager.updateStatus(
                audioPlaybackManager.playbackStatus.value.copy(
                    isPlaying = false,
                    errorMessage = "ERROR_CODE_IO_FILE_NOT_FOUND",
                ),
            )
            advanceUntilIdle()

            val ready = assertIs<AudioNoteViewerUiState.Ready>(viewModel.uiState.value)
            assertFalse(ready.playbackState.isPlaying)
            assertEquals("ERROR_CODE_IO_FILE_NOT_FOUND", ready.playbackState.errorMessage)
        }
}
