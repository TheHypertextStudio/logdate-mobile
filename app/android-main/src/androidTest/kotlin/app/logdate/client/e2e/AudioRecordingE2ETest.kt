package app.logdate.client.e2e

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.EditorActivity
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.audio.AudioRecordingManager
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.audio.transcription.TranscriptionStartResult
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.permissions.PermissionResult
import app.logdate.client.permissions.PermissionStatus
import app.logdate.client.permissions.PermissionType
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.transcription.TranscriptionData
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// The name of the entry page's choice that starts a recording.
private const val VOICE = "Voice"

/**
 * Instrumented tests for the audio recording experience within the editor.
 *
 * This suite verifies the end-to-end flow of starting, finishing, and
 * persisting an audio recording block, ensuring that the recorded duration
 * is correctly displayed in the editor after capture completes. It uses a
 * [FakeAudioRecordingManager] to simulate recording events without requiring
 * real hardware.
 */
@RunWith(AndroidJUnit4::class)
class AudioRecordingE2ETest {
    // A real recording for the fake recorder to hand over. The editor reads the finished file, so a
    // path with nothing at it leaves the recording being recovered and never shows its length.
    private val recording: File =
        InstrumentationRegistry.getInstrumentation().let { instrumentation ->
            File(instrumentation.targetContext.filesDir, "audio-recording-e2e.m4a").also { file ->
                instrumentation.context.assets.open("recovery/completed-recording.m4a").use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
        }

    private val fakeRecordingManager =
        FakeAudioRecordingManager(
            outputUri = Uri.fromFile(recording).toString(),
            recordingDuration = 1.seconds,
        )

    private val testModule =
        module {
            single<AudioRecordingManager> { fakeRecordingManager }
            single<AudioPlaybackManager> { FakeAudioPlaybackManager() }
            single<TranscriptionRepository> { FakeTranscriptionRepository() }
            single<TranscriptionService> { FakeTranscriptionService() }
            single<PermissionManager> { GrantedPermissionManager() }
            single<EntryDraftRepository> { EmptyEntryDraftRepository() }
        }

    private val koinRule = KoinModuleOverrideRule(testModule)
    private val composeRule = createAndroidComposeRule<EditorActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule).around(composeRule)

    @Test
    fun `record audio shows duration in block`() {
        // The row of ways to add something comes in once the entry has loaded.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription(VOICE).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(VOICE).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("audio_record_start_button").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Finish").fetchSemanticsNodes().isNotEmpty()
        }

        if (composeRule.onAllNodesWithTag("audio_record_start_button").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("audio_record_start_button").performClick()
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("audio_record_start_button").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Finish").assertIsDisplayed()
        composeRule.onNodeWithText("Finish").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("audio_block_duration").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("audio_block_duration").assertTextEquals("00:01")
    }
}

private class KoinModuleOverrideRule(
    private val module: Module,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                startKoinIfAppHasNot(context)
                loadKoinModules(module)
                base.evaluate()
            }
        }
}

private class FakeAudioRecordingManager(
    private val outputUri: String,
    private val recordingDuration: Duration,
) : AudioRecordingManager {
    private val audioLevelFlow = MutableStateFlow(0.4f)
    private val durationFlow = MutableStateFlow(Duration.ZERO)
    private val transcriptionFlow = MutableStateFlow<String?>(null)

    override var isRecording: Boolean = false
        private set

    override suspend fun startRecording(targetNoteId: Uuid?): Boolean {
        isRecording = true
        durationFlow.value = recordingDuration
        return true
    }

    override suspend fun startRecording(
        targetNoteId: Uuid?,
        onTargetReady: suspend (String) -> Boolean,
    ): Boolean {
        if (!onTargetReady(outputUri)) return false
        return startRecording(targetNoteId)
    }

    override suspend fun stopRecording(): String? {
        isRecording = false
        return outputUri
    }

    override fun getAudioLevelFlow(): Flow<Float> = audioLevelFlow

    override fun getRecordingDurationFlow(): Flow<Duration> = durationFlow

    override fun getTranscriptionFlow(): Flow<String?> = transcriptionFlow

    override fun setTranscriptionService(service: TranscriptionService) {
        // No-op for tests.
    }

    override fun release() {
        isRecording = false
    }
}

private class FakeAudioPlaybackManager : AudioPlaybackManager {
    override fun startPlayback(
        uri: String,
        metadata: AudioPlaybackMetadata?,
        onProgressUpdated: (Float) -> Unit,
        onPlaybackCompleted: () -> Unit,
    ) {
        onProgressUpdated(0f)
    }

