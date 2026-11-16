package app.logdate.client.data.notes

import app.logdate.client.data.fakes.FakeAudioNoteDao
import app.logdate.client.data.fakes.FakeImageNoteDao
import app.logdate.client.data.fakes.FakeJournalContentDao
import app.logdate.client.data.fakes.FakeMediaCaptionDao
import app.logdate.client.data.fakes.FakeTextNoteDao
import app.logdate.client.data.fakes.FakeVideoNoteDao
import app.logdate.client.database.dao.MediaCaptionDao
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.MediaCaptionEntity
import app.logdate.client.database.entities.TextNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class JournalNotePaginationBoundsTest {
    @Test
    fun `observeNotesPage slices entity buckets to pageSize before querying captions at deep offsets`() =
        runTest {
            val images = FakeImageNoteDao()
            val delegateCaptions = FakeMediaCaptionDao()
            repeat(650) { index ->
                val uid = Uuid.random()
                val created = Instant.fromEpochMilliseconds((index + 1) * 1_000L)
                images.addNote(
                    ImageNoteEntity(
                        contentUri = "file:///photo_$index.jpg",
                        uid = uid,
                        created = created,
                        lastUpdated = created,
                    ),
                )
                delegateCaptions.upsertCaption(MediaCaptionEntity(uid, "Caption $index"))
            }

            var observeAllCalls = 0
            val observedBatchSizes = mutableListOf<Int>()
            val captions =
                object : MediaCaptionDao by delegateCaptions {
                    override fun observeAll(): Flow<List<MediaCaptionEntity>> =
                        flow {
                            observeAllCalls++
                            emitAll(delegateCaptions.observeAll())
                        }

                    override fun observeCaptions(noteIds: List<Uuid>): Flow<List<MediaCaptionEntity>> {
                        observedBatchSizes += noteIds.size
                        return delegateCaptions.observeCaptions(noteIds)
                    }
                }

            val queries =
                JournalNoteQueries(
                    FakeTextNoteDao(),
                    images,
                    FakeAudioNoteDao(),
                    FakeVideoNoteDao(),
                    FakeJournalContentDao(),
                    captions,
                    EmptyNotePlaceResolver,
                    null,
                )

            val page = queries.observeNotesPage(pageSize = 20, offset = 600).first()

            assertEquals(20, page.size)
            assertEquals(0, observeAllCalls, "Paged query should scope caption lookup to pageSize instead of scanning all captions")
            assertEquals(listOf(20), observedBatchSizes)
        }

    @Test
    fun `getNotesBefore batches caption lookup into a single query for the sliced page`() =
        runTest {
            val texts = FakeTextNoteDao()
            val images = FakeImageNoteDao()
            val delegateCaptions = FakeMediaCaptionDao()

            repeat(30) { index ->
                val created = Instant.fromEpochMilliseconds((index + 1) * 1_000L)
                texts.addNote(
                    TextNoteEntity(
                        content = "Text $index",
                        created = created,
                        lastUpdated = created,
                    ),
                )
                val imageUid = Uuid.random()
                val imageCreated = Instant.fromEpochMilliseconds((index + 1) * 1_000L + 500L)
                images.addNote(
                    ImageNoteEntity(
                        contentUri = "file:///img_$index.jpg",
                        uid = imageUid,
                        created = imageCreated,
                        lastUpdated = imageCreated,
                    ),
                )
                delegateCaptions.upsertCaption(MediaCaptionEntity(imageUid, "Caption $index"))
            }

            var singleLookupCalls = 0
            var batchLookupCalls = 0
            val captions =
                object : MediaCaptionDao by delegateCaptions {
                    override suspend fun getCaption(noteId: Uuid): MediaCaptionEntity? {
                        singleLookupCalls++
                        return delegateCaptions.getCaption(noteId)
                    }

                    override suspend fun getCaptions(noteIds: List<Uuid>): List<MediaCaptionEntity> {
                        batchLookupCalls++
                        return delegateCaptions.getCaptions(noteIds)
                    }
                }

            val queries =
                JournalNoteQueries(
                    texts,
                    images,
                    FakeAudioNoteDao(),
                    FakeVideoNoteDao(),
                    FakeJournalContentDao(),
                    captions,
                    EmptyNotePlaceResolver,
                    null,
                )

            val result = queries.getNotesBefore(beforeExclusive = Instant.fromEpochMilliseconds(100_000L), limit = 20)

            assertEquals(20, result.size)
            assertEquals(0, singleLookupCalls, "getNotesBefore should not run N individual caption lookups")
            assertEquals(1, batchLookupCalls, "getNotesBefore should fetch captions in a single batch query")
            assertTrue(
                result.filterIsInstance<app.logdate.client.repository.journals.JournalNote.Image>().all {
                    it.caption.startsWith(
                        "Caption ",
                    )
                },
            )
        }
}
