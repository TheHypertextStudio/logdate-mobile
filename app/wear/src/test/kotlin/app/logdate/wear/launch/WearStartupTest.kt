package app.logdate.wear.launch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WearStartupTest {
    @Test
    fun successfulObserverPartsAreNotDuplicatedAfterALaterFailure() {
        val observers = WearStartupObservers()
        var firstStarts = 0
        var secondAttempts = 0
        repeat(2) {
            runCatching {
                observers.start("first") { firstStarts++ }
                observers.start("second") {
                    secondAttempts++
                    check(secondAttempts > 1)
                }
            }
        }
        assertEquals(1, firstStarts)
        assertEquals(2, secondAttempts)
    }

    @Test
    fun storageAndRemovalPrecedeRecoveryAndObservers() =
        runTest {
            val calls = mutableListOf<String>()
            val startup =
                WearStartup(
                    backgroundScope,
                    { calls += "storage" },
                    { calls += "removal" },
                    { calls += "recording" },
                    { calls += "observers" },
                )
            startup.start()
            runCurrent()
            assertEquals(listOf("storage", "removal", "recording", "observers"), calls)
            assertTrue(startup.awaitReady())
        }

    @Test
    fun slowRecordingRecoveryDoesNotHoldTheWatchScreenClosed() =
        runTest {
            val recovery = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val startup =
                WearStartup(
                    backgroundScope,
                    { calls += "storage" },
                    { calls += "removal" },
                    {
                        calls += "recording"
                        recovery.await()
                    },
                    { calls += "observers" },
                )

            startup.start()
            runCurrent()

            assertIs<WearStartupState.Ready>(startup.state.value)
            assertTrue(startup.awaitReady())
            assertEquals(listOf("storage", "removal", "recording"), calls)
            recovery.complete(Unit)
            runCurrent()
            assertEquals(listOf("storage", "removal", "recording", "observers"), calls)
        }

    @Test
    fun loadingDoesNotReleaseColdEntryCallersOrStartObservers() =
        runTest {
            val storage = CompletableDeferred<Unit>()
            var observing = false
            val startup = WearStartup(backgroundScope, { storage.await() }, {}, {}, { observing = true })
            startup.start()
            val waiter = async { startup.awaitReady() }
            runCurrent()
            assertFalse(waiter.isCompleted)
            assertFalse(observing)
            storage.complete(Unit)
            runCurrent()
            assertTrue(waiter.await())
        }

    @Test
    fun failedRemovalKeepsCapturesAndObserversClosedUntilExplicitRetry() =
        runTest {
            var attempts = 0
            var recordingRecovery = false
            var observing = false
            val startup =
                WearStartup(
                    backgroundScope,
                    {},
                    {
                        attempts++
                        check(attempts > 1)
                    },
                    { recordingRecovery = true },
                    { observing = true },
                )
            startup.start()
            runCurrent()
            assertIs<WearStartupState.Failed>(startup.state.value)
            assertFalse(startup.awaitReady())
            assertFalse(recordingRecovery)
            assertFalse(observing)
            startup.start()
            runCurrent()
            assertTrue(startup.awaitReady())
            assertEquals(2, attempts)
            assertTrue(recordingRecovery)
            assertTrue(observing)
        }

    @Test
    fun repeatedColdEntriesShareOneAttemptAndNeverRestartReadyObservers() =
        runTest {
            var opens = 0
            val storage = CompletableDeferred<Unit>()
            val startup =
                WearStartup(backgroundScope, {
                    opens++
                    storage.await()
                }, {}, {}, {})
            repeat(3) { startup.start() }
            runCurrent()
            assertEquals(1, opens)
            storage.complete(Unit)
            runCurrent()
            startup.start()
            runCurrent()
            assertEquals(1, opens)
        }

    @Test
    fun foregroundReturnRetriesFailedLocalStorageWithoutStartingRemoteWork() =
        runTest {
            var opens = 0
            var observers = 0
            val startup =
                WearStartup(backgroundScope, {
                    opens++
                    check(opens > 1)
                }, {}, {}, { observers++ })
            startup.start()
            runCurrent()
            assertIs<WearStartupState.Failed>(startup.state.value)
            assertEquals(0, observers)
            // MainActivity.onStart uses this same idempotent entry point after unlock/foreground return.
            startup.start()
            runCurrent()
            assertIs<WearStartupState.Ready>(startup.state.value)
            assertEquals(2, opens)
            assertEquals(1, observers)
        }

    @Test
    fun repeatedForegroundReturnsDuringStorageOpenRemainSingleFlight() =
        runTest {
            var opens = 0
            val available = CompletableDeferred<Unit>()
            val startup =
                WearStartup(backgroundScope, {
                    opens++
                    available.await()
                }, {}, {}, {})
            repeat(4) {
                startup.start()
                runCurrent()
            }
            assertEquals(1, opens)
            assertIs<WearStartupState.Loading>(startup.state.value)
            available.complete(Unit)
            runCurrent()
            assertIs<WearStartupState.Ready>(startup.state.value)
            startup.start()
            runCurrent()
            assertEquals(1, opens)
        }
}
