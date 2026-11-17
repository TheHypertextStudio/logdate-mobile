package app.logdate.wear.playback

import app.logdate.client.repository.journals.JournalNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

internal class FakeOutputs : WearAudioOutputs {
    val state = MutableStateFlow<AudioOutputState>(AudioOutputState.SpeakerOnly)
    override val outputState: StateFlow<AudioOutputState> = state
}

internal class FakeResolver : WearSyncedAudioResolver {
    val resolved = mutableListOf<Uuid>()
    var failure: Throwable? = null
    var gate: CompletableDeferred<Result<String>>? = null

    override suspend fun resolvePlayableUri(note: JournalNote.Audio): Result<String> {
        resolved += note.uid
        gate?.let { return it.await() }
        failure?.let { return Result.failure(it) }
        return Result.success("/watch/${note.uid}.m4a")
    }
}

internal class FakeEngine : WearPlaybackEngine {
    val started = mutableListOf<String>()
    val seeks = mutableListOf<Float>()
    var pauses = 0
    var resumes = 0
    var stops = 0
    val suppressed = MutableStateFlow(false)
    private var onProgress: (Float) -> Unit = {}
    private var onCompleted: () -> Unit = {}

    override val suppressedForUnsuitableOutput: StateFlow<Boolean> = suppressed

    override fun start(
        uri: String,
        noteId: Uuid,
        onProgress: (Float) -> Unit,
        onCompleted: () -> Unit,
    ) {
        started += uri
        this.onProgress = onProgress
        this.onCompleted = onCompleted
    }

    override fun pause() {
        pauses++
    }

    override fun resume() {
        resumes++
    }

    override fun stop() {
        stops++
    }

    override fun seekTo(progress: Float) {
        seeks += progress
    }

    fun reportProgress(progress: Float) = onProgress(progress)

    fun complete() = onCompleted()
}
