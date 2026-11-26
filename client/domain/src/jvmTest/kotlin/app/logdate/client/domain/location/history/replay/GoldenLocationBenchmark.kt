package app.logdate.client.domain.location.history.replay

import app.logdate.client.domain.location.history.geographicDistance
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Serializable
internal data class ExpectedVisitVector(
    val id: String,
    val start: Instant,
    val end: Instant,
    val confirmedStay: Boolean,
    val placeId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val toleranceMeters: Double = 25.0,
    val kind: String = "PlaceVisit",
)

@Serializable
internal data class ExpectedJourneyVector(
    val start: Instant,
    val end: Instant,
    val mode: TravelMode,
    val expectedDistanceMeters: Double,
    val kind: String = "JourneyLeg",
)

@Serializable
internal data class GoldenVectorExpected(
    val stays: List<ExpectedVisitVector> = emptyList(),
    val journeys: List<ExpectedJourneyVector> = emptyList(),
    val maxFalseBurstSplits: Int = 0,
    val expectMultiZoneDay: Boolean = false,
)

@Serializable
internal data class GoldenLocationVector(
    val schemaVersion: Int = 4,
    val scenarioId: String,
    val title: String,
    val zoneId: String = "America/Los_Angeles",
    val sourceDeviceId: String? = null,
    val places: List<SemanticPlace> = emptyList(),
    val placeRadiiMeters: Map<String, Double> = emptyMap(),
    val activities: List<ActivityObservation> = emptyList(),
    val observations: List<LocationObservation> = emptyList(),
    val corrections: List<HistoryCorrection> = emptyList(),
    val expected: GoldenVectorExpected,
)

internal data class ScenarioScore(
    val scenarioId: String,
    val title: String,
    val matchedStays: Int,
    val expectedStays: Int,
    val predictedStays: Int,
    val stayPrecision: Double,
    val stayRecall: Double,
    val boundaryErrorSeconds: List<Long>,
    val centroidErrorsMeters: List<Double>,
    val falseBurstSplits: Int,
    val matchedModes: Int,
    val expectedModes: Int,
    val journeyDistanceErrorsFraction: List<Double>,
) {
    val meanBoundaryMaeSeconds: Double
        get() = if (boundaryErrorSeconds.isEmpty()) 0.0 else boundaryErrorSeconds.average()
    val medianCentroidErrorMeters: Double
        get() =
            if (centroidErrorsMeters.isEmpty()) {
                0.0
            } else {
                centroidErrorsMeters.sorted()[centroidErrorsMeters.size / 2]
            }
}

internal data class BenchmarkSuiteReport(
    val algorithmName: String,
    val scenarios: List<ScenarioScore>,
) {
    val stayPrecision: Double
        get() {
            val totalPredicted = scenarios.sumOf { it.predictedStays }
            val totalMatched = scenarios.sumOf { it.matchedStays }
            return if (totalPredicted == 0) 1.0 else totalMatched.toDouble() / totalPredicted
        }
    val stayRecall: Double
        get() {
            val totalExpected = scenarios.sumOf { it.expectedStays }
            val totalMatched = scenarios.sumOf { it.matchedStays }
            return if (totalExpected == 0) 1.0 else totalMatched.toDouble() / totalExpected
        }
    val boundaryMaeSeconds: Double
        get() {
            val all = scenarios.flatMap { it.boundaryErrorSeconds }
            return if (all.isEmpty()) 0.0 else all.average()
        }
    val medianCentroidErrorMeters: Double
        get() {
            val all = scenarios.flatMap { it.centroidErrorsMeters }.sorted()
            return if (all.isEmpty()) 0.0 else all[all.size / 2]
        }
    val travelModeAccuracy: Double
        get() {
            val totalExpected = scenarios.sumOf { it.expectedModes }
            val totalMatched = scenarios.sumOf { it.matchedModes }
            return if (totalExpected == 0) 1.0 else totalMatched.toDouble() / totalExpected
        }
    val meanDistanceErrorFraction: Double
        get() {
            val all = scenarios.flatMap { it.journeyDistanceErrorsFraction }
            return if (all.isEmpty()) 0.0 else all.average()
        }
    val totalFalseBurstSplits: Int
        get() = scenarios.sumOf { it.falseBurstSplits }
}

