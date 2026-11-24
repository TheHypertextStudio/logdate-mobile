package app.logdate.client.sync

import android.os.Build
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SyncWorkerExecutionGateAndPolicyTest {
    @Test
    fun `execution gate rejects overlapping worker runs until released`() {
        val gate = SyncWorkerExecutionGate()

        val outerResult =
            gate.withGate(onBusy = { "busy" }) {
                val innerResult = gate.withGate(onBusy = { "busy" }) { "ran" }
                assertEquals("busy", innerResult)
                "outer"
            }

        assertEquals("outer", outerResult)
        val afterRelease = gate.withGate(onBusy = { "busy" }) { "ran_after" }
        assertEquals("ran_after", afterRelease)
    }

    @Test
    fun `execution gate releases cleanly when the guarded block throws`() {
        val gate = SyncWorkerExecutionGate()

        runCatching {
            gate.withGate(onBusy = { "busy" }) {
                error("boom")
            }
        }

        assertTrue(gate.tryAcquire())
        gate.release()
    }

    @Test
    fun `foreground promotion skips background wakes on Android 12+ unless manual or consented`() {
        assertFalse(
            SyncForegroundPolicy.shouldPromoteToForeground(
                isManualRequest = false,
                hasMobileDataConsent = false,
                sdkInt = Build.VERSION_CODES.S,
                isAppInForeground = { false },
            ),
        )
        assertTrue(
            SyncForegroundPolicy.shouldPromoteToForeground(
                isManualRequest = false,
                hasMobileDataConsent = false,
                sdkInt = Build.VERSION_CODES.S,
                isAppInForeground = { true },
            ),
        )
        assertTrue(
            SyncForegroundPolicy.shouldPromoteToForeground(
                isManualRequest = true,
                hasMobileDataConsent = false,
                sdkInt = Build.VERSION_CODES.S,
                isAppInForeground = { false },
            ),
        )
        assertTrue(
            SyncForegroundPolicy.shouldPromoteToForeground(
                isManualRequest = false,
                hasMobileDataConsent = true,
                sdkInt = Build.VERSION_CODES.S,
                isAppInForeground = { false },
            ),
        )
        assertTrue(
            SyncForegroundPolicy.shouldPromoteToForeground(
                isManualRequest = false,
                hasMobileDataConsent = false,
                sdkInt = Build.VERSION_CODES.R,
                isAppInForeground = { false },
            ),
        )
    }

    @Test
    fun `progress collector never emits after cancelAndJoin completes`() =
        runTest {
            val statuses =
                MutableStateFlow(
                    SyncStatus(
                        isEnabled = true,
                        lastSyncTime = null,
                        pendingUploads = 0,
                        isSyncing = true,
                        hasErrors = false,
                    ),
                )
            val active = AtomicBoolean(true)
            val updatedAfterFinish = AtomicBoolean(false)
            var finished = false

            coroutineScope {
                val collector =
                    launch {
                        statuses
                            .runProgressUpdates(since = statuses.value, period = 10.milliseconds)
                            .takeWhile {
                                if (!active.get()) return@takeWhile false
                                if (finished) updatedAfterFinish.set(true)
                                true
                            }.collect()
                    }
                try {
                    statuses.value = statuses.value.copy(totalForRun = 5, completedInRun = 1)
                    delay(25.milliseconds)
                    statuses.value = statuses.value.copy(totalForRun = 5, completedInRun = 2)
                } finally {
                    active.set(false)
                    withContext(NonCancellable) {
                        collector.cancelAndJoin()
                    }
                    finished = true
                }
            }

            statuses.value = statuses.value.copy(totalForRun = 5, completedInRun = 3)
            delay(25.milliseconds)
            assertFalse(updatedAfterFinish.get())
        }
}
