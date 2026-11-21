package app.logdate.client.media.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidMediaClaimFenceTest {
    @Test
    fun `a live claim blocks the final reclamation check across fence instances`() =
        runBlocking {
            val root = Files.createTempDirectory("media-claim-fence-").toFile()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val attempted = CompletableDeferred<Unit>()
                val claim =
                    async {
                        AndroidMediaClaimFence(root).withClaim {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                entered.await()
                val collector =
                    async {
                        attempted.complete(Unit)
                        AndroidMediaClaimFence(root).withExclusiveReclamation { true }
                    }
                attempted.await()
                assertNull(withTimeoutOrNull(100) { collector.await() })
                assertFalse(collector.isCompleted)
                release.complete(Unit)
                withTimeout(5_000) { claim.await() }
                assertTrue(withTimeout(5_000) { collector.await() })
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `a claim waiting on another claim's work does not freeze`() =
        runBlocking {
            val root = Files.createTempDirectory("media-claim-fence-").toFile()
            val unrelatedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val result =
                    withTimeoutOrNull(5_000) {
                        AndroidMediaClaimFence(root).withClaim {
                            unrelatedScope.async { AndroidMediaClaimFence(root).withClaim { true } }.await()
                        }
                    }
                assertEquals(true, result)
            } finally {
                unrelatedScope.cancel()
                root.deleteRecursively()
            }
        }

    @Test
    fun `reclamation waits for every overlapping claim and then blocks new ones`() =
        runBlocking {
            val root = Files.createTempDirectory("media-claim-fence-").toFile()
            try {
                val firstEntered = CompletableDeferred<Unit>()
                val secondEntered = CompletableDeferred<Unit>()
                val releaseFirst = CompletableDeferred<Unit>()
                val releaseSecond = CompletableDeferred<Unit>()
                val first =
                    async {
                        AndroidMediaClaimFence(root).withClaim {
                            firstEntered.complete(Unit)
                            releaseFirst.await()
                        }
                    }
                firstEntered.await()
                val second =
                    async {
                        AndroidMediaClaimFence(root).withClaim {
                            secondEntered.complete(Unit)
                            releaseSecond.await()
                        }
                    }
                withTimeout(5_000) { secondEntered.await() }
                val collectorEntered = CompletableDeferred<Unit>()
                val releaseCollector = CompletableDeferred<Unit>()
                val collector =
                    async {
                        AndroidMediaClaimFence(root).withExclusiveReclamation {
                            collectorEntered.complete(Unit)
                            releaseCollector.await()
                        }
                    }
                releaseFirst.complete(Unit)
                withTimeout(5_000) { first.await() }
                assertNull(withTimeoutOrNull(100) { collectorEntered.await() })
                releaseSecond.complete(Unit)
                withTimeout(5_000) { second.await() }
                withTimeout(5_000) { collectorEntered.await() }
                val blockedClaim = async { AndroidMediaClaimFence(root).withClaim { true } }
                assertNull(withTimeoutOrNull(100) { blockedClaim.await() })
                releaseCollector.complete(Unit)
                withTimeout(5_000) { collector.await() }
                assertTrue(withTimeout(5_000) { blockedClaim.await() })
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `a collector blocks a new claim and nested claims reuse their fence`() =
        runBlocking {
            val root = Files.createTempDirectory("media-claim-fence-").toFile()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val attempted = CompletableDeferred<Unit>()
                val collector =
                    async {
                        AndroidMediaClaimFence(root).withExclusiveReclamation {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                entered.await()
                val claim =
                    async {
                        attempted.complete(Unit)
                        AndroidMediaClaimFence(root).withClaim {
                            AndroidMediaClaimFence(root).withClaim { true }
                        }
                    }
                attempted.await()
                assertNull(withTimeoutOrNull(100) { claim.await() })
                assertFalse(claim.isCompleted)
                release.complete(Unit)
                withTimeout(5_000) { collector.await() }
                assertTrue(withTimeout(5_000) { claim.await() })
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `cancelling a waiting collector leaves the claim fence usable`() =
        runBlocking {
            val root = Files.createTempDirectory("media-claim-fence-").toFile()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val claim =
                    async {
                        AndroidMediaClaimFence(root).withClaim {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                entered.await()
                val attempted = CompletableDeferred<Unit>()
                val collector =
                    async {
                        attempted.complete(Unit)
                        AndroidMediaClaimFence(root).withExclusiveReclamation { Unit }
                    }
                attempted.await()
                assertNull(withTimeoutOrNull(100) { collector.await() })
                collector.cancelAndJoin()
                release.complete(Unit)
                withTimeout(5_000) { claim.await() }
                assertTrue(withTimeout(5_000) { AndroidMediaClaimFence(root).withClaim { true } })
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `cancelling while another channel owns the OS lock does not wait for its release`() =
        runBlocking {
            val root = Files.createTempDirectory("media-claim-fence-").toFile()
            val directory = root.resolve("media-reclamation").also { it.mkdirs() }
            val path = directory.resolve(".claims.lock").toPath()
            try {
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                    channel.lock().use {
                        val started = CompletableDeferred<Unit>()
                        val claim =
                            async {
                                started.complete(Unit)
                                AndroidMediaClaimFence(root).withClaim { true }
                            }
                        started.await()
                        assertNull(withTimeoutOrNull(100) { claim.await() })
                        withTimeout(5_000) { claim.cancelAndJoin() }
                    }
                }
                assertTrue(withTimeout(5_000) { AndroidMediaClaimFence(root).withClaim { true } })
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `a symlinked lock directory is refused`() =
        runBlocking {
            val root = Files.createTempDirectory("media-claim-fence-").toFile()
            val outside = Files.createTempDirectory("media-claim-outside-").toFile()
            try {
                Files.createSymbolicLink(root.resolve("media-reclamation").toPath(), outside.toPath())
                assertFailsWith<IllegalStateException> { AndroidMediaClaimFence(root).withClaim { Unit } }
                assertTrue(outside.listFiles().isNullOrEmpty())
            } finally {
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }
}
