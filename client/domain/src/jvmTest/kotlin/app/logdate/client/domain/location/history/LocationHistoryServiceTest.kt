package app.logdate.client.domain.location.history

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.location.ActivityHistoryItem
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.Place
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.ManualVisit
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import app.logdate.shared.model.location.VisitMemoryLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocationHistoryServiceTest {
    private val start = Instant.parse("2026-09-29T00:00:00Z")
    private val date = LocalDate.parse("2026-09-29")
    private val phone = "00000000-0000-0000-0000-000000000001"
    private val tablet = "00000000-0000-0000-0000-000000000002"

    @Test
    fun anotherDaysCurrentDeviceDoesNotHideThisDaysRecording() =
        runTest {
            val fixture = Fixture()
            fixture.save("previous", HistoryPayload.Observation(observation("previous", start - 2.hours)))
            fixture.save("today", HistoryPayload.Observation(observation("today", start + 2.hours, tablet)))
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
                Fixture(
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
            val memory = note(start + 240.hours)
            val fixture = Fixture(notes = listOf(memory))
            for (i in 0..900) {
                fixture.save(
                    "observation:s$i",
                    HistoryPayload.Observation(observation("s$i", start - 74.hours + (i * 5).minutes)),
                )
            }
            fixture.service.savePlace(place("renamed"))
            fixture.service.correct("s0", HistoryField.PLACE, "renamed")
            fixture.service.linkMemory("s0", memory.uid.toString())
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
            assertEquals(listOf(memory.uid.toString()), day.memoryIds)
            assertTrue(fixture.boundaryReads > 0)
        }

    @Test
    fun noisyExtendedStayIsOneVisitWithOneIdentityAcrossDayAndMonthViews() =
        runTest {
            val fixture = Fixture()
            for (i in 0..900) {
                val sample = observation("s$i", start - 74.hours + (i * 5).minutes)
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
            val fixture = Fixture()
            val walk = (start - 24.hours)..(start - 24.hours + 10.minutes)
            for (i in 0..900) {
                val time = start - 74.hours + (i * 5).minutes
                if (time in walk) continue
                fixture.save("observation:s$i", HistoryPayload.Observation(observation("s$i", time)))
            }
            for (minute in 1..10) {
                fixture.save(
                    "observation:walk$minute",
                    HistoryPayload.Observation(
                        observation(
                            "walk$minute",
                            start - 24.hours + minute.minutes,
                        ).copy(latitude = 36.006, activity = TravelMode.WALKING),
                    ),
                )
            }
            fixture.service.savePlace(place("old").copy(latitude = 35.0))
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
            val fixture = Fixture()
            for (i in 0..900) {
                fixture.save(
                    "observation:s$i",
                    HistoryPayload.Observation(observation("s$i", start - 74.hours + (i * 5).minutes)),
                )
            }
            var snapshot: LocationHistorySnapshot? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.service.observeDay(date, zone = TimeZone.UTC).collect { snapshot = it }
            }
            runCurrent()
            val originalId = snapshot!!.items.single().id
            fixture.save("observation:late", HistoryPayload.Observation(observation("late", start - 74.hours - 5.minutes)))
            fixture.save(
                "observation:other-owner",
                HistoryPayload.Observation(observation("other-owner", start - 74.hours - 10.minutes).copy(ownerId = "other")),
            )
            fixture.save(
                "observation:other-device",
                HistoryPayload.Observation(observation("other-device", start - 74.hours - 10.minutes, tablet)),
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
            val retrospective = note(start + 240.hours)
            val unrelated = note(start - 240.hours)
            val fixture = Fixture(notes = listOf(retrospective, unrelated))
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
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
            val retrospective = note(start + 240.hours)
            val fixture = Fixture(notes = listOf(retrospective))
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            fixture.save("link", HistoryPayload.MemoryLink(VisitMemoryLink(retrospective.uid.toString(), "sample")))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.service.observeDay(date, zone = TimeZone.UTC).collect()
            }
            runCurrent()
            fixture.save("place", HistoryPayload.Place(place("place")))
            runCurrent()
            assertEquals(listOf(retrospective.uid), fixture.requestedNoteIds)
        }

    @Test
    fun optionalSourceFailuresDoNotEraseRecordedHistory() =
        runTest {
            val fixture =
                Fixture(
                    notesFlow = flow { error("notes unavailable") },
                    placesFlow = flow { error("places unavailable") },
                    activityFlow = flow { error("activity unavailable") },
                )
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            val snapshot = fixture.service.observeDay(date, zone = TimeZone.UTC).first { it.failedSections.size == 3 }
            assertEquals(listOf("sample"), snapshot.items.flatMap { it.evidenceIds })
            assertEquals(HistoryFailedSection.entries.toSet(), snapshot.failedSections)
        }

    @Test
    fun optionalSourceKeepsItsLastSuccessfulDataWhenItFails() =
        runTest {
            val memory = note(start + 1.hours)
            val fixture =
                Fixture(
                    notesFlow =
                        flow {
                            emit(listOf(memory))
                            error("notes disconnected")
                        },
                )
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            val snapshot = fixture.service.observeDay(date, zone = TimeZone.UTC).first { HistoryFailedSection.NOTES in it.failedSections }
            assertEquals(listOf(memory), snapshot.notes)
            assertEquals(1, snapshot.items.size)
        }

    @Test
    fun deletingVisitPersistsSuppressionAndPayloadRemovalInOneBatch() =
        runTest {
            val fixture = Fixture()
            fixture.save("observation:sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            val priorSingleWrites = fixture.singleWrites
            fixture.service.deleteVisit(fixture.snapshot().items.single())
            assertEquals(1, fixture.batchWrites)
            assertEquals(priorSingleWrites, fixture.singleWrites)
            assertTrue(fixture.records().any { it.deleted })
            assertTrue(fixture.records().any { it.recordType == "correction" })
        }

    @Test
    fun deletingVisitTombstonesCapturedCoordinatesButKeepsJournalMemory() =
        runTest {
            val memory = note(start + 1.hours)
            val fixture = Fixture(notes = listOf(memory))
            fixture.save("observation:sample", HistoryPayload.Observation(observation("sample", start + 1.hours, tablet)))
            fixture.service.deleteVisit(fixture.snapshot().items.single())
            val record = fixture.records().first { it.id == "observation:sample" }
            assertTrue(record.deleted)
            assertNull(record.payload)
            assertEquals(tablet, record.deviceId)
            assertEquals(listOf(memory), fixture.snapshot().notes)
            assertTrue(fixture.snapshot().items.isEmpty())
        }

    @Test
    fun deletingUnstagedLegacyEvidenceKeepsItsDeviceAndIgnoresOtherAccounts() =
        runTest {
            val point =
                LocationHistoryItem(
                    sampleId = "legacy",
                    userId = "default_user",
                    deviceId = phone,
                    timestamp = start + 1.hours,
                    loggedAt = start + 1.hours,
                    location = Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                    confidence = 1f,
                    isGenuine = true,
                )
            val fixture = Fixture(raw = listOf(point, point.copy(sampleId = "unrelated", userId = "other")))
            fixture.service.deleteVisit(fixture.snapshot().items.single())
            val tombstone = fixture.records().first { it.id == "observation:legacy" }
            assertTrue(tombstone.deleted)
            assertNull(tombstone.payload)
            assertEquals(phone, tombstone.deviceId)
            assertTrue(fixture.records().none { it.id == "observation:unrelated" })
        }

    @Test
    fun deletingManualVisitRemovesItsPayloadAndPreservesMemory() =
        runTest {
            val memory = note(start + 1.hours)
            val fixture = Fixture(notes = listOf(memory))
            fixture.service.addVisit(start, start + 2.hours, place("place"))
            val visit = fixture.snapshot().items.single()
            fixture.service.deleteVisit(visit)
            val record = fixture.records().first { it.id == visit.id }
            assertTrue(record.deleted)
            assertNull(record.payload)
            assertEquals(listOf(memory), fixture.snapshot().notes)
        }

    @Test
    fun userRenamePersistsAsAnImmutableLabelCorrectionAndSurvivesRestart() =
        runTest {
            val fixture = Fixture()
            fixture.service.savePlace(place("place"))
            fixture.service.savePlace(place("place").copy(name = "My cafe", userConfirmed = true))
            val records = fixture.records().mapNotNull { it.payload }.map { Json.decodeFromString(HistoryPayload.serializer(), it) }
            assertEquals(
                "place",
                records
                    .filterIsInstance<HistoryPayload.Place>()
                    .single()
                    .value.name,
            )
            assertEquals(
                "My cafe",
                records
                    .filterIsInstance<HistoryPayload.Correction>()
                    .single()
                    .value.value,
            )
            assertEquals(
                "My cafe",
                fixture
                    .createService()
                    .observeDay(date, zone = TimeZone.UTC)
                    .first()
                    .places
                    .single()
                    .name,
            )
            fixture.service.savePlace(place("place").copy(name = "A better name", userConfirmed = true))
            assertEquals(
                "A better name",
                fixture
                    .snapshot()
                    .places
                    .single()
                    .name,
            )
        }

    @Test
    fun concurrentPlaceLabelsRemainConflictedUntilExplicitResolution() =
        runTest {
            val fixture = Fixture()
            fixture.service.addVisit(start + 1.hours, start + 2.hours, place("place"))
            fixture.save("label-one", HistoryPayload.Correction(HistoryCorrection("label-one", "place:place", HistoryField.LABEL, "One")))
            fixture.save("label-two", HistoryPayload.Correction(HistoryCorrection("label-two", "place:place", HistoryField.LABEL, "Two")))
            fixture.save(
                "unrelated-label-one",
                HistoryPayload.Correction(HistoryCorrection("unrelated-label-one", "place:other", HistoryField.LABEL, "Other one")),
            )
            fixture.save(
                "unrelated-label-two",
                HistoryPayload.Correction(HistoryCorrection("unrelated-label-two", "place:other", HistoryField.LABEL, "Other two")),
            )
            assertEquals(
                setOf("label-one", "label-two"),
                fixture
                    .snapshot()
                    .conflicts
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(
                "place",
                fixture
                    .snapshot()
                    .places
                    .single()
                    .name,
            )
            fixture.service.correct("place:place", HistoryField.LABEL, "Chosen")
            val resolved = fixture.snapshot()
            assertTrue(resolved.conflicts.isEmpty())
            assertEquals("Chosen", (resolved.items.single() as PlaceVisit).place?.name)
        }

    @Test
    fun providerSuggestionNeverOverwritesAUserLabel() =
        runTest {
            val fixture = Fixture()
            fixture.service.savePlace(place("place").copy(userConfirmed = false))
            fixture.service.savePlace(place("place").copy(name = "My place", userConfirmed = true))
            fixture.service.savePlace(place("place").copy(name = "Provider suggestion", userConfirmed = false))
            assertEquals(
                "My place",
                fixture
                    .snapshot()
                    .places
                    .single()
                    .name,
            )
            assertTrue(
                fixture
                    .snapshot()
                    .places
                    .single()
                    .userConfirmed,
            )
        }

    @Test
    fun memoryLinkIdentityIsOpaqueAndRepeatedLinkIsIdempotent() =
        runTest {
            val fixture = Fixture()
            fixture.service.linkMemory("private-evidence", "private-note")
            fixture.service.linkMemory("private-evidence", "private-note")
            val record = fixture.records().single()
            assertTrue("private-evidence" !in record.id && "private-note" !in record.id)
            assertEquals(
                VisitMemoryLink("private-note", "private-evidence"),
                (Json.decodeFromString(HistoryPayload.serializer(), record.payload!!) as HistoryPayload.MemoryLink).value,
            )
        }

    @Test
    fun rangeChoosesOneSourcePerDayWithoutLosingOtherDeviceDays() =
        runTest {
            val fixture = Fixture()
            fixture.save("phoneYesterday", HistoryPayload.Observation(observation("phoneYesterday", start - 2.hours)))
            fixture.save("tabletYesterday", HistoryPayload.Observation(observation("tabletYesterday", start - 1.hours, tablet)))
            fixture.save("tabletToday", HistoryPayload.Observation(observation("tabletToday", start + 2.hours, tablet)))
            val snapshot = fixture.service.observeRange(LocalDate.parse("2026-09-28"), date, zone = TimeZone.UTC).first()
            assertEquals(setOf("phoneYesterday", "tabletToday"), snapshot.items.flatMap { it.evidenceIds }.toSet())
            val preferred = fixture.service.observeRange(LocalDate.parse("2026-09-28"), date, tablet, TimeZone.UTC).first()
            assertEquals(setOf("tabletYesterday", "tabletToday"), preferred.items.flatMap { it.evidenceIds }.toSet())
        }

    @Test
    fun visitEndingAtMidnightIsExcludedButSampleAtMidnightRemains() =
        runTest {
            val fixture = Fixture()
            fixture.save("place", HistoryPayload.Place(place("place")))
            fixture.save("previous", HistoryPayload.Manual(ManualVisit("previous", start - 1.hours, start, "place")))
            fixture.save("boundary", HistoryPayload.Observation(observation("boundary", start)))
            assertEquals(listOf("boundary"), fixture.snapshot().items.flatMap { it.evidenceIds })
        }

    @Test
    fun mergePreservesManualVisitsAndSuppressesTheOldSavedPlace() =
        runTest {
            val oldId = "00000000-0000-0000-0000-000000000003"
            val fixture = Fixture(savedPlaces = listOf(Place.UserDefined(Uuid.parse(oldId), "Old name", 36.0, -115.0)))
            val original = place(oldId)
            fixture.service.addVisit(start + 1.hours, start + 2.hours, original)
            val before = fixture.snapshot().items.single() as PlaceVisit
            fixture.service.mergePlaces(oldId, place("merged"))
            val after = fixture.snapshot()
            assertEquals(before.id, after.items.single().id)
            assertEquals("merged", (after.items.single() as PlaceVisit).place?.id)
            assertTrue(after.places.none { it.id == oldId })
        }

    @Test
    fun mergeAlsoSuppressesASavedPlaceWithoutAHistoryPlaceRecord() =
        runTest {
            val oldId = "00000000-0000-0000-0000-000000000003"
            val fixture = Fixture(savedPlaces = listOf(Place.UserDefined(Uuid.parse(oldId), "Old name", 36.0, -115.0)))
            fixture.service.mergePlaces(oldId, place("merged"))
            assertTrue(fixture.snapshot().places.none { it.id == oldId })
        }

    @Test
    fun recentIndependentVehicleEvidenceDoesNotClaimDrivingOrTransit() =
        runTest {
            val fixture = Fixture()
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            fixture.save("activity", HistoryPayload.Activity(activity(start + 59.minutes, "IN_VEHICLE", "ENTER")))
            assertEquals(TravelMode.VEHICLE, (fixture.snapshot().items.single() as JourneyLeg).mode)
        }

    @Test
    fun localActivityEvidenceCanClassifyUnsyncedSamples() =
        runTest {
            val fixture =
                Fixture(
                    activities =
                        listOf(
                            ActivityHistoryItem(
                                "local",
                                "owner",
                                phone,
                                start + 59.minutes,
                                start + 1.hours,
                                "ON_BICYCLE",
                                "ENTER",
                                "UTC",
                            ),
                        ),
                )
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            assertEquals(TravelMode.CYCLING, (fixture.snapshot().items.single() as JourneyLeg).mode)
        }

    @Test
    fun expiredExitedFutureAndOtherDeviceActivityDoNotInventMovement() =
        runTest {
            val invalid =
                listOf(
                    listOf(activity(start + 40.minutes, "WALKING", "ENTER")),
                    listOf(
                        activity(start + 59.minutes, "WALKING", "ENTER"),
                        activity(start + 59.minutes, "WALKING", "EXIT").copy(id = "exit"),
                    ),
                    listOf(activity(start + 61.minutes, "WALKING", "ENTER")),
                    listOf(activity(start + 59.minutes, "WALKING", "ENTER").copy(deviceId = tablet)),
                )
            for (events in invalid) {
                val fixture = Fixture()
                fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
                events.forEach { fixture.save(it.id, HistoryPayload.Activity(it)) }
                assertTrue(fixture.snapshot().items.single() is PlaceVisit)
            }
        }

    @Test
    fun adjacentPlacesStayAmbiguousUntilTheVisitIsCorrected() =
        runTest {
            val fixture = Fixture()
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            fixture.save("one", HistoryPayload.Place(place("one")))
            fixture.save("two", HistoryPayload.Place(place("two").copy(latitude = 36.0001)))
            assertNull((fixture.snapshot().items.single() as PlaceVisit).place)
            fixture.service.correct("sample", app.logdate.shared.model.location.HistoryField.PLACE, "two")
            assertEquals("two", (fixture.snapshot().items.single() as PlaceVisit).place?.id)
        }

    @Test
    fun snapshotNotesAreDateBoundedButRetrospectiveVisitLinksRemain() =
        runTest {
            val today = note(start + 1.hours)
            val unrelated = note(start - 48.hours)
            val retrospective = note(start + 48.hours)
            val fixture = Fixture(notes = listOf(today, unrelated, retrospective))
            fixture.save("sample", HistoryPayload.Observation(observation("sample", start + 1.hours)))
            fixture.save("link", HistoryPayload.MemoryLink(VisitMemoryLink(retrospective.uid.toString(), "sample")))
            val snapshot = fixture.snapshot()
            assertEquals(setOf(today.uid, retrospective.uid), snapshot.notes.map { it.uid }.toSet())
            assertEquals(listOf(retrospective.uid.toString()), (snapshot.items.single() as PlaceVisit).memoryIds)
        }

    @Test
    fun locallyKnownMockFlagIsRetainedOnJourneyEvidence() =
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
            val fixture = Fixture(raw = listOf(raw))
            assertTrue((fixture.snapshot().items.single() as JourneyLeg).route.single().isMock)
        }

    @Test
    fun conflictsOnlyReferToEvidenceDisplayedInTheSelectedDay() =
        runTest {
            val fixture = Fixture()
            fixture.save("today", HistoryPayload.Observation(observation("today", start + 1.hours)))
            fixture.save("yesterday", HistoryPayload.Observation(observation("yesterday", start - 2.hours)))
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

    @Test
    fun mergeAliasesSurviveServiceRecreationWithoutChangingJournalLocations() =
        runTest {
            val old = "00000000-0000-0000-0000-000000000003"
            val memory = note(start + 1.hours).copy(location = NoteLocation(place = NotePlace(Uuid.parse(old), "Old name", 36.0, -115.0)))
            val fixture = Fixture(notes = listOf(memory))
            fixture.service.mergePlaces(old, place("middle"))
            fixture.service.mergePlaces("middle", place("final"))
            val restored = fixture.createService().observeDay(date, zone = TimeZone.UTC).first()
            assertEquals(mapOf(old to "final", "middle" to "final"), restored.placeAliases)
            assertEquals(memory, restored.notes.single())
            assertEquals(
                old,
                restored.notes
                    .single()
                    .location
                    ?.place
                    ?.id
                    .toString(),
            )
        }

    @Test
    fun cyclicAndConflictingAliasesAreNotApplied() =
        runTest {
            val fixture = Fixture()
            fixture.save("one", HistoryPayload.Correction(HistoryCorrection("one", "place:a", HistoryField.PLACE, "b")))
            fixture.save("two", HistoryPayload.Correction(HistoryCorrection("two", "place:b", HistoryField.PLACE, "a")))
            fixture.save("three", HistoryPayload.Correction(HistoryCorrection("three", "place:x", HistoryField.PLACE, "y")))
            fixture.save("four", HistoryPayload.Correction(HistoryCorrection("four", "place:x", HistoryField.PLACE, "z")))
            assertTrue(fixture.snapshot().placeAliases.isEmpty())
        }

    private fun observation(
        id: String,
        time: Instant,
        deviceId: String = phone,
    ) = LocationObservation(id, "owner", deviceId, time, 36.0, -115.0, accuracyMeters = 8f)

    private fun activity(
        time: Instant,
        type: String,
        transition: String,
    ) = ActivityObservation("activity", "owner", phone, time, type, transition)

    private fun place(id: String) = SemanticPlace(id, id, 36.0, -115.0, userConfirmed = true)

    private fun note(time: Instant) = JournalNote.Text(creationTimestamp = time, lastUpdated = time, content = "Memory")

    private inner class Fixture(
        private val savedPlaces: List<Place> = emptyList(),
        private val notes: List<JournalNote> = emptyList(),
        private val notesFlow: Flow<List<JournalNote>>? = null,
        private val placesFlow: Flow<List<Place>>? = null,
        private val activityFlow: Flow<List<ActivityHistoryItem>>? = null,
        private val activities: List<ActivityHistoryItem> = emptyList(),
        private val raw: List<LocationHistoryItem> = emptyList(),
    ) {
        var boundaryReads = 0
        var batchWrites = 0
        var singleWrites = 0
        var allNotesReads = 0
        val requestedRanges = mutableListOf<Pair<Instant, Instant>>()
        val requestedNoteIds = mutableListOf<Uuid>()
        private val state = MutableStateFlow(emptyList<HistoryRecord>())
        private val store =
            object : HistoryRecordStore {
                override fun observe(
                    ownerId: String,
                    origin: String,
                ) = state

                override suspend fun records(
                    ownerId: String,
                    origin: String,
                ) = state.value

                override suspend fun put(
                    ownerId: String,
                    origin: String,
                    record: HistoryRecord,
                ) {
                    singleWrites++
                    state.value = state.value.filterNot { it.id == record.id } + record
                }

                override suspend fun putBatch(
                    ownerId: String,
                    origin: String,
                    records: List<HistoryRecord>,
                ) {
                    batchWrites++
                    val replaced = records.map { it.id }.toSet()
                    state.value = state.value.filterNot { it.id in replaced } + records
                }

                override suspend fun observationsBefore(
                    ownerId: String,
                    origin: String,
                    deviceId: String,
                    beforeMillis: Long,
                    beforeSampleId: String,
                    limit: Int,
                ): List<HistoryRecord> {
                    boundaryReads++
                    return state.value
                        .mapNotNull { record ->
                            if (record.deleted) {
                                null
                            } else {
                                record.payload?.let { text ->
                                    (Json.decodeFromString(HistoryPayload.serializer(), text) as? HistoryPayload.Observation)?.value?.let {
                                        record to
                                            it
                                    }
                                }
                            }
                        }.filter { (_, point) ->
                            point.ownerId == ownerId &&
                                point.deviceId == deviceId &&
                                (
                                    point.timestamp.toEpochMilliseconds() < beforeMillis ||
                                        (
                                            point.timestamp.toEpochMilliseconds() == beforeMillis &&
                                                point.id < beforeSampleId
                                        )
                                )
                        }.sortedWith(
                            compareByDescending<Pair<HistoryRecord, LocationObservation>> {
                                it.second.timestamp
                            }.thenByDescending { it.second.id },
                        ).take(limit)
                        .map { it.first }
                }

                override suspend fun pending(
                    ownerId: String,
                    origin: String,
                    limit: Int,
                ) = emptyList<HistoryRecord>()

                override suspend fun cursor(
                    ownerId: String,
                    origin: String,
                ) = 0L

                override suspend fun applyPage(
                    ownerId: String,
                    origin: String,
                    records: List<HistoryRecord>,
                    cursor: Long,
                ) = Unit

                override suspend fun acknowledge(
                    ownerId: String,
                    origin: String,
                    id: String,
                    deviceVersion: Long,
                    serverVersion: Long,
                ) = Unit
            }
        val service = createService()

        fun createService() =
            LocationHistoryService(
                rawHistory =
                    object : LocationHistoryRepository by proxy<LocationHistoryRepository>({ flowOf(raw) }) {
                        override suspend fun getLocationHistoryBefore(
                            userId: String,
                            deviceId: String,
                            before: Instant,
                            beforeSampleId: String,
                            limit: Int,
                        ): List<LocationHistoryItem> =
                            raw
                                .filter {
                                    it.userId == userId &&
                                        it.deviceId == deviceId &&
                                        (it.timestamp < before || (it.timestamp == before && it.sampleId < beforeSampleId))
                                }.sortedWith(compareByDescending<LocationHistoryItem> { it.timestamp }.thenByDescending { it.sampleId })
                                .take(limit)

                        override suspend fun getLocationHistoryBetween(
                            startTime: Instant,
                            endTime: Instant,
                        ) = raw.filter { it.timestamp >= startTime && it.timestamp < endTime }
                    },
                store = store,
                notes =
                    object : JournalNotesRepository by proxy<JournalNotesRepository>({ flowOf(notes) }) {
                        override val allNotesObserved: Flow<List<JournalNote>>
                            get() {
                                allNotesReads++
                                return notesFlow ?: flowOf(notes)
                            }

                        override fun observeNotesInRange(
                            start: Instant,
                            end: Instant,
                        ): Flow<List<JournalNote>> {
                            requestedRanges += start to end
                            return notesFlow ?: flowOf(notes.filter { it.creationTimestamp >= start && it.creationTimestamp < end })
                        }

                        override suspend fun getNoteById(noteId: Uuid): JournalNote? {
                            requestedNoteIds += noteId
                            return notes.firstOrNull { it.uid == noteId }
                        }
                    },
                owner = proxy<CanonicalOwnerProvider> { "owner" },
                device = proxy<DeviceIdProvider> { MutableStateFlow(Uuid.parse(phone)) },
                config = proxy<LogDateConfigRepository> { "https://example.test" },
                userPlaces = proxy<UserPlacesRepository> { placesFlow ?: flowOf(savedPlaces) },
                activityHistory = proxy<ActivityHistoryRepository> { activityFlow ?: flowOf(activities) },
                reconstructionDispatcher = Dispatchers.Unconfined,
            )

        suspend fun save(
            id: String,
            payload: HistoryPayload,
        ) {
            store.put(
                "owner",
                "https://example.test",
                HistoryRecord(
                    id,
                    "test",
                    Json.encodeToString(HistoryPayload.serializer(), payload),
                    (payload as? HistoryPayload.Observation)?.value?.deviceId ?: phone,
                ),
            )
        }

        suspend fun records() = store.records("owner", "https://example.test")

        suspend fun snapshot() = service.observeDay(date, zone = TimeZone.UTC).first()
    }
}

private inline fun <reified T> proxy(crossinline value: () -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> value() } as T
