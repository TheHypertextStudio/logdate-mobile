package app.logdate.client.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncForegroundRunTest {
    private fun status(
        total: Int? = null,
        completed: Int = 0,
    ) = SyncStatus(
        isEnabled = true,
        lastSyncTime = null,
        pendingUploads = total ?: 0,
        isSyncing = total != null,
        hasErrors = false,
        totalForRun = total,
        completedInRun = completed,
    )

    @Test
    fun `repeated empty polls still sync without announcing an upload`() =
        runTest {
            val statuses = MutableStateFlow(status())
            var polls = 0
            var notifications = 0
            repeat(3) {
                withSyncForegroundRun(statuses, {
                    notifications++
                    true
                }, {
                    notifications++
                    true
                }) {
                    polls++
                    delay(100)
                    SyncResult(success = true)
                }
            }
            assertEquals(3, polls, "Remote discovery must still run when the local queue is empty")
            assertEquals(0, notifications, "An empty periodic poll must not announce a backup")
        }

    @Test
    fun `a quick authentication failure is returned without announcing an upload`() =
        runTest {
            var notifications = 0
            val failure = SyncResult(false, errors = listOf(SyncError(SyncErrorType.AUTHENTICATION_ERROR, "Sign in required")))
            val result =
                withSyncForegroundRun(MutableStateFlow(status()), {
                    notifications++
                    true
                }, {
                    notifications++
                    true
                }) {
                    delay(100)
                    failure
                }
            assertEquals(failure, result)
            assertEquals(0, notifications)
        }

    @Test
    fun `a real upload promotes and reports its progress`() =
        runTest {
            val statuses = MutableStateFlow(status())
            val progress = mutableListOf<SyncRunProgress>()
            var starting = 0
            withSyncForegroundRun(statuses, {
                starting++
                true
            }, {
                progress += it
                true
            }) {
                statuses.value = status(2, 0)
                delay(1500)
                statuses.value = status(2, 1)
                delay(1500)
                SyncResult(success = true, uploadedItems = 2)
            }
            assertEquals(listOf(SyncRunProgress(0, 2), SyncRunProgress(1, 2)), progress)
            assertEquals(0, starting, "A determinate upload does not need a separate starting notification")
        }

    @Test
    fun `long eligible remote recovery retains foreground protection without upload progress`() =
        runTest {
            var starting = 0
            var completed = false
            withSyncForegroundRun(MutableStateFlow(status()), {
                starting++
                true
            }, { error("No uploads exist") }, hasEligibleWork = { true }) {
                delay(15000)
                completed = true
            }
            assertTrue(completed)
            assertEquals(1, starting)
        }

    @Test
    fun `late eligible work is observed after an initially empty check`() =
        runTest {
            var eligible = false
            var starting = 0
            withSyncForegroundRun(MutableStateFlow(status()), {
                starting++
                true
            }, { error("No upload denominator exists") }, hasEligibleWork = { eligible }) {
                delay(12000)
                eligible = true
                delay(19000)
            }
            assertEquals(1, starting)
        }

    @Test
    fun `a long check with nothing eligible looks for work less and less often`() =
        runTest {
            var checks = 0
            var starting = 0
            withSyncForegroundRun(MutableStateFlow(status()), {
                starting++
                true
            }, { error("No upload denominator exists") }, hasEligibleWork = {
                checks++
                false
            }) {
                delay(600_000)
            }
            assertEquals(0, starting)
            assertTrue(checks in 5..12, "Each check reads every pending row; $checks ran in ten minutes")
        }

    @Test
    fun `a long upload is never reset to starting by the fallback timer`() =
        runTest {
            val statuses = MutableStateFlow(status())
            var starting = 0
            var uploads = 0
            withSyncForegroundRun(statuses, {
                starting++
                true
            }, {
                uploads++
                true
            }) {
                statuses.value = status(3, 0)
                delay(15000)
            }
            assertTrue(uploads > 0)
            assertEquals(0, starting)
        }

    @Test
    fun `cancelling a short poll leaves no delayed notification`() =
        runTest {
            var notifications = 0
            val entered = CompletableDeferred<Unit>()
            val job =
                launch {
                    withSyncForegroundRun(MutableStateFlow(status()), {
                        notifications++
                        true
                    }, {
                        notifications++
                        true
                    }) {
                        entered.complete(Unit)
                        delay(60000)
                    }
                }
            entered.await()
            job.cancel()
            job.join()
            advanceTimeBy(20000)
            runCurrent()
            assertEquals(0, notifications)
        }

    @Test
    fun `notification denial never interrupts sync or repeatedly retries promotion`() =
        runTest {
            val statuses = MutableStateFlow(status())
            var attempts = 0
            var completed = false
            withSyncForegroundRun(statuses, {
                attempts++
                false
            }, {
                attempts++
                false
            }) {
                statuses.value = status(2, 0)
                delay(1500)
                statuses.value = status(2, 1)
                delay(15000)
                completed = true
            }
            assertTrue(completed)
            assertEquals(1, attempts)
        }

    @Test
    fun `completed progress from a previous run does not announce another backup`() =
        runTest {
            var notifications = 0
            withSyncForegroundRun(MutableStateFlow(status(5, 5)), {
                notifications++
                true
            }, {
                notifications++
                true
            }) {
                delay(200)
            }
            assertEquals(0, notifications)
        }
}