internal object GoldenLocationCorpus {
    private val base = Instant.parse("2026-10-12T16:00:00Z")
    private const val ORIGIN_LAT = 37.7749
    private const val ORIGIN_LON = -122.4194

    fun allScenarios(): List<GoldenLocationVector> =
        listOf(
            b01OfficeWifiDriftAndBurst(),
            b02HighwaySingleEnter(),
            b03AdjacentUrbanStops(),
            b04QuickCoffeeStop(),
            b05CampusEnvelopeWalk(),
            b06MultimodalKinematicCommute(),
            b07CrossTimezoneFlight(),
            b08DualDeviceFusion(),
            b09IndoorColdStart(),
            b10MockGpsContamination(),
        )

    private fun b01OfficeWifiDriftAndBurst(): GoldenLocationVector {
        val start = base
        val end = base + 4.hours
        val observations =
            (0..80).map { step ->
                val t = start + (step * 3).minutes
                val isBurst1 = step in 20..21
                val isBurst2 = step in 50..51
                when {
                    isBurst1 || isBurst2 ->
                        obs(
                            id = "b01-$step",
                            t = t,
                            northMeters = 450.0 + (step % 2) * 15.0,
                            eastMeters = 20.0,
                            accuracy = 55f,
                            activity = TravelMode.UNKNOWN,
                        )
                    else -> {
                        val angle = (step % 8) * (PI / 4.0)
                        val drift = if (step % 5 == 0) 58.0 else 18.0
                        obs(
                            id = "b01-$step",
                            t = t,
                            northMeters = drift * kotlin.math.sin(angle),
                            eastMeters = drift * cos(angle),
                            accuracy = if (step % 5 == 0) 24f else 15f,
                            activity = TravelMode.STILL,
                        )
                    }
                }
            }
        return GoldenLocationVector(
            scenarioId = "B01",
            title = "4h office stay with Wi-Fi drift and 2-sample cell bursts",
            observations = observations,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b01-0",
                                start = start,
                                end = end,
                                confirmedStay = true,
                                latitude = ORIGIN_LAT,
                                longitude = ORIGIN_LON,
                                toleranceMeters = 20.0,
                            ),
                        ),
                    maxFalseBurstSplits = 0,
                ),
        )
    }

    private fun b02HighwaySingleEnter(): GoldenLocationVector {
        val start = base
        val totalMinutes = 90
        val speed = 25.0
        val observations =
            (0..totalMinutes * 2).map { tick ->
                val secondsOffset = tick * 30L
                obs(
                    id = "b02-$tick",
                    t = start + secondsOffset.seconds,
                    northMeters = secondsOffset * speed,
                    eastMeters = if (tick % 4 == 0) 4.0 else -4.0,
                    accuracy = 12f,
                    speed = speed.toFloat(),
                    activity = TravelMode.UNKNOWN,
                )
            }
        val activities =
            listOf(
                ActivityObservation(
                    id = "act-b02-enter",
                    ownerId = "owner",
                    deviceId = "phone-1",
                    timestamp = start,
                    activityType = "IN_VEHICLE",
                    transitionType = "ENTER",
                    timeZoneId = "America/Los_Angeles",
                ),
            )
        return GoldenLocationVector(
            scenarioId = "B02",
            title = "90m highway trip with single ENTER IN_VEHICLE at t=0",
            activities = activities,
            observations = observations,
            expected =
                GoldenVectorExpected(
                    journeys =
                        listOf(
                            ExpectedJourneyVector(
                                start = start,
                                end = start + 90.minutes,
                                mode = TravelMode.VEHICLE,
                                expectedDistanceMeters = 90 * 60 * speed,
                            ),
                        ),
                ),
        )
    }

    private fun b03AdjacentUrbanStops(): GoldenLocationVector {
        val (cafeLat, cafeLon) = offset(ORIGIN_LAT, ORIGIN_LON, 0.0, 0.0)
        val (bookLat, bookLon) = offset(ORIGIN_LAT, ORIGIN_LON, 0.0, 65.0)
        val cafe = SemanticPlace("place-cafe", "Cedar Street Coffee", cafeLat, cafeLon, userConfirmed = true)
        val bookstore = SemanticPlace("place-book", "Harbor Books", bookLat, bookLon, userConfirmed = true)

        val cafeObs =
            (0..14).map { i ->
                obs("b03-cafe-$i", base + (i * 2).minutes, northMeters = (i % 3) * 3.0, eastMeters = 2.0, accuracy = 10f)
            }
        val walkBetween =
            listOf(
                obs(
                    id = "b03-walk-0",
                    t = base + 29.minutes,
                    northMeters = 0.0,
                    eastMeters = 32.0,
                    accuracy = 10f,
                    activity = TravelMode.WALKING,
                    speed = 1.4f,
                ),
            )
        val bookObs =
            (0..12).map { i ->
                obs("b03-book-$i", base + 30.minutes + (i * 2).minutes, northMeters = (i % 3) * 3.0, eastMeters = 65.0, accuracy = 10f)
            }
        return GoldenLocationVector(
            scenarioId = "B03",
            title = "Two adjacent urban stops 65m apart",
            places = listOf(cafe, bookstore),
            placeRadiiMeters = mapOf(cafe.id to 35.0, bookstore.id to 35.0),
            observations = cafeObs + walkBetween + bookObs,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b03-cafe-0",
                                start = base,
                                end = base + 28.minutes,
                                confirmedStay = true,
                                placeId = cafe.id,
                                latitude = cafeLat,
                                longitude = cafeLon,
                                toleranceMeters = 15.0,
                            ),
                            ExpectedVisitVector(
                                id = "owner:phone-1:b03-book-0",
                                start = base + 30.minutes,
                                end = base + 54.minutes,
                                confirmedStay = true,
                                placeId = bookstore.id,
                                latitude = bookLat,
                                longitude = bookLon,
                                toleranceMeters = 15.0,
                            ),
                        ),
                ),
        )
    }

    private fun b04QuickCoffeeStop(): GoldenLocationVector {
        val walk1 =
            (0..19).map { i ->
                obs(
                    id = "b04-w1-$i",
                    t = base + (i * 30).seconds,
                    northMeters = i * 40.0,
                    eastMeters = 0.0,
                    accuracy = 12f,
                    speed = 1.35f,
                    activity = TravelMode.WALKING,
                )
            }
        val stopNorth = 800.0
        val stopStart = base + 10.minutes
        val coffeeStop =
            (0..3).map { i ->
                obs(
                    id = "b04-stop-$i",
                    t = stopStart + (i * 75).seconds,
                    northMeters = stopNorth + (i % 2) * 3.0,
                    eastMeters = 0.0,
                    accuracy = 12f,
                    speed = 0.1f,
                    activity = TravelMode.STILL,
                )
            }
        val walk2Start = base + 14.minutes + 15.seconds
        val walk2 =
            (1..20).map { i ->
                obs(
                    id = "b04-w2-$i",
                    t = walk2Start + (i * 30).seconds,
                    northMeters = stopNorth + i * 40.0,
                    eastMeters = 0.0,
                    accuracy = 12f,
                    speed = 1.35f,
                    activity = TravelMode.WALKING,
                )
            }
        val (stopLat, stopLon) = offset(ORIGIN_LAT, ORIGIN_LON, stopNorth, 0.0)
        return GoldenLocationVector(
            scenarioId = "B04",
            title = "3m 45s high-accuracy coffee stop between walking legs",
            observations = walk1 + coffeeStop + walk2,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b04-stop-0",
                                start = stopStart,
                                end = stopStart + 225.seconds,
                                confirmedStay = true,
                                latitude = stopLat,
                                longitude = stopLon,
                                toleranceMeters = 15.0,
                            ),
                        ),
                    journeys =
                        listOf(
                            ExpectedJourneyVector(
                                start = base,
                                end = base + 570.seconds,
                                mode = TravelMode.WALKING,
                                expectedDistanceMeters = 760.0,
                            ),
                            ExpectedJourneyVector(
                                start = walk2Start + 30.seconds,
                                end = walk2Start + 600.seconds,
                                mode = TravelMode.WALKING,
                                expectedDistanceMeters = 760.0,
                            ),
                        ),
                ),
        )
    }

    private fun b05CampusEnvelopeWalk(): GoldenLocationVector {
        val campus = SemanticPlace("place-campus", "North Campus", ORIGIN_LAT, ORIGIN_LON, userConfirmed = true)
        val observations =
            (0..30).map { i ->
                val angle = i * (2 * PI / 15.0)
                val radius = 160.0
                obs(
                    id = "b05-$i",
                    t = base + (i * 90).seconds,
                    northMeters = radius * kotlin.math.sin(angle),
                    eastMeters = radius * cos(angle),
                    accuracy = 15f,
                    speed = 0.9f,
                    activity = TravelMode.WALKING,
                )
            }
        return GoldenLocationVector(
            scenarioId = "B05",
            title = "45m walking inside 350m saved campus place",
            places = listOf(campus),
            placeRadiiMeters = mapOf(campus.id to 350.0),
            observations = observations,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b05-0",
                                start = base,
                                end = base + 45.minutes,
                                confirmedStay = true,
                                placeId = campus.id,
                                latitude = ORIGIN_LAT,
                                longitude = ORIGIN_LON,
                                toleranceMeters = 200.0,
                            ),
                        ),
                ),
        )
    }

    private fun b06MultimodalKinematicCommute(): GoldenLocationVector {
        val walk1 =
            (0..18).map { i ->
                obs("b06-w1-$i", base + (i * 20).seconds, northMeters = i * 26.0, eastMeters = 0.0, accuracy = 10f, speed = 1.3f)
            }
        val driveStartNorth = 18 * 26.0
        val driveStart = base + 6.minutes + 20.seconds
        val drive =
            (1..72).map { i ->
                obs(
                    id = "b06-d-$i",
                    t = driveStart + (i * 15).seconds,
                    northMeters = driveStartNorth + i * 210.0,
                    eastMeters = if (i % 3 == 0) 6.0 else -6.0,
                    accuracy = 12f,
                    speed = 14.0f,
                )
            }
        val walk2StartNorth = driveStartNorth + 72 * 210.0
        val walk2Start = driveStart + (72 * 15).seconds + 20.seconds
        val walk2 =
            (0..12).map { i ->
                obs(
                    id = "b06-w2-$i",
                    t = walk2Start + (i * 20).seconds,
                    northMeters = walk2StartNorth + i * 26.0,
                    eastMeters = 0.0,
                    accuracy = 10f,
                    speed = 1.3f,
                )
            }
        return GoldenLocationVector(
            scenarioId = "B06",
            title = "Multimodal commute inferred without activity events",
            observations = walk1 + drive + walk2,
            expected =
                GoldenVectorExpected(
                    journeys =
                        listOf(
                            ExpectedJourneyVector(base, base + 360.seconds, TravelMode.WALKING, 468.0),
                            ExpectedJourneyVector(driveStart + 15.seconds, driveStart + (72 * 15).seconds, TravelMode.VEHICLE, 71 * 210.0),
                            ExpectedJourneyVector(walk2Start, walk2Start + 240.seconds, TravelMode.WALKING, 312.0),
                        ),
                ),
        )
    }

    private fun b07CrossTimezoneFlight(): GoldenLocationVector {
        val morningStart = Instant.parse("2026-10-12T15:00:00Z")
        val morning =
            (0..12).map { i ->
                LocationObservation(
                    id = "b07-la-$i",
                    ownerId = "owner",
                    deviceId = "phone-1",
                    timestamp = morningStart + (i * 10).minutes,
                    latitude = ORIGIN_LAT,
                    longitude = ORIGIN_LON,
                    accuracyMeters = 15f,
                    activity = TravelMode.STILL,
                    timeZoneId = "America/Los_Angeles",
                    source = "FOREGROUND_STREAM",
                )
            }
        val eveningStart = Instant.parse("2026-10-13T01:30:00Z")
        val nyLat = 40.7128
        val nyLon = -74.0060
        val evening =
            (0..12).map { i ->
                LocationObservation(
                    id = "b07-ny-$i",
                    ownerId = "owner",
                    deviceId = "phone-1",
                    timestamp = eveningStart + (i * 10).minutes,
                    latitude = nyLat,
                    longitude = nyLon,
                    accuracyMeters = 15f,
                    activity = TravelMode.STILL,
                    timeZoneId = "America/New_York",
                    source = "FOREGROUND_STREAM",
                )
            }
        return GoldenLocationVector(
            scenarioId = "B07",
            title = "Cross-timezone flight day LA to NY",
            zoneId = "America/Los_Angeles",
            observations = morning + evening,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b07-la-0",
                                start = morningStart,
                                end = morningStart + 120.minutes,
                                confirmedStay = true,
                                latitude = ORIGIN_LAT,
                                longitude = ORIGIN_LON,
                            ),
                            ExpectedVisitVector(
                                id = "owner:phone-1:b07-ny-0",
                                start = eveningStart,
                                end = eveningStart + 120.minutes,
                                confirmedStay = true,
                                latitude = nyLat,
                                longitude = nyLon,
                            ),
                        ),
                    expectMultiZoneDay = true,
                ),
        )
    }

    private fun b08DualDeviceFusion(): GoldenLocationVector {
        val phonePart1 =
            (0..12).map { i ->
                obs("b08-p1-$i", base + (i * 10).minutes, northMeters = 0.0, eastMeters = 0.0, accuracy = 15f, deviceId = "phone-1")
            }
        val tabletFill =
            (0..5).map { i ->
                obs(
                    id = "b08-tab-$i",
                    t = base + 125.minutes + (i * 10).minutes,
                    northMeters = 1200.0,
                    eastMeters = 0.0,
                    accuracy = 20f,
                    deviceId = "tablet-1",
                )
            }
        val phonePart2 =
            (0..12).map { i ->
                obs(
                    id = "b08-p2-$i",
                    t = base + 185.minutes + (i * 10).minutes,
                    northMeters = 0.0,
                    eastMeters = 0.0,
                    accuracy = 15f,
                    deviceId = "phone-1",
                )
            }
        val (tabLat, tabLon) = offset(ORIGIN_LAT, ORIGIN_LON, 1200.0, 0.0)
        return GoldenLocationVector(
            scenarioId = "B08",
            title = "Dual-device day filling 45m phone gap from tablet",
            sourceDeviceId = "all",
            observations = phonePart1 + tabletFill + phonePart2,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b08-p1-0",
                                start = base,
                                end = base + 120.minutes,
                                confirmedStay = true,
                                latitude = ORIGIN_LAT,
                                longitude = ORIGIN_LON,
                            ),
                            ExpectedVisitVector(
                                id = "owner:tablet-1:b08-tab-0",
                                start = base + 125.minutes,
                                end = base + 175.minutes,
                                confirmedStay = true,
                                latitude = tabLat,
                                longitude = tabLon,
                            ),
                            ExpectedVisitVector(
                                id = "owner:phone-1:b08-p2-0",
                                start = base + 185.minutes,
                                end = base + 305.minutes,
                                confirmedStay = true,
                                latitude = ORIGIN_LAT,
                                longitude = ORIGIN_LON,
                            ),
                        ),
                ),
        )
    }

    private fun b09IndoorColdStart(): GoldenLocationVector {
        val initialNetwork = obs("b09-net", base, northMeters = 120.0, eastMeters = 0.0, accuracy = 180f)
        val gpsFixes =
            (1..10).map { i ->
                obs("b09-gps-$i", base + 90.seconds + (i * 2).minutes, northMeters = 0.0, eastMeters = 0.0, accuracy = 15f)
            }
        return GoldenLocationVector(
            scenarioId = "B09",
            title = "Indoor cold start with coarse network fix before GPS lock",
            observations = listOf(initialNetwork) + gpsFixes,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b09-gps-1",
                                start = base,
                                end = base + 90.seconds + 20.minutes,
                                confirmedStay = true,
                                latitude = ORIGIN_LAT,
                                longitude = ORIGIN_LON,
                                toleranceMeters = 15.0,
                            ),
                        ),
                ),
        )
    }

    private fun b10MockGpsContamination(): GoldenLocationVector {
        val realStay =
            (0..6).map { i ->
                obs("b10-real-$i", base + (i * 3).minutes, northMeters = 0.0, eastMeters = 0.0, accuracy = 12f)
            }
        val mockStay =
            (0..5).map { i ->
                obs(
                    id = "b10-mock-$i",
                    t = base + 25.minutes + (i * 2).minutes,
                    northMeters = 5000.0,
                    eastMeters = 5000.0,
                    accuracy = 5f,
                    isMock = true,
                )
            }
        return GoldenLocationVector(
            scenarioId = "B10",
            title = "Mock GPS stay following real stay",
            observations = realStay + mockStay,
            expected =
                GoldenVectorExpected(
                    stays =
                        listOf(
                            ExpectedVisitVector(
                                id = "owner:phone-1:b10-real-0",
                                start = base,
                                end = base + 18.minutes,
                                confirmedStay = true,
                                latitude = ORIGIN_LAT,
                                longitude = ORIGIN_LON,
                                toleranceMeters = 10.0,
                            ),
                        ),
                ),
        )
    }

    private fun obs(
        id: String,
        t: Instant,
        northMeters: Double,
        eastMeters: Double,
        accuracy: Float?,
        speed: Float? = null,
        activity: TravelMode = TravelMode.STILL,
        deviceId: String = "phone-1",
        isMock: Boolean = false,
    ): LocationObservation {
        val (lat, lon) = offset(ORIGIN_LAT, ORIGIN_LON, northMeters, eastMeters)
        return LocationObservation(
            id = id,
            ownerId = "owner",
            deviceId = deviceId,
            timestamp = t,
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracy,
            speedMetersPerSecond = speed,
            activity = activity,
            timeZoneId = "America/Los_Angeles",
            source = "FOREGROUND_STREAM",
            isMock = isMock,
        )
    }

    internal fun offset(
        lat: Double,
        lon: Double,
        northMeters: Double,
        eastMeters: Double,
    ): Pair<Double, Double> {
        val dLat = (northMeters / 6_371_000.0) * (180.0 / PI)
        val dLon = (eastMeters / (6_371_000.0 * cos(lat * PI / 180.0))) * (180.0 / PI)
        return lat + dLat to lon + dLon
    }
}

