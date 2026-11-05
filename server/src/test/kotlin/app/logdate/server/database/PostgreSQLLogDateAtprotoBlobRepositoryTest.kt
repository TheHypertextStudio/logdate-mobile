@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.database.support.withH2Database
import app.logdate.server.logdate.LogDateAtprotoBlob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

/**
 * Integration tests for [PostgreSQLLogDateAtprotoBlobRepository] using an H2 database.
 *
 * Verifies that AT Protocol blob metadata, identified by Content Identifiers (CIDs),
 * can be stored, updated, and retrieved correctly. This ensures that the server
 * accurately tracks the association between users, blobs, and their physical storage locations.
 */
class PostgreSQLLogDateAtprotoBlobRepositoryTest {
    @Test
    fun `postgres atproto blob repository stores and updates blob metadata`() {
        withH2Database(AccountsTable, LogDateAtprotoBlobsTable) {
            val repository = PostgreSQLLogDateAtprotoBlobRepository()
            val userId = Uuid.random().toJavaUUID()
            createTestAccount(userId)

            repository.upsertBlob(
                userId = userId,
                blob =
                    LogDateAtprotoBlob(
                        cid = "bafk-blob",
                        userId = userId,
                        mimeType = "image/jpeg",
                        sizeBytes = 3L,
                        storagePath = "users/$userId/atproto/blobs/bafk-blob",
                        createdAt = 10L,
                    ),
            )

            repository.upsertBlob(
                userId = userId,
                blob =
                    LogDateAtprotoBlob(
                        cid = "bafk-blob",
                        userId = userId,
                        mimeType = "image/png",
                        sizeBytes = 4L,
                        storagePath = "users/$userId/atproto/blobs/bafk-blob",
                        createdAt = 11L,
                    ),
            )

            val fetched = repository.getBlob(userId, "bafk-blob")

            assertNotNull(fetched)
            assertEquals("image/png", fetched.mimeType)
            assertEquals(4L, fetched.sizeBytes)
            assertEquals(11L, fetched.createdAt)
        }
    }
}
