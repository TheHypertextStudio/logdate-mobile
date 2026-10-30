package app.logdate.ui.audio.extraction

import kotlin.math.sqrt

/** Bounded PCM accumulation shared by complete and progressive Android waveform decoding. */
internal class WaveformAmplitudeBuckets(
    sampleCount: Int,
) {
    private val sumSquares = DoubleArray(sampleCount)
    private val counts = LongArray(sampleCount)
    private var highestBucket = 0

    fun add(
        index: Int,
        squaredSamples: Double,
        count: Int,
    ) {
        sumSquares[index] += squaredSamples
        counts[index] += count
        highestBucket = maxOf(highestBucket, index)
    }

    fun snapshot(final: Boolean = false): List<Float> =
        sumSquares.indices.map { index ->
            if (counts[index] == 0L || !final && index >= highestBucket) {
                0f
            } else {
                // A fixed PCM scale keeps early geometry independent of later loud samples.
                val rms = sqrt(sumSquares[index] / counts[index]) / 32768.0
                sqrt(rms).toFloat().coerceIn(0f, 1f)
            }
        }
}
