package app.logdate.client.domain.location.history.replay

import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
        ) + goldenLateScenarios(base, ORIGIN_LAT, ORIGIN_LON)

    private fun b01OfficeWifiDriftAndBurst(): GoldenLocationVector {
        val end = base + 4.hours
        val observations =
            (0..80).map { step ->
                val t = base + (step * 3).minutes
                if (step in 20..21 || step in 50..51) {
                    obs("b01-$step", t, 450.0 + (step % 2) * 15.0, 20.0, 55f, activity = TravelMode.UNKNOWN)
                } else {
                    val angle = (step % 8) * (PI / 4.0)
                    val drift = if (step % 5 == 0) 58.0 else 18.0
                    val accuracy = if (step % 5 == 0) 24f else 15f
                    obs("b01-$step", t, drift * sin(angle), drift * cos(angle), accuracy, activity = TravelMode.STILL)
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
                                "owner:phone-1:b01-0",
                                base,
                                end,
                                true,
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
        val speed = 25.0
        val observations =
            (0..180).map { tick ->
                val sec = tick * 30L
                val eastMeters = if (tick % 4 == 0) 4.0 else -4.0
                obs("b02-$tick", base + sec.seconds, sec * speed, eastMeters, 12f, speed.toFloat(), TravelMode.UNKNOWN)
            }
        return GoldenLocationVector(
            scenarioId = "B02",
            title = "90m highway trip with single ENTER IN_VEHICLE at t=0",
            activities =
                listOf(
                    ActivityObservation("act-b02-enter", "owner", "phone-1", base, "IN_VEHICLE", "ENTER", "America/Los_Angeles"),
                ),
            observations = observations,
            expected =
                GoldenVectorExpected(
                    journeys =
                        listOf(
                            ExpectedJourneyVector(base, base + 90.minutes, TravelMode.VEHICLE, 90 * 60 * speed),
                        ),
                ),
        )
    }

    private fun b03AdjacentUrbanStops(): GoldenLocationVector {
        val (cafeLat, cafeLon) = offset(ORIGIN_LAT, ORIGIN_LON, 0.0, 0.0)
        val (bookLat, bookLon) = offset(ORIGIN_LAT, ORIGIN_LON, 0.0, 65.0)
        val cafe = SemanticPlace("place-cafe", "Cedar Street Coffee", cafeLat, cafeLon, userConfirmed = true, radiusMeters = 35.0)
        val bookstore = SemanticPlace("place-book", "Harbor Books", bookLat, bookLon, userConfirmed = true, radiusMeters = 35.0)
        val cafeObs = (0..14).map { i -> obs("b03-cafe-$i", base + (i * 2).minutes, (i % 3) * 3.0, 2.0, 10f) }
        val walkBetween = listOf(obs("b03-walk-0", base + 29.minutes, 0.0, 32.0, 10f, 1.4f, TravelMode.WALKING))
        val bookObs = (0..12).map { i -> obs("b03-book-$i", base + 30.minutes + (i * 2).minutes, (i % 3) * 3.0, 65.0, 10f) }
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
                                "owner:phone-1:b03-cafe-0",
                                base,
                                base + 28.minutes,
                                true,
                                cafe.id,
                                cafeLat,
                                cafeLon,
                                15.0,
                            ),
                            ExpectedVisitVector(
                                "owner:phone-1:b03-book-0",
                                base + 30.minutes,
                                base + 54.minutes,
                                true,
                                bookstore.id,
                                bookLat,
                                bookLon,
                                15.0,
                            ),
                        ),
                ),
        )
    }

    private fun b04QuickCoffeeStop(): GoldenLocationVector {
        val walk1 = (0..19).map { i -> obs("b04-w1-$i", base + (i * 30).seconds, i * 40.0, 0.0, 12f, 1.35f, TravelMode.WALKING) }
        val stopNorth = 800.0
        val stopStart = base + 10.minutes
        val coffeeStop =
            (0..3).map { i ->
                obs("b04-stop-$i", stopStart + (i * 75).seconds, stopNorth + (i % 2) * 3.0, 0.0, 12f, 0.1f, TravelMode.STILL)
            }
        val walk2Start = base + 14.minutes + 15.seconds
        val walk2 =
            (1..20).map { i ->
                obs("b04-w2-$i", walk2Start + (i * 30).seconds, stopNorth + i * 40.0, 0.0, 12f, 1.35f, TravelMode.WALKING)
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
                            ExpectedJourneyVector(base, base + 570.seconds, TravelMode.WALKING, 760.0),
                            ExpectedJourneyVector(walk2Start + 30.seconds, walk2Start + 600.seconds, TravelMode.WALKING, 760.0),
                        ),
                ),
        )
    }

    private fun b05CampusEnvelopeWalk(): GoldenLocationVector {
        val campus = SemanticPlace("place-campus", "North Campus", ORIGIN_LAT, ORIGIN_LON, userConfirmed = true, radiusMeters = 350.0)
        val observations =
            (0..30).map { i ->
                val angle = i * (2 * PI / 15.0)
                obs("b05-$i", base + (i * 90).seconds, 160.0 * sin(angle), 160.0 * cos(angle), 15f, 0.9f, TravelMode.WALKING)
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
                                "owner:phone-1:b05-0",
                                base,
                                base + 45.minutes,
                                true,
                                campus.id,
                                ORIGIN_LAT,
                                ORIGIN_LON,
                                200.0,
                            ),
                        ),
                ),
        )
    }

    private fun b06MultimodalKinematicCommute(): GoldenLocationVector {
        val walk1 = (0..18).map { i -> obs("b06-w1-$i", base + (i * 20).seconds, i * 26.0, 0.0, 10f, 1.3f) }
        val driveStartNorth = 18 * 26.0
        val driveStart = base + 6.minutes + 20.seconds
        val drive =
            (1..72).map { i ->
                val eastMeters = if (i % 3 == 0) 6.0 else -6.0
                obs("b06-d-$i", driveStart + (i * 15).seconds, driveStartNorth + i * 210.0, eastMeters, 12f, 14.0f)
            }
        val walk2StartNorth = driveStartNorth + 72 * 210.0
        val walk2Start = driveStart + (72 * 15).seconds + 20.seconds
        val walk2 = (0..12).map { i -> obs("b06-w2-$i", walk2Start + (i * 20).seconds, walk2StartNorth + i * 26.0, 0.0, 10f, 1.3f) }
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

    internal fun obs(
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
