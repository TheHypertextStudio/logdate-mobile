package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.archive.ArchiveBlockType
import app.logdate.client.domain.export.archive.ArchiveCounts
import app.logdate.client.domain.export.archive.ArchiveDraft
import app.logdate.client.domain.export.archive.ArchiveDraftBlock
import app.logdate.client.domain.export.archive.ArchiveDraftFile
import app.logdate.client.domain.export.archive.ArchiveGenerator
import app.logdate.client.domain.export.archive.ArchiveJournal
import app.logdate.client.domain.export.archive.ArchiveJournalFile
import app.logdate.client.domain.export.archive.ArchiveJson
import app.logdate.client.domain.export.archive.ArchiveLocation
import app.logdate.client.domain.export.archive.ArchiveLocationSample
import app.logdate.client.domain.export.archive.ArchiveManifest
import app.logdate.client.domain.export.archive.ArchiveMediaFile
import app.logdate.client.domain.export.archive.ArchiveMediaInventoryEntry
import app.logdate.client.domain.export.archive.ArchiveMediaRef
import app.logdate.client.domain.export.archive.ArchiveMediaStatus
import app.logdate.client.domain.export.archive.ArchiveNote
import app.logdate.client.domain.export.archive.ArchiveNoteFile
import app.logdate.client.domain.export.archive.ArchiveNoteType
import app.logdate.client.domain.export.archive.ArchiveOmissionReason
import app.logdate.client.domain.export.archive.ArchiveOwner
import app.logdate.client.domain.export.archive.ArchivePath
import app.logdate.client.domain.export.archive.ArchivePlace
import app.logdate.client.domain.export.archive.ArchivePlaceFile
import app.logdate.client.domain.export.archive.ArchiveProfile
import app.logdate.client.domain.export.archive.ArchiveProfileFile
import app.logdate.client.domain.export.archive.ArchiveScope
import app.logdate.client.domain.restore.IntegrityCategory
import app.logdate.client.domain.restore.RestoreArchiveTestFixtures.FakeMediaImporter
import app.logdate.client.repository.journals.JournalNote
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

