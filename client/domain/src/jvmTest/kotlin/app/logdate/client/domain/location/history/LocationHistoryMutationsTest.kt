package app.logdate.client.domain.location.history

import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.Place
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.VisitMemoryLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class LocationHistoryMutationsTest {
    private val start = HISTORY_TEST_START
    private val date = HISTORY_TEST_DATE
    private val phone = HISTORY_TEST_PHONE
    private val tablet = HISTORY_TEST_TABLET

    @Test
    fun deletingVisitPersistsSuppressionAndPayloadRemovalInOneBatch() =
        runTest {
            val fixture = HistoryServiceTestFixture()
            fixture.save("observation:sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours)))
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
            val memory = historyTestNote(start + 1.hours)
            val fixture = HistoryServiceTestFixture(notes = listOf(memory))
            fixture.save("observation:sample", HistoryPayload.Observation(historyTestObservation("sample", start + 1.hours, tablet)))
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
            val fixture = HistoryServiceTestFixture(raw = listOf(point, point.copy(sampleId = "unrelated", userId = "other")))
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
            val memory = historyTestNote(start + 1.hours)
            val fixture = HistoryServiceTestFixture(notes = listOf(memory))
            fixture.service.addVisit(start, start + 2.hours, historyTestPlace("place"))
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
            val fixture = HistoryServiceTestFixture()
            fixture.service.savePlace(historyTestPlace("place"))
            fixture.service.savePlace(historyTestPlace("place").copy(name = "My cafe", userConfirmed = true))
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
            fixture.service.savePlace(historyTestPlace("place").copy(name = "A better name", userConfirmed = true))
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
            val fixture = HistoryServiceTestFixture()
            fixture.service.addVisit(start + 1.hours, start + 2.hours, historyTestPlace("place"))
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
            val fixture = HistoryServiceTestFixture()
            fixture.service.savePlace(historyTestPlace("place").copy(userConfirmed = false))
            fixture.service.savePlace(historyTestPlace("place").copy(name = "My place", userConfirmed = true))
            fixture.service.savePlace(historyTestPlace("place").copy(name = "Provider suggestion", userConfirmed = false))
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
            val fixture = HistoryServiceTestFixture()
            val secretNoteId = Uuid.parse("11111111-1111-1111-1111-111111111111")
            fixture.service.linkMemory("private-evidence", secretNoteId)
            fixture.service.linkMemory("private-evidence", secretNoteId)
            val record = fixture.records().single()
            assertTrue("private-evidence" !in record.id && secretNoteId.toString() !in record.id)
            assertEquals(
                VisitMemoryLink(secretNoteId.toString(), "private-evidence"),
                (Json.decodeFromString(HistoryPayload.serializer(), record.payload!!) as HistoryPayload.MemoryLink).value,
            )
        }

    @Test
    fun mergePreservesManualVisitsAndSuppressesTheOldSavedPlace() =
        runTest {
            val oldId = "00000000-0000-0000-0000-000000000003"
            val fixture = HistoryServiceTestFixture(savedPlaces = listOf(Place.UserDefined(Uuid.parse(oldId), "Old name", 36.0, -115.0)))
            fixture.service.addVisit(start + 1.hours, start + 2.hours, historyTestPlace(oldId))
            val before = fixture.snapshot().items.single() as PlaceVisit
            fixture.service.mergePlaces(oldId, historyTestPlace("merged"))
            val after = fixture.snapshot()
            assertEquals(before.id, after.items.single().id)
            assertEquals("merged", (after.items.single() as PlaceVisit).place?.id)
            assertTrue(after.places.none { it.id == oldId })
        }

    @Test
    fun mergeAlsoSuppressesASavedPlaceWithoutAHistoryPlaceRecord() =
        runTest {
            val oldId = "00000000-0000-0000-0000-000000000003"
            val fixture = HistoryServiceTestFixture(savedPlaces = listOf(Place.UserDefined(Uuid.parse(oldId), "Old name", 36.0, -115.0)))
            fixture.service.mergePlaces(oldId, historyTestPlace("merged"))
            assertTrue(fixture.snapshot().places.none { it.id == oldId })
        }

    @Test
    fun mergeAliasesSurviveServiceRecreationWithoutChangingJournalLocations() =
        runTest {
            val old = "00000000-0000-0000-0000-000000000003"
            val memory =
                historyTestNote(start + 1.hours).copy(
                    location = NoteLocation(place = NotePlace(Uuid.parse(old), "Old name", 36.0, -115.0)),
                )
            val fixture = HistoryServiceTestFixture(notes = listOf(memory))
            fixture.service.mergePlaces(old, historyTestPlace("middle"))
            fixture.service.mergePlaces("middle", historyTestPlace("final"))
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
            val fixture = HistoryServiceTestFixture()
            fixture.save("one", HistoryPayload.Correction(HistoryCorrection("one", "place:a", HistoryField.PLACE, "b")))
            fixture.save("two", HistoryPayload.Correction(HistoryCorrection("two", "place:b", HistoryField.PLACE, "a")))
            fixture.save("three", HistoryPayload.Correction(HistoryCorrection("three", "place:x", HistoryField.PLACE, "y")))
            fixture.save("four", HistoryPayload.Correction(HistoryCorrection("four", "place:x", HistoryField.PLACE, "z")))
            assertTrue(fixture.snapshot().placeAliases.isEmpty())
        }

    @Test
    fun radiusAndCategoryEditsPersistAndSummaryUsesIndexedTimestamps() =
        runTest {
            val place = SemanticPlace("p1", "Home Base", 36.0, -115.0, userConfirmed = true, radiusMeters = 75.0)
            val rawItem =
                LocationHistoryItem(
                    sampleId = "raw-1",
                    userId = "owner",
                    deviceId = phone,
                    timestamp = start + 2.hours,
                    loggedAt = start + 2.hours,
                    location = Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                    confidence = 1f,
                    isGenuine = true,
                )
            val noteAtDay = historyTestNote(start + 3.hours)
            val fixture = HistoryServiceTestFixture(notes = listOf(noteAtDay), raw = listOf(rawItem))
            fixture.service.savePlace(place)
            fixture.service.updatePlaceRadius("p1", 210.0)
            fixture.service.updatePlaceCategory("p1", "HOME")
            val resolvedPlace = fixture.snapshot().places.single()
            assertEquals(210.0, resolvedPlace.radiusMeters)
            assertEquals("HOME", resolvedPlace.category)
            val summary =
                fixture.service
                    .observeRecordedDaySummaries(date, date, TimeZone.UTC)
                    .first()
                    .getValue(date)
            assertTrue(summary.hasVisits)
            assertTrue(summary.hasMemories)
            assertEquals(date, fixture.service.latestRecordedDate(TimeZone.UTC))
        }
}
