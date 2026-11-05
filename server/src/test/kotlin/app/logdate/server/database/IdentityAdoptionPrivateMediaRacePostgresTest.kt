@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.logdate.FilesystemLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class IdentityAdoptionPrivateMediaRacePostgresTest {
    @Test fun aSignedDiscardFencesARealInFlightWriterAndReleasesQuota() = race(canceled = false)

    @Test fun canceledOperationMaintenanceFencesTheOldWriterWithoutItsDeviceProof() = race(canceled = true)

    private fun race(canceled: Boolean) {
        for (filesystem in listOf(false, true)) {
            withPrivateAdoptionMedia {
                val root = Files.createTempDirectory("adoption-private-race-")
                val provider = if (filesystem) FilesystemLogDateBlobStorage(root) else storage
                val cleanup = PostgreSQLIdentityAdoptionMediaCleanup(auth.source, provider, auth.clock)
                val ready = CompletableDeferred<Unit>()
                val proceed = CountDownLatch(1)
                val paused =
                    object : LogDateBlobStorage by provider {
                        override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter {
                            val writer = provider.openBlobWriter(request)
                            ready.complete(Unit)
                            check(proceed.await(10, TimeUnit.SECONDS))
                            return writer
                        }
                    }
                try {
                    assertNotNull(begin())
                    coroutineScope {
                        val writing = async(Dispatchers.IO) { runCatching { write(provider = paused) } }
                        withTimeout(5000) { ready.await() }
                        try {
                            if (canceled) {
                                transition(IdentityAdoptionPhase.CANCELED)
                                assertTrue(cleanup.cleanup(upload.uploadId))
                            } else {
                                val discard = ticket("discard")
                                val receipt =
                                    assertNotNull(
                                        ingress(provider).discard(
                                            current,
                                            discard,
                                            auth.actionSignature(discard, current),
                                            upload.uploadId,
                                        ),
                                    )
                                assertEquals("RETIRED", receipt.state)
                                assertEquals("RETIRED", assertNotNull(begin()).state)
                            }
                            assertEquals(0L, used())
                        } finally {
                            proceed.countDown()
                        }
                        assertNotNull(writing.await().exceptionOrNull())
                    }
                    assertEquals("RETIRED", assertNotNull(row()).phase)
                    assertNull(provider.openBlob(assertNotNull(row()).path))
                    assertTrue(cleanup.cleanup(upload.uploadId))
                    assertEquals(0L, used())
                    if (!canceled) assertNotNull(begin(upload.copy(uploadId = Uuid.random().toString())))
                } finally {
                    proceed.countDown()
                    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }
    }
}