internal class V2ArchiveRestoreTest : RestoreArchiveTestFixture() {
    @Test
    fun `restore imports every supported record from a v2 archive`() =
        runTest {
            val journalId = Uuid.random()
            val noteId = Uuid.random()
            val omittedNoteId = Uuid.random()
            val draftId = Uuid.random()
            val omittedDraftBlockId = Uuid.random()
            val mediaPath = ArchivePath.of("media/photos/2026/photo.jpg")
            val manifest =
                ArchiveManifest(
                    schemaVersion = ExportSchemaVersion.V2_0,
                    exportedAt = now,
                    exportTimeZone = "America/Los_Angeles",
                    generator = ArchiveGenerator(version = "2.0.0"),
                    owner = ArchiveOwner(displayName = "Alice"),
                    scope = ArchiveScope(complete = true),
                    counts =
                        ArchiveCounts(
                            journals = 1,
                            notes = 2,
                            drafts = 1,
                            media = 1,
                            places = 1,
                            locationSamples = 1,
                            hasProfile = true,
                        ),
                    contents = emptyList(),
                )
            val media =
                ArchiveMediaRef(
                    status = ArchiveMediaStatus.INCLUDED,
                    path = mediaPath,
                    mediaType = "image/jpeg",
                )
            val bundle =
                V2RestoreBundle(
                    manifestJson = ArchiveJson.document.encodeToString(ArchiveManifest.serializer(), manifest),
                    journalsJson =
                        ArchiveJson.document.encodeToString(
                            ArchiveJournalFile.serializer(),
                            ArchiveJournalFile(
                                listOf(
                                    ArchiveJournal(
                                        id = journalId.toString(),
                                        title = "Daily",
                                        description = "Every day",
                                        createdAt = now - 30.days,
                                        updatedAt = now,
                                    ),
                                ),
                            ),
                        ),
                    notesJson =
                        ArchiveJson.document.encodeToString(
                            ArchiveNoteFile.serializer(),
                            ArchiveNoteFile(
                                listOf(
                                    ArchiveNote(
                                        id = noteId.toString(),
                                        type = ArchiveNoteType.IMAGE,
                                        createdAt = now,
                                        updatedAt = now,
                                        timeZone = "America/Los_Angeles",
                                        caption = "Sunset",
                                        media = media,
                                        location = ArchiveLocation(36.1, -115.2, placeName = "Las Vegas"),
                                        journalIds = listOf(journalId.toString()),
                                    ),
                                    ArchiveNote(
                                        id = omittedNoteId.toString(),
                                        type = ArchiveNoteType.IMAGE,
                                        createdAt = now,
                                        updatedAt = now,
                                        caption = "Unavailable photo",
                                        media =
                                            ArchiveMediaRef(
                                                status = ArchiveMediaStatus.OMITTED,
                                                omittedReason = ArchiveOmissionReason.UNREADABLE,
                                            ),
                                        journalIds = listOf(journalId.toString()),
                                    ),
                                ),
                            ),
                        ),
                    draftsJson =
                        ArchiveJson.document.encodeToString(
                            ArchiveDraftFile.serializer(),
                            ArchiveDraftFile(
                                listOf(
                                    ArchiveDraft(
                                        id = draftId.toString(),
                                        journalIds = listOf(journalId.toString()),
                                        createdAt = now,
                                        updatedAt = now,
                                        blocks =
                                            listOf(
                                                ArchiveDraftBlock(
                                                    id = Uuid.random().toString(),
                                                    type = ArchiveBlockType.TEXT,
                                                    timestamp = now,
                                                    text = "Work in progress",
                                                ),
                                                ArchiveDraftBlock(
                                                    id = omittedDraftBlockId.toString(),
                                                    type = ArchiveBlockType.IMAGE,
                                                    timestamp = now,
                                                    media =
                                                        ArchiveMediaRef(
                                                            status = ArchiveMediaStatus.OMITTED,
                                                            omittedReason = ArchiveOmissionReason.UNREADABLE,
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                    profileJson =
                        ArchiveJson.document.encodeToString(
                            ArchiveProfileFile.serializer(),
                            ArchiveProfileFile(ArchiveProfile(displayName = "Alice", updatedAt = now)),
                        ),
                    placesJson =
                        ArchiveJson.document.encodeToString(
                            ArchivePlaceFile.serializer(),
                            ArchivePlaceFile(
                                listOf(
                                    ArchivePlace(
                                        id = Uuid.random().toString(),
                                        name = "Home",
                                        latitude = 36.1,
                                        longitude = -115.2,
                                        radiusMeters = 100.0,
                                    ),
                                ),
                            ),
                        ),
                    locationHistoryJsonLines =
                        ArchiveJson.line.encodeToString(
                            ArchiveLocationSample.serializer(),
                            ArchiveLocationSample(
                                timestamp = now,
                                loggedAt = now,
                                latitude = 36.1,
                                longitude = -115.2,
                                altitudeMeters = 610.0,
                                confidence = 0.9,
                                isGenuine = true,
                                isMock = false,
                                capturePipeline = "HIGH_DETAIL",
                                captureSource = "MANUAL",
                            ),
                        ) + "\n",
                    mediaInventoryJson =
                        ArchiveJson.document.encodeToString(
                            ArchiveMediaFile.serializer(),
                            ArchiveMediaFile(
                                listOf(
                                    ArchiveMediaInventoryEntry(
                                        path = mediaPath,
                                        mediaType = "image/jpeg",
                                        bytes = 4,
                                        sha256 = "a".repeat(64),
                                    ),
                                ),
                            ),
                        ),
                )

            val result =
                useCase.restore(
                    bundle,
                    mediaImporter = FakeMediaImporter(mapOf(mediaPath.value to "file:///restored/photo.jpg")),
                )

            assertEquals(ExportSchemaVersion.V2_0, result.metadata.version)
            assertEquals(1, result.journalsImported)
            assertEquals(2, result.notesImported)
            assertEquals(2, result.journalLinksImported)
            assertEquals(1, result.draftsImported)
            assertEquals(1, result.mediaImported)
            assertEquals("Daily", journalRepo.created.single().title)
            val restoredNote = assertIs<JournalNote.Image>(notesRepo.created.single { it.uid == noteId })
            assertEquals("file:///restored/photo.jpg", restoredNote.mediaRef)
            assertEquals("Sunset", restoredNote.caption)
            assertEquals("America/Los_Angeles", restoredNote.timeZoneId)
            assertEquals(setOf(noteId to journalId, omittedNoteId to journalId), contentRepo.links.toSet())
            assertEquals(2, result.unavailableMedia.size)
            assertTrue(result.unavailableMedia.any { it.blockId == omittedDraftBlockId.toString() })
            assertTrue(result.unavailableMedia.any { it.entityId == omittedNoteId.toString() })
            assertEquals(
                2,
                journalRepo.savedDrafts
                    .single()
                    .blocks.size,
            )
            assertEquals("Alice", profileRepo.profile.displayName)
            assertEquals(1, placesRepo.places.size)
            assertEquals(1, locationHistoryRepo.logLocationCallCount)
        }

    @Test
    fun `v2 restore stores location history under the restoring owner and device`() =
        runTest {
            val manifest =
                ArchiveManifest(
                    exportedAt = now,
                    exportTimeZone = "America/Los_Angeles",
                    generator = ArchiveGenerator(version = "2.0.0"),
                    owner = ArchiveOwner(displayName = "Alice"),
                    scope = ArchiveScope(complete = true),
                    counts =
                        ArchiveCounts(
                            journals = 0,
                            notes = 0,
                            drafts = 0,
                            media = 0,
                            places = 0,
                            locationSamples = 2,
                            hasProfile = false,
                        ),
                    contents = emptyList(),
                )
            val samples =
                listOf(36.1, 36.2).map { latitude ->
                    ArchiveLocationSample(
                        timestamp = now,
                        loggedAt = now,
                        latitude = latitude,
                        longitude = -115.2,
                        altitudeMeters = 610.0,
                        confidence = 0.9,
                        isGenuine = true,
                        isMock = false,
                        capturePipeline = "HIGH_DETAIL",
                        captureSource = "MANUAL",
                    )
                }

            useCase.restore(
                V2RestoreBundle(
                    manifestJson = ArchiveJson.document.encodeToString(ArchiveManifest.serializer(), manifest),
                    locationHistoryJsonLines =
                        samples.joinToString(separator = "") {
                            ArchiveJson.line.encodeToString(ArchiveLocationSample.serializer(), it) + "\n"
                        },
                ),
            )

            val restored = locationHistoryRepo.entries
            assertEquals(2, restored.size)
            assertTrue(restored.all { it.userId == RESTORE_TEST_OWNER_ID }, "owners: ${restored.map { it.userId }}")
            assertTrue(restored.all { it.deviceId == RESTORE_TEST_DEVICE_ID.toString() }, "devices: ${restored.map { it.deviceId }}")
        }
}
