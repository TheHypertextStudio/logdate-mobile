package app.logdate.client.launch

import app.logdate.client.database.NewerDatabaseVersionException
import app.logdate.client.database.encryption.DatabaseKeyRecoveryRequiredException
import app.logdate.client.device.restore.NativeRestorePrivacyMetadataRequiredException
import app.logdate.client.restore.NativeMediaQuarantinePendingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApplicationStartupTest {
    @Test
    fun `waiting for dependencies returns once Koin starts even if the journal is still opening`() =
        runTest(UnconfinedTestDispatcher()) {
            val journal = CompletableDeferred<Unit>()
            val dependencies = MutableStateFlow(false)
            val startup = ApplicationStartup(backgroundScope) { journal.await() }
            startup.start()
            val waiting = async { startup.awaitDependencies(dependencies) }
            runCurrent()
            assertFalse(waiting.isCompleted, "Callers that read Koin must wait until it starts")
            dependencies.value = true
            runCurrent()
            assertTrue(waiting.isCompleted)
            assertIs<ApplicationStartupState.Loading>(startup.state.value)
            journal.complete(Unit)
        }

    @Test
    fun `waiting for dependencies reports a startup that fails before Koin starts`() =
        runTest(UnconfinedTestDispatcher()) {
            val startup = ApplicationStartup(backgroundScope) { error("Device still locked") }
            startup.start()
            val failure = runCatching { startup.awaitDependencies(MutableStateFlow(false)) }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertEquals("Device still locked", failure.cause?.message)
        }

    @Test
    fun `waiting for dependencies ignores a later journal failure once Koin started`() =
        runTest(UnconfinedTestDispatcher()) {
            val startup = ApplicationStartup(backgroundScope) { error("Journal key missing") }
            startup.start()
            startup.awaitDependencies(MutableStateFlow(true))
            assertIs<ApplicationStartupState.Failed>(startup.state.value)
        }

    @Test
    fun `optional device work cannot delay opening a validated journal`() =
        runTest {
            val content = CompletableDeferred<Unit>()
            val deviceWork = CompletableDeferred<Unit>()
            var starts = 0
            val startup =
                ApplicationStartup(backgroundScope, startDeviceWork = {
                    starts++
                    deviceWork.await()
                }) { content.await() }
            startup.start()
            runCurrent()
            assertIs<ApplicationStartupState.Loading>(startup.state.value)
            assertEquals(0, starts, "Device observers cannot start before retained content is validated")
            content.complete(Unit)
            runCurrent()
            assertTrue(startup.awaitReady())
            assertEquals(1, starts)
            assertFalse(deviceWork.isCompleted, "Slow optional initialization must remain independent of journal access")
            repeat(3) { startup.start() }
            runCurrent()
            assertEquals(1, starts, "Repeated foreground startup cannot duplicate device initialization")
            deviceWork.complete(Unit)
        }

    @Test
    fun `optional startup failure cannot close a validated journal`() =
        runTest {
            var starts = 0
            val startup =
                ApplicationStartup(backgroundScope, startDeviceWork = {
                    starts++
                    error("Device service unavailable")
                }) {}
            startup.start()
            runCurrent()
            assertTrue(startup.awaitReady())
            assertEquals(1, starts)
        }

    @Test
    fun `failed content recovery cannot start device observers before a successful retry`() =
        runTest {
            var attempts = 0
            var starts = 0
            val startup =
                ApplicationStartup(backgroundScope, startDeviceWork = { starts++ }) {
                    attempts++
                    check(attempts > 1) { "Retained journal unavailable" }
                }
            startup.start()
            runCurrent()
            assertFalse(startup.awaitReady())
            assertEquals(0, starts)
            startup.start()
            runCurrent()
            assertTrue(startup.awaitReady())
            assertEquals(1, starts)
        }

    @Test
    fun `a newer journal requests an app update through dependency wrappers`() {
        val failure = IllegalStateException("Startup dependency failed", NewerDatabaseVersionException(58, 57))
        assertEquals(ApplicationStartupFailureMessage.UPDATE_REQUIRED, applicationStartupFailureMessage(failure))
    }

    @Test
    fun `retry waits for failed attempt cleanup and coalesces repeated requests`() =
        runTest {
            val cleanup = CompletableDeferred<Unit>()
            var attempts = 0
            val startup =
                ApplicationStartup(backgroundScope) {
                    attempts++
                    if (attempts == 1) {
                        CoroutineScope(currentCoroutineContext()).launch { cleanup.await() }
                        error("Storage temporarily unavailable")
                    }
                }
            startup.start()
            runCurrent()
            assertIs<ApplicationStartupState.Failed>(startup.state.value)
            startup.start()
            startup.start()
            runCurrent()
            assertEquals(1, attempts, "A retry cannot overlap unfinished recovery cleanup")
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(2, attempts, "Repeated retry requests must schedule one successor")
            assertIs<ApplicationStartupState.Ready>(startup.state.value)
        }

    @Test
    fun `immediate failure retry keeps ownership of its unfinished attempt`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val finish = CompletableDeferred<Unit>()
            var attempts = 0
            val startup =
                ApplicationStartup(CoroutineScope(backgroundScope.coroutineContext + dispatcher)) {
                    attempts++
                    check(attempts > 1) { "Storage temporarily unavailable" }
                    finish.await()
                }
            val retry =
                launch(dispatcher) {
                    startup.state.first { it is ApplicationStartupState.Failed }
                    startup.start()
                }
            startup.start()
            startup.start()
            assertEquals(2, attempts, "A retry in progress must remain the current attempt")
            assertIs<ApplicationStartupState.Loading>(startup.state.value)
            finish.complete(Unit)
            runCurrent()
            retry.join()
            assertIs<ApplicationStartupState.Ready>(startup.state.value)
        }

    @Test
    fun `retry offered by a failure cannot be dropped while its job finishes`() =
        runTest {
            var attempts = 0
            val startup =
                ApplicationStartup(backgroundScope) {
                    attempts++
                    check(attempts > 1) { "Storage temporarily unavailable" }
                }
            val retry =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    startup.state.first { it is ApplicationStartupState.Failed }
                    startup.start()
                }
            startup.start()
            runCurrent()
            retry.join()
            assertEquals(2, attempts, "A retry must start as soon as failure is observable")
            assertIs<ApplicationStartupState.Ready>(startup.state.value)
        }

    @Test
    fun `missing restored privacy metadata reaches its recovery guidance through dependency wrappers`() {
        val failure = IllegalStateException("Startup dependency failed", NativeRestorePrivacyMetadataRequiredException())
        assertEquals(ApplicationStartupFailureMessage.PRIVACY_METADATA_RECOVERY, applicationStartupFailureMessage(failure))
    }

    @Test
    fun `database storage exhaustion gives a repair action before journal initialization`() {
        val failure = IllegalStateException("Database initialization failed", IllegalStateException("no space left on device"))

        assertEquals(ApplicationStartupFailureMessage.STORAGE_FULL, applicationStartupFailureMessage(failure))
    }

    @Test
    fun `missing database key reaches recovery guidance through dependency wrappers`() {
        val failure = IllegalStateException("Storage dependency failed", DatabaseKeyRecoveryRequiredException())

        assertNotEquals(ApplicationStartupFailureMessage.GENERIC, applicationStartupFailureMessage(failure))
    }

    @Test
    fun `native media recovery offers a storage-specific retry when the database is full`() {
        val failure = NativeMediaQuarantinePendingException(IllegalStateException("database or disk is full"))

        assertEquals(ApplicationStartupFailureMessage.STORAGE_FULL, applicationStartupFailureMessage(failure))
    }

    @Test
    fun `native media recovery keeps its failure message actionable without a storage error`() {
        val failure = NativeMediaQuarantinePendingException()

        assertEquals(ApplicationStartupFailureMessage.NATIVE_MEDIA_PENDING, applicationStartupFailureMessage(failure))
    }

    @Test
    fun `native media recovery recognizes a storage failure suppressed by a later enqueue failure`() {
        val failure = NativeMediaQuarantinePendingException(IllegalStateException("WorkManager enqueue failed"))
        failure.addSuppressed(IllegalStateException("database or disk is full"))

        assertEquals(ApplicationStartupFailureMessage.STORAGE_FULL, applicationStartupFailureMessage(failure))
    }

    @Test
    fun `callers cannot access the graph until initialization completes`() =
        runTest {
            val opened = CompletableDeferred<Unit>()
            val startup = ApplicationStartup(backgroundScope) { opened.await() }
            startup.start()
            val waiter = async { startup.awaitReady() }
            runCurrent()
            assertIs<ApplicationStartupState.Loading>(startup.state.value)
            assertFalse(waiter.isCompleted)
            opened.complete(Unit)
            runCurrent()
            assertTrue(waiter.await())
            assertIs<ApplicationStartupState.Ready>(startup.state.value)
        }

    @Test
    fun `failed initialization is visible and only explicit retry reruns it`() =
        runTest {
            var attempts = 0
            val startup =
                ApplicationStartup(backgroundScope) {
                    attempts++
                    check(attempts > 1) { "Storage unavailable" }
                }
            startup.start()
            runCurrent()
            assertIs<ApplicationStartupState.Failed>(startup.state.value)
            assertFalse(startup.awaitReady())
            assertEquals(1, attempts)
            startup.start()
            runCurrent()
            assertTrue(startup.awaitReady())
            assertEquals(2, attempts)
        }

    @Test
    fun `native restore completion during loading retries missing-key startup automatically`() =
        runTest {
            val completionCount = MutableStateFlow(0L)
            val firstAttempt = CompletableDeferred<Unit>()
            var keyDelivered = false
            var attempts = 0
            var reconciliations = 0
            val startup =
                ApplicationStartup(backgroundScope) {
                    attempts++
                    if (attempts == 1) {
                        firstAttempt.await()
                        throw DatabaseKeyRecoveryRequiredException()
                    }
                    check(keyDelivered)
                }
            val observer =
                observeNativeRestoreDeliveryCompletions(
                    backgroundScope,
                    completionCount,
                    startup,
                ) { reconciliations++ }

            startup.start()
            runCurrent()
            assertIs<ApplicationStartupState.Loading>(startup.state.value)
            keyDelivered = true
            completionCount.value++
            runCurrent()
            firstAttempt.complete(Unit)
            runCurrent()

            assertTrue(startup.awaitReady())
            assertEquals(2, attempts, "The completion token must survive the original Loading attempt")
            assertEquals(0, reconciliations, "A successful retry uses normal startup work")
            observer.cancel()
        }

    @Test
    fun `retry resolves the original durable binding without caching a failed replacement`() =
        runTest {
            val durableStore = DurableStore("retained journal")
            var attempts = 0
            val graph =
                koinApplication {
                    modules(
                        module {
                            single {
                                attempts++
                                check(attempts > 1) { "Encrypted store unavailable" }
                                durableStore
                            }
                        },
                    )
                }
            try {
                var resolved: DurableStore? = null
                val startup = ApplicationStartup(backgroundScope) { resolved = graph.koin.get() }
                startup.start()
                runCurrent()
                assertFalse(startup.awaitReady())
                assertEquals(null, resolved)
                assertEquals("retained journal", durableStore.content)
                startup.start()
                runCurrent()
                assertTrue(startup.awaitReady())
                assertSame(durableStore, resolved)
                assertEquals(2, attempts)
            } finally {
                graph.close()
            }
        }

    private data class DurableStore(
        val content: String,
    )

    @Test
    fun `repeated start requests share one initialization and ready is final`() =
        runTest {
            val opened = CompletableDeferred<Unit>()
            var attempts = 0
            val startup =
                ApplicationStartup(backgroundScope) {
                    attempts++
                    opened.await()
                }
            repeat(4) { startup.start() }
            runCurrent()
            assertEquals(1, attempts)
            opened.complete(Unit)
            runCurrent()
            startup.start()
            runCurrent()
            assertEquals(1, attempts)
        }
}
