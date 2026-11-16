package app.logdate.server

import app.logdate.server.database.PostgreSQLMaintenanceLease
import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.logging.MaintenanceNotices
import app.logdate.server.logging.safeServerMessage
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.sync.SyncPurgeResult
import app.logdate.server.sync.SyncRepository
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Every instance starts the same maintenance loop. A lease lets only one of them run a pass at a time,
 * and a random wait before the first pass spreads out instances that start together.
 */
class SyncMaintenanceLeaseTest {
    private class CountingRepository(
        val purged: CompletableDeferred<Unit> = CompletableDeferred(),
        val onPurge: () -> Unit = {},
    ) : SyncRepository by InMemorySyncRepository() {
        var purges = 0

        override fun purgeTombstonesOlderThan(olderThan: Long): SyncPurgeResult {
            purges++
            onPurge()
            purged.complete(Unit)
            return SyncPurgeResult(0, 0, 0, 0, olderThan)
        }
    }

    private fun start(
        repository: SyncRepository,
        lease: MaintenanceLease,
        startJitter: (Duration) -> Duration = { Duration.ZERO },
    ): Job =
        requireNotNull(
            startSyncMaintenance(
                collectionsRepository = repository.asLogDateCollectionsRepository(),
                metrics = SyncMetricsRegistry(),
                readEnv = { null },
                lease = lease,
                startJitter = startJitter,
            ),
        )

    private fun Job.stop() = runBlocking { cancelAndJoin() }

    @Test
    fun `a pass another instance is running is skipped`() {
        val asked = CompletableDeferred<Unit>()
        val repository = CountingRepository()
        val job =
            start(repository, lease = { _ ->
                asked.complete(Unit)
                false
            })
        try {
            runBlocking { withTimeout(5_000) { asked.await() } }
            assertEquals(0, repository.purges)
        } finally {
            job.stop()
        }
    }

    @Test
    fun `a pass runs while this instance holds the lease`() {
        var held = false
        var heldDuringPurge = false
        val repository = CountingRepository(onPurge = { heldDuringPurge = held })
        val job =
            start(repository, lease = { pass ->
                held = true
                try {
                    pass()
                } finally {
                    held = false
                }
                true
            })
        try {
            runBlocking { withTimeout(5_000) { repository.purged.await() } }
            assertTrue(heldDuringPurge)
        } finally {
            job.stop()
        }
    }

    @Test
    fun `a lease that cannot be checked is logged under its own code`() {
        val logged = CompletableDeferred<Pair<LogLevel, String?>>()
        val antilog =
            object : Antilog() {
                override fun performLog(
                    priority: LogLevel,
                    tag: String?,
                    throwable: Throwable?,
                    message: String?,
                ) {
                    if (priority == LogLevel.ERROR) logged.complete(priority to message)
                }
            }
        val repository = CountingRepository()
        Napier.base(antilog)
        val job = start(repository, lease = { _ -> error("database unavailable") })
        try {
            val (priority, message) = runBlocking { withTimeout(5_000) { logged.await() } }

            assertEquals(LogLevel.ERROR to MaintenanceNotices.LEASE_FAILED, priority to message)
            assertEquals("SERVER_MAINTENANCE_LEASE_FAILED", safeServerMessage(priority, tag = null, message = message))
            assertEquals(0, repository.purges)
        } finally {
            job.stop()
            Napier.takeLogarithm(antilog)
        }
    }

    @Test
    fun `the first pass waits for the start delay`() {
        val repository = CountingRepository()
        val started = TimeSource.Monotonic.markNow()
        val job = start(repository, MaintenanceLease.Local, startJitter = { 300.milliseconds })
        try {
            runBlocking { withTimeout(5_000) { repository.purged.await() } }
            assertTrue(started.elapsedNow() >= 300.milliseconds)
        } finally {
            job.stop()
        }
    }

    @Test
    fun `the start delay is shorter than the interval and five minutes`() {
        val random = Random(7)
        repeat(1_000) {
            assertTrue(randomStartDelay(2.minutes, random) in Duration.ZERO..<2.minutes)
            assertTrue(randomStartDelay(60.minutes, random) in Duration.ZERO..<5.minutes)
        }
        assertEquals(Duration.ZERO, randomStartDelay(Duration.ZERO, random))
    }

    @Test
    fun `only one instance sharing the database holds the lease at a time`() {
        localTestPostgresDataSource().use { database ->
            val name = "maintenance-test-${UUID.randomUUID()}"
            val first = PostgreSQLMaintenanceLease(name, database)
            val second = PostgreSQLMaintenanceLease(name, database)
            var secondRanInside = true
            var secondRanAfter = false

            runBlocking {
                assertTrue(first.runExclusively { secondRanInside = second.runExclusively { } })
                secondRanAfter = second.runExclusively { }
            }

            assertFalse(secondRanInside, "a second instance waits for the pass that is running")
            assertTrue(secondRanAfter, "the lease is free once the pass ends")
        }
    }

    @Test
    fun `a pass that fails still frees the lease`() {
        localTestPostgresDataSource().use { database ->
            val name = "maintenance-test-${UUID.randomUUID()}"
            val lease = PostgreSQLMaintenanceLease(name, database)

            runBlocking {
                runCatching { lease.runExclusively { error("pass failed") } }
                assertTrue(PostgreSQLMaintenanceLease(name, database).runExclusively { })
            }
        }
    }
}
