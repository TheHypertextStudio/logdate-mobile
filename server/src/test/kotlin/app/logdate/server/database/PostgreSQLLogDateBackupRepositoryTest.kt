@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.database.support.withH2Database
import app.logdate.server.logdate.LogDateBackup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Integration tests for [PostgreSQLLogDateBackupRepository] using an H2 in-memory database.
 *
 * Verifies the full lifecycle of backup metadata management, including creation,
 * retrieval (both individual and list-based), and deletion. These tests ensure
 * that backup records are correctly associated with users and devices.
 */
class PostgreSQLLogDateBackupRepositoryTest {
    @Test
    fun `postgres backup repository stores lists and deletes backups`() {
        withH2Database(AccountsTable, LogDateBackupsTable) {
            val repository = PostgreSQLLogDateBackupRepository()
            val userId = Uuid.random().toJavaUUID()
            createTestAccount(userId)
            val firstBackup =
                LogDateBackup(
                    id = Uuid.random().toJavaUUID(),
                    userId = userId,
                    deviceId = "device-a",
                    manifest = """{"v":1}""",
                    storagePath = "users/$userId/backups/one.enc",
                    createdAt = 10L,
                    sizeBytes = 100L,
                )
            val secondBackup =
                LogDateBackup(
                    id = Uuid.random().toJavaUUID(),
                    userId = userId,
                    deviceId = "device-b",
                    manifest = """{"v":2}""",
                    storagePath = "users/$userId/backups/two.enc",
                    createdAt = 20L,
                    sizeBytes = 200L,
                )

            repository.createBackup(userId, firstBackup)
            repository.createBackup(userId, secondBackup)

            assertEquals(secondBackup.id, repository.listBackups(userId).first().id)
            assertEquals(firstBackup.id, repository.getBackup(userId, firstBackup.id)?.id)

            repository.deleteBackup(userId, firstBackup.id)

            assertNull(repository.getBackup(userId, firstBackup.id))
            assertEquals(listOf(secondBackup.id), repository.listBackups(userId).map(LogDateBackup::id))
        }
    }
}
