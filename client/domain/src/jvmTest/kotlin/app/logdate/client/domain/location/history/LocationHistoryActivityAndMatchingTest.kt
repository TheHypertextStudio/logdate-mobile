package app.logdate.client.domain.location.history

import app.logdate.client.repository.location.ActivityHistoryItem
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.ManualVisit
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import app.logdate.shared.model.location.VisitMemoryLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class LocationHistoryActivityAndMatchingTest {
    private val start = HISTORY_TEST_START
    private val date = HISTORY_TEST_DATE
    private val phone = HISTORY_TEST_PHONE
    private val tablet = HISTORY_TEST_TABLET

    @Test
    fun rangeChoosesOneSourcePerDayWithoutLosingOtherDeviceDays() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            fixture.save("phoneYesterday", HistoryPayload.Observation(historyTestObservation("phoneYesterday", start - 2.hours)))
            fixture.save("tabletYesterday", HistoryPayload.Observation(historyTestObservation("tabletYesterday", start - 1.hours, tablet)))
            fixture.save("tabletToday", HistoryPayload.Observation(historyTestObservation("tabletToday", start + 2.hours, tablet)))
            val snapshot = fixture.service.observeRange(LocalDate.parse("2026-09-28"), date, zone = TimeZone.UTC).first()
            assertEquals(setOf("phoneYesterday", "tabletToday"), snapshot.items.flatMap { it.evidenceIds }.toSet())
            val preferred = fixture.service.observeRange(LocalDate.parse("2026-09-28"), date, tablet, TimeZone.UTC).first()
            assertEquals(setOf("tabletYesterday", "tabletToday"), preferred.items.flatMap { it.evidenceIds }.toSet())
        }

    @Test
    fun visitEndingAtMidnightIsExcludedButSampleAtMidnightRemains() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            fixture.save("place", HistoryPayload.Place(historyTestPlace("place")))
            fixture.save("previous", HistoryPayload.Manual(ManualVisit("previous", start - 1.hours, start, "place")))
            fixture.save("boundary", HistoryPayload.Observation(historyTestObservation("boundary", start)))
            assertEquals(listOf("boundary"), fixture.snapshot().items.flatMap { it.evidenceIds })
        }

    @Test
    fun recentIndependentVehicleEvidenceDoesNotClaimDrivingOrTransit() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            fixture.save("activity", HistoryPayload.Activity(historyTestActivity(start + 59.minutes, "IN_VEHICLE", "ENTER")))
            assertEquals(TravelMode.VEHICLE, (fixture.snapshot().items.single() as JourneyLeg).mode)
        }

    @Test
    fun localActivityEvidenceCanClassifyUnsyncedSamples() =
        runTest {
            val fixture =
                HistoryServiceTestFixture(
                    activities =
                        listOf(
                            ActivityHistoryItem("local", "owner", phone, start + 59.minutes, start + 1.hours, "ON_BICYCLE", "ENTER", "UTC"),
                        ),
                )
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            assertEquals(TravelMode.CYCLING, (fixture.snapshot().items.single() as JourneyLeg).mode)
        }

    @Test
    fun expiredExitedFutureAndOtherDeviceActivityDoNotInventMovement() =
        runTest {
            val invalid =
                listOf(
                    listOf(historyTestActivity(start + 20.minutes, "WALKING", "ENTER")),
                    listOf(
                        historyTestActivity(start + 59.minutes, "WALKING", "ENTER"),
                        historyTestActivity(start + 59.minutes, "WALKING", "EXIT").copy(id = "exit"),
                    ),
                    listOf(historyTestActivity(start + 61.minutes, "WALKING", "ENTER")),
                    listOf(historyTestActivity(start + 59.minutes, "WALKING", "ENTER").copy(deviceId = tablet)),
                )
            for (events in invalid) {
                val fixture = HistoryServiceTestFixture()
                fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
                events.forEach { fixture.save(it.id, HistoryPayload.Activity(it)) }
                assertTrue(fixture.snapshot().items.single() is PlaceVisit)
            }

            // A continuous trip carried past the old 10-minute cutoff stays IN_VEHICLE across 60 minutes.
            val continuous = HistoryServiceTestFixture()
            continuous.save("enter", HistoryPayload.Activity(historyTestActivity(start, "IN_VEHICLE", "ENTER")))
            for (m in 0..60 step 10) {
                continuous.save("s$m", HistoryPayload.Observation(historyTestObservation("s$m", start + m.minutes)))
            }
            val carriedLeg =
                continuous
                    .snapshot()
                    .items
                    .filterIsInstance<JourneyLeg>()
                    .single()
            assertEquals(TravelMode.VEHICLE, carriedLeg.mode)
            assertEquals(7, carriedLeg.evidenceIds.size)
            val expiredProjected =
                applyActivityEvidence(
                    listOf(historyTestObservation("late", start + 60.minutes)),
                    listOf(historyTestActivity(start, "IN_VEHICLE", "ENTER")),
                )
            val continuousProjected =
                applyActivityEvidence(
                    listOf(
                        historyTestObservation("s1", start + 15.minutes),
                        historyTestObservation("s2", start + 40.minutes),
                        historyTestObservation("s3", start + 60.minutes),
                    ),
                    listOf(historyTestActivity(start, "IN_VEHICLE", "ENTER")),
                )
            assertEquals(TravelMode.UNKNOWN, expiredProjected.single().activity)
            assertTrue(continuousProjected.all { it.activity == TravelMode.VEHICLE })
        }

    @Test
    fun adjacentPlacesStayAmbiguousUntilTheVisitIsCorrected() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            fixture.save("one", HistoryPayload.Place(historyTestPlace("one")))
            fixture.save("two", HistoryPayload.Place(historyTestPlace("two").copy(latitude = 36.0001)))
            val ambiguous = fixture.snapshot().items.single() as PlaceVisit
            assertEquals("one", ambiguous.place?.id)
            assertTrue(ambiguous.needsDisambiguation)
            assertEquals(listOf("one", "two"), ambiguous.candidatePlaceIds)
            fixture.service.correct("sample", HistoryField.PLACE, "two")
            val corrected = fixture.snapshot().items.single() as PlaceVisit
            assertEquals("two", corrected.place?.id)
            assertTrue(!corrected.needsDisambiguation)
        }

    @Test
    fun snapshotNotesAreDateBoundedButRetrospectiveVisitLinksRemain() =
        runTest {
            val today = historyTestNote(start + 1.hours)
            val unrelated = historyTestNote(start - 48.hours)
            val retrospective = historyTestNote(start + 48.hours)
            val fixture = HistoryServiceTestFixture(notes = listOf(today, unrelated, retrospective))
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            fixture.save("link", HistoryPayload.MemoryLink(VisitMemoryLink(retrospective.uid.toString(), "sample")))
            val snapshot = fixture.snapshot()
            assertEquals(setOf(today.uid, retrospective.uid), snapshot.notes.map { it.uid }.toSet())
            assertEquals(listOf(retrospective.uid), (snapshot.items.single() as PlaceVisit).memoryIds)
        }

    @Test
    fun mockSamplesAreExcludedByDefaultAndRetainedWhenExplicitlyIncluded() =
        runTest {
            val raw =
                LocationHistoryItem(
                    sampleId = "mock",
                    userId = "owner",
                    deviceId = phone,
                    timestamp = start + 1.hours,
                    location = Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                    confidence = 1f,
                    isGenuine = false,
                    activityType = "WALKING",
                    isMock = true,
                )
            val fixture = HistoryServiceTestFixture(raw = listOf(raw))
            assertTrue(fixture.snapshot().items.isEmpty())
            val included = ReconstructLocationDay()(listOf(raw.toObservation("owner")), includeMock = true)
            assertTrue((included.single() as JourneyLeg).route.single().isMock)
        }

    @Test
    fun conflictsOnlyReferToEvidenceDisplayedInTheSelectedDay() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            fixture.save("today", HistoryPayload.Observation(historyTestObservation("today", start + 1.hours)))
            fixture.save("yesterday", HistoryPayload.Observation(historyTestObservation("yesterday", start - 2.hours)))
            for (target in listOf("today", "yesterday")) {
                for (mode in listOf("WALKING", "RUNNING")) {
                    val id = "$target-$mode"
                    fixture.save(id, HistoryPayload.Correction(HistoryCorrection(id, target, HistoryField.ACTIVITY, mode)))
                }
            }
            assertEquals(
                setOf("today-WALKING", "today-RUNNING"),
                fixture
                    .snapshot()
                    .conflicts
                    .map { it.id }
                    .toSet(),
            )
        }
}