    override fun pausePlayback() = Unit

    override fun stopPlayback() = Unit

    override fun seekTo(position: Float) = Unit

    override fun release() = Unit
}

private class EmptyEntryDraftRepository : EntryDraftRepository {
    override fun getDrafts(): Flow<List<EntryDraft>> = flowOf(emptyList())

    override fun getDraft(uid: Uuid): Flow<Result<EntryDraft>> = flowOf(Result.failure(NoSuchElementException("Draft not found")))

    override suspend fun createDraft(notes: List<JournalNote>): Uuid = Uuid.random()

    override suspend fun updateDraft(
        uid: Uuid,
        notes: List<JournalNote>,
    ): Uuid = uid

    override suspend fun setPendingMedia(
        uid: Uuid,
        pendingMedia: List<app.logdate.client.repository.journals.PendingMediaRecord>,
    ) = Unit

    override suspend fun deleteDraft(uid: Uuid) = Unit

    override suspend fun deleteAllDrafts() = Unit

    override suspend fun deleteExpiredDrafts(maxAge: Duration): Int = 0
}

private class FakeTranscriptionService : TranscriptionService {
    private val transcriptionFlow = MutableSharedFlow<TranscriptionResult>(replay = 1)

    override fun getTranscriptionFlow(): SharedFlow<TranscriptionResult> = transcriptionFlow

    override suspend fun startLiveTranscription(): TranscriptionStartResult = TranscriptionStartResult.Started

    override suspend fun stopLiveTranscription(): TranscriptionResult = TranscriptionResult.Cancelled

    override suspend fun transcribeAudioFile(audioUri: String): TranscriptionResult = TranscriptionResult.Success("Test transcription")

    override suspend fun cancelTranscription() = Unit

    override fun getSupportedLanguages(): List<String> = emptyList()

    override fun setLanguage(languageCode: String) = Unit

    override val supportsLiveTranscription: Boolean = true

    override val supportsFileTranscription: Boolean = true

    override suspend fun resetTranscription() = Unit

    override fun release() = Unit
}

private class FakeTranscriptionRepository : TranscriptionRepository {
    private val transcriptions = mutableMapOf<Uuid, MutableStateFlow<TranscriptionData?>>()

    override suspend fun requestTranscription(noteId: Uuid): Boolean = true

    override suspend fun getTranscription(noteId: Uuid): TranscriptionData? = transcriptions[noteId]?.value

    override fun observeTranscription(noteId: Uuid): Flow<TranscriptionData?> =
        transcriptions.getOrPut(noteId) { MutableStateFlow(null) }.asStateFlow()

    override suspend fun getPendingTranscriptions(): List<TranscriptionData> = emptyList()

    override suspend fun updateTranscription(
        noteId: Uuid,
        text: String?,
        status: TranscriptionStatus,
        errorMessage: String?,
    ): Boolean {
        val now = Clock.System.now()
        val current = transcriptions.getOrPut(noteId) { MutableStateFlow(null) }
        current.value =
            TranscriptionData(
                noteId = noteId,
                text = text,
                status = status,
                errorMessage = errorMessage,
                created = now,
                lastUpdated = now,
                id = Uuid.random(),
            )
        return true
    }

    override suspend fun deleteTranscription(noteId: Uuid): Boolean {
        transcriptions.remove(noteId)
        return true
    }
}

private class GrantedPermissionManager : PermissionManager {
    private val permissions =
        MutableStateFlow(
            PermissionType.values().associateWith { PermissionStatus.GRANTED },
        )

    override fun isPermissionGranted(type: PermissionType): Boolean = true

    override fun arePermissionsGranted(types: Set<PermissionType>): Boolean = true

    override fun observePermissions(types: Set<PermissionType>): StateFlow<Map<PermissionType, PermissionStatus>> =
        permissions.asStateFlow()

    override fun requestPermission(
        type: PermissionType,
        onResult: (PermissionResult) -> Unit,
    ) {
        onResult(PermissionResult(type, PermissionStatus.GRANTED, shouldShowRationale = false))
    }

    override fun requestPermissions(
        types: Set<PermissionType>,
        onResult: (List<PermissionResult>) -> Unit,
    ) {
        onResult(types.map { PermissionResult(it, PermissionStatus.GRANTED, shouldShowRationale = false) })
    }

    override fun openAppSettings() = Unit

    override fun openPermissionSettings() = Unit

    override fun shouldShowRationale(type: PermissionType): Boolean = false
}
