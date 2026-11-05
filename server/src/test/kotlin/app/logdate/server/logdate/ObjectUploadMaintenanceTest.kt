@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.logdate

import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.installObjectUploadMaintenance
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ObjectUploadMaintenanceTest {
    @Test fun applicationStartsRecoveryAndJoinsItWhenTheServerStops() {
        val calls = AtomicInteger()
        val checked = CompletableDeferred<Unit>()
        val uploads =
            object : ObjectUploads by ObjectUploads.Direct {
                override fun pendingCleanup(after: String?): List<ObjectUpload> {
                    calls.incrementAndGet()
                    checked.complete(Unit)
                    return emptyList()
                }
            }
        val maintenance = ObjectUploadMaintenance(uploads, InMemoryLogDateBlobStorage(), intervalMillis = 1)
        testApplication {
            application { installObjectUploadMaintenance(maintenance) }
            startApplication()
            withTimeout(5000) { checked.await() }
        }
        assertTrue(calls.get() > 0)
        assertFailsWith<IllegalStateException> { maintenance.start() }
    }

    @Test fun fairBoundedRecoveryContinuesPastFailedObjectsAndClosesItsOnlyWorker() =
        runBlocking<Unit> {
            val owner = IdentityWriteExpectation(Uuid.random(), 0)
            val tickets =
                (0..250).map {
                    ObjectUpload("ticket-${it.toString().padStart(3, '0')}", owner, null, "synthetic/$it", ObjectUploadKind.BACKUP)
                }
            val seen = ConcurrentLinkedQueue<String>()
            val finished = CompletableDeferred<Unit>()
            val uploads =
                object : ObjectUploads by ObjectUploads.Direct {
                    override fun pendingCleanup(after: String?): List<ObjectUpload> =
                        tickets
                            .filter { after == null || it.id > after }
                            .take(100)

                    override fun recover(
                        upload: ObjectUpload,
                        deleteObject: (String) -> Boolean,
                    ): Boolean {
                        seen.add(upload.id)
                        if (upload.id == tickets.last().id) finished.complete(Unit)
                        return false
                    }
                }
            val maintenance = ObjectUploadMaintenance(uploads, InMemoryLogDateBlobStorage(), intervalMillis = 1)
            try {
                maintenance.start()
                maintenance.start()
                withTimeout(5000) { finished.await() }
            } finally {
                maintenance.close()
            }
            assertEquals(tickets.map { it.id }.toSet(), seen.toSet())
            assertTrue(seen.size >= tickets.size)
            assertFailsWith<IllegalStateException> { maintenance.start() }
        }
}
