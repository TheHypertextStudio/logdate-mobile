package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportLocation
import app.logdate.client.domain.export.ExportLocationHistoryItem
import app.logdate.client.domain.export.ExportNote
import app.logdate.client.domain.export.ExportPlace
import app.logdate.client.domain.restore.IntegrityCategory
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.shared.model.Place
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class ArchiveProfileHistoryRestoreTest : RestoreArchiveTestFixture() {
    @Test
    fun `restore imports profile places and location history from optional payloads`() =
        runTest {
            val placeId = Uuid.random()
            val profile =
                LogDateProfile(
                    displayName = "Alice",
                    bio = "Writes a lot",
                    birthday = now - 10.days,
                )
            val bundle =
                buildBundle(
                    profile = profile,
                    places =
                        listOf(
                            ExportPlace(
                                id = placeId.toString(),
                                displayName = "Home",
                                latitude = 37.7749,
                                longitude = -122.4194,
                                radiusMeters = 125.0,
                                description = "Apartment",
                            ),
                        ),
                    locationHistory =
                        listOf(
                            ExportLocationHistoryItem(
                                sampleId = "sample-1",
                                userId = "user-1",
                                deviceId = "device-1",
                                timestamp = now,
                                loggedAt = now,
                                latitude = 37.7749,
                                longitude = -122.4194,
                                altitudeMeters = 12.0,
                                confidence = 0.8f,
                                isGenuine = true,
                                capturePipeline = "HIGH_DETAIL",
                                captureSource = "MANUAL",
                                accuracyMeters = 6.5f,
                            ),
                        ),
                )

            useCase.restore(bundle)

            assertEquals("Alice", profileRepo.profile.displayName)
            assertEquals("Writes a lot", profileRepo.profile.bio)
            assertEquals(1, placesRepo.places.size)
            assertEquals(placeId, (placesRepo.places.first() as Place.UserDefined).id)
            assertEquals(1, locationHistoryRepo.entries.size)
            assertEquals("sample-1", locationHistoryRepo.entries.first().sampleId)
            assertEquals(LocationCapturePipeline.HIGH_DETAIL, locationHistoryRepo.entries.first().capturePipeline)
        }

    @Test
    fun `restore location history does not scan all existing history before import`() =
        runTest {
            locationHistoryRepo.throwOnGetAllLocationHistory = true

            val bundle =
                buildBundle(
                    locationHistory =
                        listOf(
                            ExportLocationHistoryItem(
                                sampleId = "sample-1",
                                userId = "user-1",
                                deviceId = "device-1",
                                timestamp = now,
                                loggedAt = now,
                                latitude = 37.7749,
                                longitude = -122.4194,
                                altitudeMeters = 12.0,
                                confidence = 0.8f,
                                isGenuine = true,
                                capturePipeline = "HIGH_DETAIL",
                                captureSource = "MANUAL",
                            ),
                        ),
                )

            val result = useCase.restore(bundle)

            assertEquals(0, locationHistoryRepo.getAllLocationHistoryCallCount)
            assertEquals(1, locationHistoryRepo.logLocationCallCount)
            assertEquals(1, locationHistoryRepo.entries.size)
            assertTrue(result.warnings.isEmpty())
        }

    @Test
    fun `restore profile skips unchanged updates`() =
        runTest {
            val profile =
                LogDateProfile(
                    displayName = "Alice",
                    bio = "Writes a lot",
                    originalBio = "Writes a lot",
                    birthday = now - 10.days,
                    lastUpdatedAt = now,
                )
            profileRepo.profile = profile

            val result = useCase.restore(buildBundle(profile = profile))

            assertEquals(0, profileRepo.updateDisplayNameCallCount)
            assertEquals(0, profileRepo.updateBirthdayCallCount)
            assertEquals(0, profileRepo.updateProfilePhotoCallCount)
            assertEquals(0, profileRepo.updateBioCallCount)
            assertTrue(result.warnings.isEmpty())
        }

    @Test
    fun `automatic Cloud restore preserves populated local profile fields`() =
        runTest {
            val local =
                LogDateProfile(
                    displayName = "Local name",
                    birthday = Instant.parse("1992-06-03T00:00:00Z"),
                    profilePhotoUri = "local-photo",
                    bio = "Local display bio",
                    originalBio = "Local original words",
                    lastUpdatedAt = now - 10.days,
                )
            val cloud =
                LogDateProfile(
                    displayName = "Cloud name",
                    birthday = Instant.parse("1991-01-01T00:00:00Z"),
                    profilePhotoUri = "cloud-photo",
                    bio = "Cloud display bio",
                    originalBio = "Cloud original words",
                    lastUpdatedAt = now,
                )
            profileRepo.profile = local

            useCase.restore(
                buildBundle(profile = cloud),
                RestoreOptions(preservePopulatedLocalProfile = true),
            )

            assertEquals(local.displayName, profileRepo.profile.displayName)
            assertEquals(local.birthday, profileRepo.profile.birthday)
            assertEquals(local.profilePhotoUri, profileRepo.profile.profilePhotoUri)
            assertEquals(local.bio, profileRepo.profile.bio)
            assertEquals(local.originalBio, profileRepo.profile.originalBio)
        }

    @Test
    fun `automatic Cloud restore fills blank local profile fields independently`() =
        runTest {
            profileRepo.profile =
                LogDateProfile(
                    displayName = "Local name",
                    bio = "Local display bio",
                    lastUpdatedAt = now,
                )
            val cloud =
                LogDateProfile(
                    displayName = "Cloud name",
                    birthday = Instant.parse("1992-06-03T00:00:00Z"),
                    profilePhotoUri = "cloud-photo",
                    bio = "Cloud display bio",
                    originalBio = "Cloud original words",
                    lastUpdatedAt = now - 10.days,
                )

            useCase.restore(
                buildBundle(profile = cloud),
                RestoreOptions(preservePopulatedLocalProfile = true),
            )

            assertEquals("Local name", profileRepo.profile.displayName)
            assertEquals(cloud.birthday, profileRepo.profile.birthday)
            assertEquals("cloud-photo", profileRepo.profile.profilePhotoUri)
            assertEquals("Local display bio", profileRepo.profile.bio)
            assertEquals("Cloud original words", profileRepo.profile.originalBio)
        }

    // endregion

    // region Merge strategies

    @Test
    fun `notes with location data preserve coordinates`() =
        runTest {
            val noteId = Uuid.random()
            val bundle =
                buildBundle(
                    notes =
                        listOf(
                            ExportNote(
                                id = noteId.toString(),
                                type = "text",
                                content = "At the park",
                                createdAt = now,
                                updatedAt = now,
                                location =
                                    ExportLocation(
                                        latitude = 37.7749,
                                        longitude = -122.4194,
                                        placeName = "San Francisco",
                                    ),
                            ),
                        ),
                )

            val result = useCase.restore(bundle)

            assertEquals(1, result.notesImported)
            val restored = notesRepo.created.first() as JournalNote.Text
            assertEquals(37.7749, restored.location?.coordinates?.latitude)
            assertEquals(-122.4194, restored.location?.coordinates?.longitude)
            assertEquals("San Francisco", restored.location?.displayName)
        }

    @Test
    fun `restore preserves location altitude and accuracy`() =
        runTest {
            val noteId = Uuid.random()
            val bundle =
                buildBundle(
                    notes =
                        listOf(
                            ExportNote(
                                id = noteId.toString(),
                                type = "text",
                                content = "At the summit",
                                createdAt = now,
                                updatedAt = now,
                                location =
                                    ExportLocation(
                                        latitude = 37.7749,
                                        longitude = -122.4194,
                                        placeName = "Twin Peaks",
                                        altitude = 282.0,
                                        accuracy = 4.5f,
                                    ),
                            ),
                        ),
                )

            val result = useCase.restore(bundle)

            assertEquals(1, result.notesImported)
            val restored = notesRepo.created.first()
            assertIs<JournalNote.Text>(restored)
            val coords = restored.location?.coordinates
            assertEquals(282.0, coords?.altitude)
            assertEquals(4.5f, coords?.accuracy)
        }
}
