package app.logdate.client.domain.location.history

import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import app.logdate.shared.model.location.VisitMemoryLink
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocationHistoryServiceTest {
    private val start = HISTORY_TEST_START
    private val date = HISTORY_TEST_DATE
    private val phone = HISTORY_TEST_PHONE
    private val tablet = HISTORY_TEST_TABLET

    @Test
    fun anotherDaysCurrentDeviceDoesNotHideThisDaysRecording() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            fixture.save("previous", HistoryPayload.Observation(historyTestObservation("previous", start - 2.hours)))
            fixture.save("today", HistoryPayload.Observation(historyTestObservation("today", start + 2.hours, tablet)))
            val snapshot = fixture.snapshot()
            assertEquals(tablet, snapshot.selectedDeviceId)
            assertEquals(listOf(tablet), snapshot.recordingDevices)
            assertEquals(listOf("today"), snapshot.items.flatMap { it.evidenceIds })
        }

    @Test
    fun rawCaptureCarryInIncludesOnlyCanonicalAndThisDevicesLegacyEvidence() =
        runTest {
            val prototype =
                LocationHistoryItem(
                    "raw",
                    "owner",
                    phone,
                    start,
                    start,
                    Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                    1f,
                    true,
                    accuracyMeters = 8f,
                )
            val raw = (0..900).map { i -> prototype.copy(sampleId = "r$i", timestamp = start - 74.hours + (i * 5).minutes) }
            val legacy = prototype.copy(sampleId = "legacy", userId = "default_user", timestamp = start - 74.hours - 5.minutes)
            val fixture =
                HistoryServiceTestFixture(
                    raw =
                        raw + legacy +
                            listOf(
                                legacy.copy(sampleId = "another-owner", userId = "another"),
                                legacy.copy(sampleId = "another-device", deviceId = tablet),
                            ),
                )
            val visit = fixture.snapshot().items.single()
            assertEquals("legacy", visit.evidenceIds.first())
            assertTrue(visit.evidenceIds.none { it.startsWith("another-") })
        }

    @Test
    fun extendedStayKeepsItsAnchorCorrectionAndMemoryAcrossDayAndMonthViews() =
        runTest {
            val memory = historyTestNote(start + 240.hours)
            val fixture = HistoryServiceTestFixture(notes = listOf(memory))
            for (i in 0..900) {
                fixture.save(
                    "observation:s$i",
                    HistoryPayload.Observation(historyTestObservation("s$i", start - 74.hours + (i * 5).minutes)),
                )
            }
            fixture.service.savePlace(historyTestPlace("renamed"))
            fixture.service.correct("s0", HistoryField.PLACE, "renamed")
            fixture.service.linkMemory("s0", memory.uid)
            val day =
                fixture
                    .snapshot()
                    .items
                    .filterIsInstance<PlaceVisit>()
                    .single()
            val month =
                fixture.service
                    .observeRange(LocalDate.parse("2026-08-30"), date, zone = TimeZone.UTC)
                    .first()
                    .items
                    .filterIsInstance<PlaceVisit>()
                    .single()
            assertEquals(month.id, day.id)
            assertEquals("renamed", day.place?.id)
            assertEquals(listOf(memory.uid), day.memoryIds)
            assertTrue(fixture.boundaryReads > 0)
        }

    @Test
    fun noisyExtendedStayIsOneVisitWithOneIdentityAcrossDayAndMonthViews() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            for (i in 0..900) {
                val sample = historyTestObservation("s$i", start - 74.hours + (i * 5).minutes)
                val noisy =
                    when {
                        i % 37 == 0 -> sample.copy(latitude = 36.0036, accuracyMeters = 30f)
                        i % 53 == 0 -> sample.copy(latitude = 36.0027, accuracyMeters = null)
                        else -> sample.copy(latitude = 36.0 + (i % 7 - 3) * 0.0002, accuracyMeters = 40f)
                    }
                fixture.save("observation:s$i", HistoryPayload.Observation(noisy))
            }
            val day = fixture.snapshot().items.single() as PlaceVisit
            val month =
                fixture.service
                    .observeRange(LocalDate.parse("2026-08-30"), date, zone = TimeZone.UTC)
                    .first()
                    .items
                    .single() as PlaceVisit
            assertTrue(day.confirmedStay)
            assertEquals(month.id, day.id)
            assertEquals("s1", day.evidenceIds.first())
        }

    @Test
    fun backwardBoundaryStopsAtSeparateWalkAndReturnWithoutApplyingOlderEdits() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            val walk = (start - 24.hours)..(start - 24.hours + 10.minutes)
            for (i in 0..900) {
                val time = start - 74.hours + (i * 5).minutes
                if (time in walk) continue
                fixture.save("observation:s$i", HistoryPayload.Observation(historyTestObservation("s$i", time)))
            }
            for (minute in 1..10) {
                fixture.save(
                    "observation:walk$minute",
                    HistoryPayload.Observation(
                        historyTestObservation("walk$minute", start - 24.hours + minute.minutes).copy(
                            latitude = 36.006,
                            activity = TravelMode.WALKING,
                        ),
                    ),
                )
            }
            fixture.service.savePlace(historyTestPlace("old").copy(latitude = 35.0))
            fixture.service.correct("s0", HistoryField.PLACE, "old")
            val visit =
                fixture
                    .snapshot()
                    .items
                    .filterIsInstance<PlaceVisit>()
                    .single()
            assertTrue("s0" !in visit.evidenceIds)
            assertNull(visit.place)
        }

    @Test
    fun lateOlderEvidenceInvalidatesBoundaryCacheWithoutMixingAccountsOrDevices() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            for (i in 0..900) {
                fixture.save(
                    "observation:s$i",
                    HistoryPayload.Observation(historyTestObservation("s$i", start - 74.hours + (i * 5).minutes)),
                )
            }
            var snapshot: LocationHistorySnapshot? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.service.observeDay(date, zone = TimeZone.UTC).collect { snapshot = it }
            }
            runCurrent()
            val originalId = snapshot!!.items.single().id
            fixture.save("observation:late", HistoryPayload.Observation(historyTestObservation("late", start - 74.hours - 5.minutes)))
            fixture.save(
                "observation:other-owner",
                HistoryPayload.Observation(historyTestObservation("other-owner", start - 74.hours - 10.minutes).copy(ownerId = "other")),
            )
            fixture.save(
                "observation:other-device",
                HistoryPayload.Observation(historyTestObservation("other-device", start - 74.hours - 10.minutes, tablet)),
            )
            runCurrent()
            assertTrue(snapshot!!.items.single().id != originalId)
            assertEquals(
                "late",
                snapshot!!
                    .items
                    .single()
                    .evidenceIds
                    .first(),
            )
            assertTrue(
                snapshot!!
                    .items
                    .single()
                    .evidenceIds
                    .none { it.startsWith("other-") },
            )
        }

    @Test
    fun noteReadsAreBoundedAndOnlyContextLinksFetchRetrospectiveMemories() =
        runTest {
            val retrospective = historyTestNote(start + 240.hours)
            val unrelated = historyTestNote(start - 240.hours)
            val fixture = HistoryServiceTestFixture(notes = listOf(retrospective, unrelated))
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            fixture.save("link", HistoryPayload.MemoryLink(VisitMemoryLink(retrospective.uid.toString(), "sample")))
            fixture.save("unrelated", HistoryPayload.MemoryLink(VisitMemoryLink(unrelated.uid.toString(), "unavailable-evidence")))
            val snapshot = fixture.snapshot()
            assertEquals(0, fixture.allNotesReads)
            assertEquals(listOf((start - 24.hours) to (start + 48.hours)), fixture.requestedRanges)
            assertEquals(listOf(retrospective.uid), fixture.requestedNoteIds)
            assertEquals(listOf(retrospective), snapshot.notes)
        }

    @Test
    fun unrelatedHistoryUpdatesDoNotRefetchLinkedNotes() =
        runTest {
            val retrospective = historyTestNote(start + 240.hours)
            val fixture = HistoryServiceTestFixture(notes = listOf(retrospective))
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            fixture.save("link", HistoryPayload.MemoryLink(VisitMemoryLink(retrospective.uid.toString(), "sample")))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.service.observeDay(date, zone = TimeZone.UTC).collect()
            }
            runCurrent()
            fixture.save("place", HistoryPayload.Place(historyTestPlace("place")))
            runCurrent()
            assertEquals(listOf(retrospective.uid), fixture.requestedNoteIds)
        }

    @Test
    fun optionalSourceFailuresDoNotEraseRecordedHistory() =
        runTest {
            val fixture =
                HistoryServiceTestFixture(
                    notesFlow = flow { error("notes unavailable") },
                    placesFlow = flow { error("places unavailable") },
                    activityFlow = flow { error("activity unavailable") },
                )
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            val snapshot = fixture.service.observeDay(date, zone = TimeZone.UTC).first { it.failedSections.size == 3 }
            assertEquals(listOf("sample"), snapshot.items.flatMap { it.evidenceIds })
            assertEquals(HistoryFailedSection.entries.toSet(), snapshot.failedSections)
        }

    @Test
    fun optionalSourceKeepsItsLastSuccessfulDataWhenItFails() =
        runTest {
            val memory = historyTestNote(start + 1.hours)
            val fixture =
                HistoryServiceTestFixture(
                    notesFlow =
                        flow {
                            emit(listOf(memory))
                            error("notes disconnected")
                        },
                )
            fixture.save("sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
            val snapshot = fixture.service.observeDay(date, zone = TimeZone.UTC).first { HistoryFailedSection.NOTES in it.failedSections }
            assertEquals(listOf(memory), snapshot.notes)
            assertEquals(1, snapshot.items.size)
        }
}
