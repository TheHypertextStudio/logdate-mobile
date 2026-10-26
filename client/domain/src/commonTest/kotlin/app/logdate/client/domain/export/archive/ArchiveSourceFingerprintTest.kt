package app.logdate.client.domain.export.archive

import app.logdate.client.device.AppInfo
import app.logdate.client.domain.export.archive.support.ArchiveExportFixture
import app.logdate.client.domain.export.archive.support.InMemoryArchiveContainer
import app.logdate.client.domain.export.support.StubAppInfoProvider
import app.logdate.client.domain.export.support.StubUserStateRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.SyncStorageUnavailableException
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import okio.Timeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant

class ArchiveSourceFingerprintTest : ArchiveExportFixture() {
    @Test
    fun `cancelling while reading media is not converted into an omission`() =
        runTest {
            val opener =
                MediaSourceOpener {
                    object : Source {
                        override fun read(
                            sink: Buffer,
                            byteCount: Long,
                        ): Long = throw CancellationException("Stopped")

                        override fun timeout(): Timeout = Timeout.NONE

                        override fun close() = Unit
                    }
                }
            assertFailsWith<CancellationException> {
                useCase(opener).export(ArchiveExportOptions(), InMemoryArchiveContainer()).toList()
            }
        }

    @Test
    fun `device storage failure reading media escapes the export omission path`() =
        runTest {
            val opener =
                MediaSourceOpener {
                    object : Source {
                        override fun read(
                            sink: Buffer,
                            byteCount: Long,
                        ): Long = throw SyncStorageUnavailableException()

                        override fun timeout(): Timeout = Timeout.NONE

                        override fun close() = Unit
                    }
                }
            assertFailsWith<SyncStorageUnavailableException> {
                useCase(opener).export(ArchiveExportOptions(), InMemoryArchiveContainer()).toList()
            }
        }

    @Test
    fun `device storage failure reading a category is not converted into an omission`() =
        runTest {
            val unavailable =
                object : JournalRepository by journalsRepository {
                    override val allJournalsObserved = flow<List<Journal>> { throw SyncStorageUnavailableException() }
                }
            val reader =
                ArchiveSnapshotReader(
                    unavailable,
                    notesRepository,
                    profileRepository,
                    placesRepository,
                    locationRepository,
                    StubUserStateRepository(),
                    StubAppInfoProvider(AppInfo(versionName = "1", versionCode = 1, packageName = "test")),
                )
            assertFailsWith<SyncStorageUnavailableException> { reader.read(ArchiveExportOptions()) { _, _ -> } }
        }

    @Test
    fun `device storage failure opening media escapes the export omission path`() =
        runTest {
            assertFailsWith<SyncStorageUnavailableException> {
                useCase(MediaSourceOpener { throw SyncStorageUnavailableException() })
                    .export(ArchiveExportOptions(), InMemoryArchiveContainer())
                    .toList()
            }
        }

    private suspend fun fingerprint(opener: MediaSourceOpener = readable): String {
        val completed =
            assertIs<ArchiveExportProgress.Completed>(
                useCase(opener).export(ArchiveExportOptions(), InMemoryArchiveContainer()).toList().last(),
            )
        return assertNotNull(completed.summary.sourceFingerprint)
    }

    @Test
    fun `unchanged source has a stable fingerprint`() =
        runTest {
            assertEquals(fingerprint(), fingerprint())
        }

    @Test
    fun `export time does not change content identity`() =
        runTest {
            val later =
                object : Clock {
                    override fun now(): Instant = Instant.parse("2026-10-26T15:00:00Z")
                }
            val completed =
                assertIs<ArchiveExportProgress.Completed>(
                    useCase(clock = later).export(ArchiveExportOptions(), InMemoryArchiveContainer()).toList().last(),
                )
            assertEquals(fingerprint(), assertNotNull(completed.summary.sourceFingerprint))
        }

    @Test
    fun `text edits invalidate the fingerprint without relying on timestamps`() =
        runTest {
            val before = fingerprint()
            notesRepository.testNotes = notesRepository.testNotes.map { if (it == textNote) textNote.copy(content = "Changed") else it }
            assertNotEquals(before, fingerprint())
        }

    @Test
    fun `media bytes invalidate the fingerprint at the same source reference`() =
        runTest {
            val before = fingerprint()
            val changed = Files(readable.files + (imageReference to (jpeg + byteArrayOf(1))))
            assertNotEquals(before, fingerprint(changed))
        }

    @Test
    fun `membership changes invalidate the fingerprint`() =
        runTest {
            val before = fingerprint()
            notesRepository.notesByJournal = emptyMap()
            assertNotEquals(before, fingerprint())
        }
}