internal fun scoreGoldenScenario(
    scenario: GoldenLocationVector,
    items: List<LocationDayItem>,
): ScenarioScore {
    val visits = items.filterIsInstance<PlaceVisit>().filter { it.confirmedStay }
    val journeys = items.filterIsInstance<JourneyLeg>()
    val expectedStays = scenario.expected.stays
    val usedVisits = mutableSetOf<String>()
    val boundaryErrors = mutableListOf<Long>()
    val centroidErrors = mutableListOf<Double>()

    var matchedStays = 0
    expectedStays.forEach { expected ->
        val best =
            visits
                .filter { it.id !in usedVisits }
                .filter { it.start <= expected.end && it.end >= expected.start }
                .minByOrNull { geographicDistance(it.latitude, it.longitude, expected.latitude, expected.longitude) }
        if (best != null) {
            val distance = geographicDistance(best.latitude, best.longitude, expected.latitude, expected.longitude)
            val placeMatches = expected.placeId == null || best.place == null || best.place?.id == expected.placeId
            if (distance <= maxOf(expected.toleranceMeters, 150.0) && placeMatches) {
                matchedStays++
                usedVisits += best.id
                boundaryErrors += abs((best.start - expected.start).inWholeSeconds)
                boundaryErrors += abs((best.end - expected.end).inWholeSeconds)
                centroidErrors += distance
            }
        }
    }

    val expectedJourneys = scenario.expected.journeys
    var matchedModes = 0
    val distanceErrors = mutableListOf<Double>()
    expectedJourneys.forEach { expected ->
        val overlapping = journeys.filter { it.start <= expected.end && it.end >= expected.start }
        val dominant = overlapping.maxByOrNull { it.end - it.start }
        if (dominant != null && dominant.mode == expected.mode) {
            matchedModes++
        }
        val actualDistance = overlapping.sumOf { leg -> pathLengthMeters(leg.route) }
        if (expected.expectedDistanceMeters > 0.0 && overlapping.isNotEmpty()) {
            distanceErrors += abs(actualDistance - expected.expectedDistanceMeters) / expected.expectedDistanceMeters
        }
    }

    val falseBurstSplits =
        if (expectedStays.size == 1 && scenario.scenarioId == "B01") {
            (visits.size - 1).coerceAtLeast(0)
        } else {
            0
        }

    return ScenarioScore(
        scenarioId = scenario.scenarioId,
        title = scenario.title,
        matchedStays = matchedStays,
        expectedStays = expectedStays.size,
        predictedStays = visits.size,
        stayPrecision = if (visits.isEmpty()) (if (expectedStays.isEmpty()) 1.0 else 0.0) else matchedStays.toDouble() / visits.size,
        stayRecall = if (expectedStays.isEmpty()) 1.0 else matchedStays.toDouble() / expectedStays.size,
        boundaryErrorSeconds = boundaryErrors,
        centroidErrorsMeters = centroidErrors,
        falseBurstSplits = falseBurstSplits,
        matchedModes = matchedModes,
        expectedModes = expectedJourneys.size,
        journeyDistanceErrorsFraction = distanceErrors,
    )
}

