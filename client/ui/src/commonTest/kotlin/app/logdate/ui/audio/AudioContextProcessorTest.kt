package app.logdate.ui.audio

import app.logdate.ui.audio.WaveformStorage
import app.logdate.ui.audio.extraction.AmplitudeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Locks in the progressive waveform contract — the UI for long voice notes paints
 * an empty waveform first, fills in as decode advances, and the final state is
 * cached so a subsequent open is one-shot.
 */
class AudioContextProcessorTest {
    @Test
    fun `cache migration failure keeps the waveform already read`() =
        runTest {
            val uri = "https://example.test/cached.m4a"
            val samples = listOf(.2f, .6f)
            for (failSave in listOf(true, false)) {
                val backing = InMemoryWaveformStorage().apply { save(uri, samples) }
                val storage =
                    object : WaveformStorage by backing {
                        override suspend fun save(
                            audioUri: String,
                            amplitudes: List<Float>,
                        ) {
                            if (failSave) error("Cache full")
                            backing.save(audioUri, amplitudes)
                        }

                        override suspend fun delete(audioUri: String) {
                            error("Cache temporarily unavailable")
                        }
                    }
                val processor = AudioContextProcessor(FailingExtractor(), storage, coroutineContext = Dispatchers.Unconfined)
                val contexts = processor.processProgressively(uri, 1000, Clock.System.now(), null, null).toList()
                assertEquals(samples, contexts.single().amplitudes)
            }
        }

    @Test
    fun `existing cached recordings migrate without decoding their audio again`() =
        runTest {
            val uri = "fake://existing"
            val cached = listOf(.1f, .5f)
            val storage = InMemoryWaveformStorage().apply { save(uri, cached) }
            val processor = AudioContextProcessor(FailingExtractor(), storage, coroutineContext = Dispatchers.Unconfined)
            val contexts = processor.processProgressively(uri, 1000, Clock.System.now(), null, null).toList()
            assertEquals(cached, contexts.single().amplitudes)
            assertEquals(cached, storage.load(waveformStorageKey(uri, 1000)))
            assertNull(storage.load(uri))
        }

    @Test
    fun `passive waveform presentation never downloads remote audio`() =
        runTest {
            val processor = AudioContextProcessor(FailingExtractor(), InMemoryWaveformStorage(), coroutineContext = Dispatchers.Unconfined)
            val uri = "https://example.test/original.m4a"
            val contexts = processor.processProgressively(uri, 1000, Clock.System.now(), null, null).toList()
            assertTrue(contexts.single().amplitudes.isEmpty())
            assertTrue(processor.process(uri, 1000, Clock.System.now(), null, null).amplitudes.isEmpty())
        }

    @Test
    fun `replacement media at the same URI does not reuse the former disk waveform`() =
        runTest {
            var calls = 0
            val extractor =
                object : AmplitudeExtractor {
                    override suspend fun extractAmplitudes(
                        uri: String,
                        targetSampleCount: Int,
                    ): List<Float> {
                        calls++
                        return listOf(calls / 10f)
                    }
                }
            val processor = AudioContextProcessor(extractor, InMemoryWaveformStorage(), coroutineContext = Dispatchers.Unconfined)
            processor.processProgressively("fake://replaced", 1000, Clock.System.now(), null, null).toList()
            val replacement = processor.processProgressively("fake://replaced", 2000, Clock.System.now(), null, null).toList()
            assertEquals(2, calls)
            assertEquals(listOf(.2f), replacement.last().amplitudes)
        }

    @Test
    fun `a damaged derived cache is regenerated from the original audio`() =
        runTest {
            val samples = listOf(.2f, .7f)
            val storage =
                object : WaveformStorage by InMemoryWaveformStorage() {
                    override suspend fun load(audioUri: String): List<Float>? = error("Truncated waveform cache")
                }
            val processor =
                AudioContextProcessor(
                    FakeProgressiveExtractor(emptyList(), samples),
                    storage,
                    coroutineContext = Dispatchers.Unconfined,
                )
            val contexts = processor.processProgressively("fake://damaged", 1000, Clock.System.now(), null, null).toList()
            assertEquals(samples, contexts.last().amplitudes)
        }

