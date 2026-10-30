package app.logdate.feature.speech.recognition

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTranscriptionAudioTest {
    @Test
    fun `stopping file recognition cancels its decoder and releases the stream`() =
        runBlocking {
            var closed = false
            val accepted = mutableListOf<FloatArray>()
            val decoder =
                flow {
                    try {
                        emit(floatArrayOf(.1f, .4f))
                        awaitCancellation()
                    } finally {
                        closed = true
                    }
                }
            val work = launch(start = CoroutineStart.UNDISPATCHED) { feedFileTranscriptionAudio(decoder, accepted::add) }
            assertEquals(1, accepted.size)
            work.cancelAndJoin()
            assertTrue(closed)
        }

    @Test
    fun `long codec frames are bounded and retain every sample in order`() =
        runBlocking {
            val samples = FloatArray(5000) { it.toFloat() }
            val accepted = mutableListOf<FloatArray>()
            assertTrue(feedFileTranscriptionAudio(flow { emit(samples) }, accepted::add))
            assertTrue(accepted.all { it.size <= 2048 })
            assertEquals(samples.toList(), accepted.flatMap { it.toList() })
        }
}
