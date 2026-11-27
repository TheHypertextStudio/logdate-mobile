package app.logdate.client.domain.location.history.replay

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal fun goldenLateScenarios(
    base: Instant,
    originLat: Double,
    originLon: Double,
): List<GoldenLocationVector> =
    listOf(
        b07CrossTimezoneFlight(originLat, originLon),
        b08DualDeviceFusion(base, originLat, originLon),
        b09IndoorColdStart(base, originLat, originLon),
        b10MockGpsContamination(base, originLat, originLon),
    )

private fun b07CrossTimezoneFlight(
    originLat: Double,
    originLon: Double,
): GoldenLocationVector {
    val morningStart = Instant.parse("2026-10-12T15:00:00Z")
    val morning =
        (0..12).map { i ->
            LocationObservation(
                id = "b07-la-$i",
                ownerId = "owner",
                deviceId = "phone-1",
                timestamp = morningStart + (i * 10).minutes,
                latitude = originLat,
                longitude = originLon,
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
                            "owner:phone-1:b07-la-0",
                            morningStart,
                            morningStart + 120.minutes,
                            true,
                            latitude = originLat,
                            longitude = originLon,
                        ),
                        ExpectedVisitVector(
                            "owner:phone-1:b07-ny-0",
                            eveningStart,
                            eveningStart + 120.minutes,
                            true,
                            latitude = nyLat,
                            longitude = nyLon,
                        ),
                    ),
                expectMultiZoneDay = true,
            ),
    )
}

private fun b08DualDeviceFusion(
    base: Instant,
    originLat: Double,
    originLon: Double,
): GoldenLocationVector {
    val phonePart1 =
        (0..12).map { i ->
            GoldenLocationCorpus.obs("b08-p1-$i", base + (i * 10).minutes, 0.0, 0.0, 15f, deviceId = "phone-1")
        }
    val tabletFill =
        (0..5).map { i ->
            GoldenLocationCorpus.obs("b08-tab-$i", base + 125.minutes + (i * 10).minutes, 1200.0, 0.0, 20f, deviceId = "tablet-1")
        }
    val phonePart2 =
        (0..12).map { i ->
            GoldenLocationCorpus.obs("b08-p2-$i", base + 185.minutes + (i * 10).minutes, 0.0, 0.0, 15f, deviceId = "phone-1")
        }
    val (tabLat, tabLon) = GoldenLocationCorpus.offset(originLat, originLon, 1200.0, 0.0)
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
                            "owner:phone-1:b08-p1-0",
                            base,
                            base + 120.minutes,
                            true,
                            latitude = originLat,
                            longitude = originLon,
                        ),
                        ExpectedVisitVector(
                            "owner:tablet-1:b08-tab-0",
                            base + 125.minutes,
                            base + 175.minutes,
                            true,
                            latitude = tabLat,
                            longitude = tabLon,
                        ),
                        ExpectedVisitVector(
                            "owner:phone-1:b08-p2-0",
                            base + 185.minutes,
                            base + 305.minutes,
                            true,
                            latitude = originLat,
                            longitude = originLon,
                        ),
                    ),
            ),
    )
}

private fun b09IndoorColdStart(
    base: Instant,
    originLat: Double,
    originLon: Double,
): GoldenLocationVector {
    val initialNetwork = GoldenLocationCorpus.obs("b09-net", base, 120.0, 0.0, 180f)
    val gpsFixes =
        (1..10).map { i ->
            GoldenLocationCorpus.obs(
                "b09-gps-$i",
                base + 90.seconds + (i * 2).minutes,
                0.0,
                0.0,
                15f,
            )
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
                            latitude = originLat,
                            longitude = originLon,
                            toleranceMeters = 15.0,
                        ),
                    ),
            ),
    )
}

private fun b10MockGpsContamination(
    base: Instant,
    originLat: Double,
    originLon: Double,
): GoldenLocationVector {
    val realStay = (0..6).map { i -> GoldenLocationCorpus.obs("b10-real-$i", base + (i * 3).minutes, 0.0, 0.0, 12f) }
    val mockStay =
        (0..5).map { i ->
            GoldenLocationCorpus.obs("b10-mock-$i", base + 25.minutes + (i * 2).minutes, 5000.0, 5000.0, 5f, isMock = true)
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
                            "owner:phone-1:b10-real-0",
                            base,
                            base + 18.minutes,
                            true,
                            latitude = originLat,
                            longitude = originLon,
                            toleranceMeters = 10.0,
                        ),
                    ),
            ),
    )
}
