@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AccountCredentialMutationRuntimeTest {
    @Test
    fun `an in memory server uses local coordination without allocating database resources`() =
        runBlocking {
            val runtime =
                createAccountCredentialMutationRuntime(
                    false,
                    sourceProvider = { error("No application database lookup is needed") },
                    poolFactory = { error("No database pool is needed") },
                )
            try {
                assertEquals("local", runtime.lock.withLock(Uuid.random()) { "local" })
            } finally {
                runtime.close()
            }
        }

    @Test
    fun `the database runtime owns a separate bounded lazy pool and preserves the application pool`() {
        HikariDataSource().use { app ->
            val owned = HikariDataSource()
            val runtime = createAccountCredentialMutationRuntime(true, { app }, { owned })
            try {
                assertEquals(0, owned.minimumIdle)
                assertEquals(2, owned.maximumPoolSize)
                assertTrue(owned.connectionTimeout <= 5000)
                assertTrue(owned.validationTimeout <= 2000)
                assertFalse(owned.isClosed)
            } finally {
                runtime.close()
                runtime.close()
            }
            assertTrue(owned.isClosed)
            assertFalse(app.isClosed)
        }
    }

    @Test
    fun `a missing initialized application database fails before lock pool allocation`() {
        var allocated = false
        assertFailsWith<IllegalStateException> {
            createAccountCredentialMutationRuntime(true, { null }, {
                allocated = true
                HikariDataSource()
            })
        }
        assertFalse(allocated)
    }

    @Test
    fun `a borrowed application pool cannot be claimed or closed as a lock pool`() {
        HikariDataSource().use { borrowed ->
            assertFailsWith<IllegalArgumentException> { createAccountCredentialMutationRuntime(true, { borrowed }, { borrowed }) }
            assertFalse(borrowed.isClosed)
        }
    }

    @Test
    fun `pool setup failure preserves the application database`() {
        HikariDataSource().use { app ->
            assertFailsWith<IllegalStateException> {
                createAccountCredentialMutationRuntime(true, { app }, { error("Synthetic lock pool setup failure") })
            }
            assertFalse(app.isClosed)
        }
    }
}
