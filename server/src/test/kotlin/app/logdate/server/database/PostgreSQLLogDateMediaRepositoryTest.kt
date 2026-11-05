@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.database.support.withH2Database
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.sync.StaleWriteException
import app.logdate.shared.model.sync.DeviceId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Integration tests for [PostgreSQLLogDateMediaRepository] utilizing an H2 database.
 *
 * This suite validates the persistence and management of media records, specifically:
 * - Idempotent upsert operations (creation and updates).
 * - Media record retrieval by unique identifiers.
 * - Graceful deletion (tombstoning) of media content.
 *
 * It ensures that media-specific metadata, such as encryption details and device associations,
 * are correctly handled.
 */
class PostgreSQLLogDateMediaRepositoryTest {
    @Test
    fun `receipt records choose one committed version and cannot resurrect a deletion`() {
        withH2Database(AccountsTable, LogDateMediaRecordsTable) {
            val repository = PostgreSQLLogDateMediaRepository()
            val owner = Uuid.random().toJavaUUID()
            createTestAccount(owner)
            val media =
                LogDateMedia(
                    "receipt-v1-test",
                    "entry",
                    owner,
                    "photo.jpg",
                    "image/jpeg",
                    3,
                    byteArrayOf(1, 2, 3),
                    null,
                    10,
                    0,
                    DeviceId("device"),
                )
            val first = repository.upsertMedia(owner, media)
            val replay = repository.upsertMedia(owner, media.copy(data = byteArrayOf(4, 5, 6)))
            assertContentEquals(first.data, replay.data)
            assertEquals(first.version, replay.version)
            assertNull(repository.getMedia(Uuid.random().toJavaUUID(), media.mediaId))
            repository.deleteMedia(owner, media.mediaId, 30)
            assertFailsWith<StaleWriteException> { repository.upsertMedia(owner, media) }
        }
    }

    @Test
    fun `postgres media repository stores updates and tombstones media`() {
        withH2Database(AccountsTable, LogDateMediaRecordsTable) {
            val repository = PostgreSQLLogDateMediaRepository()
            val userId = Uuid.random().toJavaUUID()
            createTestAccount(userId)

            val created =
                repository.upsertMedia(
                    userId = userId,
                    media =
                        LogDateMedia(
                            mediaId = "media-1",
                            contentId = "entry-1",
                            userId = userId,
                            fileName = "photo.jpg",
                            mimeType = "image/jpeg",
                            sizeBytes = 3L,
                            data = byteArrayOf(1, 2, 3),
                            storagePath = null,
                            createdAt = 10L,
                            version = 0L,
                            deviceId = DeviceId("device-1"),
                            encryptionVersion = 1,
                            encryptionKeyId = "default",
                            encryptionMode = "SERVER",
                        ),
                )

            val updated =
                repository.upsertMedia(
                    userId = userId,
                    media = created.copy(fileName = "photo-updated.jpg"),
                )
            val fetched = repository.getMedia(userId, "media-1")

            assertNotNull(fetched)
            assertEquals("photo-updated.jpg", fetched.fileName)
            assertTrue(updated.version >= created.version)

            repository.deleteMedia(userId, "media-1", deletedAt = 30L)

            assertNull(repository.getMedia(userId, "media-1"))
        }
    }
}