internal fun pathLengthMeters(points: List<LocationObservation>): Double =
    points.zipWithNext().sumOf { (a, b) ->
        geographicDistance(a.latitude, a.longitude, b.latitude, b.longitude)
    }

internal fun formatBenchmarkReport(report: BenchmarkSuiteReport): String =
    buildString {
        appendLine("Algorithm: ${report.algorithmName}")
        report.scenarios.forEach { score ->
            appendLine(
                "  ${score.scenarioId} (${score.title}): " +
                    "stays ${score.matchedStays}/${score.expectedStays} (pred=${score.predictedStays}), " +
                    "boundaryMAE=${"%.1f".format(score.meanBoundaryMaeSeconds)}s, " +
                    "centroidMed=${"%.1f".format(score.medianCentroidErrorMeters)}m, " +
                    "modes=${score.matchedModes}/${score.expectedModes}, " +
                    "burstSplits=${score.falseBurstSplits}",
            )
        }
        appendLine(
            "Totals: precision=${"%.3f".format(report.stayPrecision)} " +
                "recall=${"%.3f".format(report.stayRecall)} " +
                "boundaryMAE=${"%.1f".format(report.boundaryMaeSeconds)}s " +
                "centroidMed=${"%.1f".format(report.medianCentroidErrorMeters)}m " +
                "modeAcc=${"%.3f".format(report.travelModeAccuracy)} " +
                "distErr=${"%.3f".format(report.meanDistanceErrorFraction)} " +
                "burstSplits=${report.totalFalseBurstSplits}",
        )
    }
