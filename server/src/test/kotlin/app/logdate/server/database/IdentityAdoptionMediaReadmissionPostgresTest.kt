@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class IdentityAdoptionMediaReadmissionPostgresTest {
    @Test fun evictedCoverageRequiresFreshReadbackOfTheSameDurableCipherTicket() =
        withPrivateAdoptionMedia {
            val original = assertNotNull(begin())
            assertNotNull(write())
            assertNotNull(verify())
            val path = assertNotNull(row()).path
            auth.clock.time += 8.days
            assertTrue(PostgreSQLIdentityAdoptionStagingCleanup(auth.source, auth.clock).cleanup(current.id))
            current = assertNotNull(auth.operations.status(current.binding.accountId, current.id)).operation
            val resumed = assertNotNull(begin())
            assertTrue(resumed.generation > original.generation)
            assertEquals("STORED", resumed.state)
            assertEquals(upload.size, used())
            assertNotNull(verify())
            assertEquals("VERIFIED", assertNotNull(begin()).state)
            assertEquals(path, assertNotNull(row()).path)
            assertEquals(upload.size, used())
        }
}