    @Test
    fun `remounted audio immediately reuses complete samples for the same media revision`() =
        runTest {
            val samples = listOf(.1f, .5f, .8f)
            val storage = InMemoryWaveformStorage()
            val processor =
                AudioContextProcessor(
                    FakeProgressiveExtractor(emptyList(), samples),
                    storage,
                    coroutineContext = Dispatchers.Unconfined,
                )
            processor.processProgressively("fake://retained", 1000, Clock.System.now(), null, null).toList()
            assertEquals(samples, processor.cachedAmplitudes("fake://retained", 1000))
            assertNull(processor.cachedAmplitudes("fake://replacement", 1000))
            assertNull(processor.cachedAmplitudes("fake://retained", 2000))
            processor.clearCache("fake://retained")
            assertNull(processor.cachedAmplitudes("fake://retained", 1000))
        }

    @Test
    fun `process progressively emits empty then snapshots then final`() =
        runTest {
            val extractor =
                FakeProgressiveExtractor(
                    snapshots =
                        listOf(
                            listOf(0.2f),
                            listOf(0.4f, 0.5f),
                            listOf(0.4f, 0.6f, 0.7f),
                        ),
                    final = listOf(0.5f, 0.7f, 0.8f, 0.9f),
                )
            val storage = InMemoryWaveformStorage()
            val processor =
                AudioContextProcessor(
                    amplitudeExtractor = extractor,
                    waveformStorage = storage,
                    coroutineContext = Dispatchers.Unconfined,
                )

            val emissions =
                processor
                    .processProgressively(
                        audioUri = "fake://audio",
                        durationMs = 1000L,
                        createdAt = Clock.System.now(),
                        latitude = null,
                        longitude = null,
                    ).toList()

            // First emission paints immediately with empty amplitudes.
            assertTrue(emissions.first().amplitudes.isEmpty())
            // Final emission has the full waveform.
            assertEquals(listOf(0.5f, 0.7f, 0.8f, 0.9f), emissions.last().amplitudes)
            // Intermediate snapshots arrive in order.
            val amplitudeProgression = emissions.map { it.amplitudes.size }
            assertEquals(amplitudeProgression.sorted(), amplitudeProgression)
            // Final result is cached.
            assertEquals(listOf(0.5f, 0.7f, 0.8f, 0.9f), storage.load(waveformStorageKey("fake://audio", 1000)))
        }

    @Test
    fun `process progressively short circuits on cache hit`() =
        runTest {
            val cached = listOf(0.1f, 0.2f, 0.3f)
            val storage = InMemoryWaveformStorage().apply { save(waveformStorageKey("fake://audio", 1000), cached) }
            val extractor = FailingExtractor()
            val processor =
                AudioContextProcessor(
                    amplitudeExtractor = extractor,
                    waveformStorage = storage,
                    coroutineContext = Dispatchers.Unconfined,
                )

            val emissions =
                processor
                    .processProgressively(
                        audioUri = "fake://audio",
                        durationMs = 1000L,
                        createdAt = Clock.System.now(),
                        latitude = null,
                        longitude = null,
                    ).toList()

            assertEquals(1, emissions.size)
            assertEquals(cached, emissions.single().amplitudes)
        }
}

private class FakeProgressiveExtractor(
    private val snapshots: List<List<Float>>,
    private val final: List<Float>,
) : AmplitudeExtractor {
    override suspend fun extractAmplitudes(
        uri: String,
        targetSampleCount: Int,
    ): List<Float> = final

    override fun extractAmplitudesProgressively(
        uri: String,
        targetSampleCount: Int,
    ): Flow<List<Float>> =
        flow {
            snapshots.forEach { snapshot ->
                delay(1)
                emit(snapshot)
            }
            emit(final)
        }
}

private class FailingExtractor : AmplitudeExtractor {
    override suspend fun extractAmplitudes(
        uri: String,
        targetSampleCount: Int,
    ): List<Float> = error("extractor should not be called on cache hit")

    override fun extractAmplitudesProgressively(
        uri: String,
        targetSampleCount: Int,
    ): Flow<List<Float>> = error("extractor should not be called on cache hit")
}

private class InMemoryWaveformStorage : WaveformStorage {
    private val data = mutableMapOf<String, List<Float>>()

    override suspend fun save(
        audioUri: String,
        amplitudes: List<Float>,
    ) {
        data[audioUri] = amplitudes
    }

    override suspend fun load(audioUri: String): List<Float>? = data[audioUri]

    override suspend fun exists(audioUri: String): Boolean = audioUri in data

    override suspend fun delete(audioUri: String) {
        data.remove(audioUri)
    }
}
