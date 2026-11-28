package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.measureTimedValue

/**
 * The timeline reconstructs each visible day's location history, so a long, densely sampled stay
 * must cost about one pass over its samples. Stay detection used to copy and sort every anchor's
 * accuracy for each new sample, which kept phones busy for minutes after the timeline loaded.
 */
class StayDetectionCostTest {
    @Test
    fun `a whole day at one place sampled every two seconds reconstructs quickly`() {
        val random = Random(7)
        val samples =
            (0 until DAY_SAMPLES).map { index ->
                sample(
                    index = index,
                    northMeters = random.nextDouble(-6.0, 6.0),
                    eastMeters = random.nextDouble(-6.0, 6.0),
                    accuracy = random.nextInt(5, 40).toFloat(),
                )
            }

        val (items, elapsed) = measureTimedValue { ReconstructLocationDay()(samples) }

        val visit = items.filterIsInstance<PlaceVisit>().single()
        assertTrue(visit.confirmedStay)
        assertTrue(elapsed < 10.seconds, "Reconstructing $DAY_SAMPLES samples took $elapsed")
    }

    @Test
    fun `the running median matches the radius taken from every anchor`() {
        val random = Random(11)
        repeat(200) {
            val accuracies = SortedAccuracies()
            val anchors = mutableListOf<LocationObservation>()
            repeat(random.nextInt(1, 80)) { index ->
                val accuracy = if (random.nextInt(6) == 0) null else random.nextInt(1, 60).toFloat()
                accuracies.add(accuracy)
                anchors += sample(index, accuracy = accuracy)
                val parameters = HistoryReconstructionParameters()
                assertEquals(
                    dynamicCoreRadius(anchors, parameters),
                    coreRadiusForMedianAccuracy(accuracies.median(), parameters),
                )
            }
        }
    }

    private fun sample(
        index: Int,
        northMeters: Double = 0.0,
        eastMeters: Double = 0.0,
        accuracy: Float?,
    ) = LocationObservation(
        id = "sample-$index",
        ownerId = "user",
        deviceId = "phone",
        timestamp = BASE + (index * 2).seconds,
        latitude = HOME_LATITUDE + northMeters / METERS_PER_DEGREE,
        longitude = HOME_LONGITUDE + eastMeters / (METERS_PER_DEGREE * cos(HOME_LATITUDE * PI / 180)),
        accuracyMeters = accuracy,
        activity = TravelMode.STILL,
    )

    private companion object {
        const val DAY_SAMPLES = 43_200
        const val HOME_LATITUDE = 36.17
        const val HOME_LONGITUDE = -86.78
        const val METERS_PER_DEGREE = 111_320.0
        val BASE: Instant = Instant.parse("2026-09-27T05:00:00Z")
    }
}
