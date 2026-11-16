package app.logdate.wear.launch

import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.storage.SecureStorage
import app.logdate.wear.di.wearRemovalModule
import app.logdate.wear.sync.v2.removal.WatchRemovalLock
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Exercises the application's actual startup callback and real removal Koin factory. */
@OptIn(ExperimentalCoroutinesApi::class)
class WearBootstrapGraphTest {
    @Test
    fun `normal local launch does not resolve removal media or remote dependencies`() =
        runTest {
            val secure = mockk<SecureStorage>()
            coEvery { secure.getString(any()) } returns null
            var removalDependencies = 0
            val graph =
                koinApplication {
                    modules(
                        wearRemovalModule,
                        module {
                            single<SecureStorage> { secure }
                            single<LogDateDatabase> {
                                removalDependencies++
                                error("Removal dependency unavailable; ordinary local startup must not resolve it")
                            }
                        },
                    )
                }
            try {
                val startup = WearStartup(backgroundScope, {}, { restoreWearRemoval(graph.koin) }, {}, {})
                startup.start()
                runCurrent()
                assertIs<WearStartupState.Ready>(startup.state.value)
                assertEquals(0, removalDependencies)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `interrupted removal cannot bypass recovery when its dependencies are unavailable`() =
        runTest {
            val secure = mockk<SecureStorage>()
            coEvery { secure.getString(any()) } returns "wiping:retained-link"
            var removalDependencies = 0
            val graph =
                koinApplication {
                    modules(
                        wearRemovalModule,
                        module {
                            single<SecureStorage> { secure }
                            single<LogDateDatabase> {
                                removalDependencies++
                                error("Removal dependency unavailable")
                            }
                        },
                    )
                }
            try {
                // This is the same secure lock used by the real removal registration.
                assertEquals("wiping:retained-link", graph.koin.get<WatchRemovalLock>().state())
                val startup = WearStartup(backgroundScope, {}, { restoreWearRemoval(graph.koin) }, {}, {})
                startup.start()
                runCurrent()
                assertIs<WearStartupState.Failed>(startup.state.value)
                assertEquals(1, removalDependencies)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `restarting an ordinary offline watch never opens the removal graph`() =
        runTest {
            val secure = mockk<SecureStorage>()
            coEvery { secure.getString(any()) } returns null
            val graph =
                koinApplication {
                    modules(
                        wearRemovalModule,
                        module {
                            single<SecureStorage> { secure }
                            single<LogDateDatabase> { error("No phone, network, playback or removal dependency may be needed") }
                        },
                    )
                }
            try {
                repeat(2) {
                    val startup = WearStartup(backgroundScope, {}, { restoreWearRemoval(graph.koin) }, {}, {})
                    startup.start()
                    runCurrent()
                    assertIs<WearStartupState.Ready>(startup.state.value)
                }
            } finally {
                graph.close()
            }
        }

    @Test
    fun `unreadable removal marker never exposes retained content or starts observers`() =
        runTest {
            val secure = mockk<SecureStorage>()
            coEvery { secure.getString(any()) } throws IllegalStateException("Encrypted marker unavailable")
            var observing = false
            val graph = koinApplication { modules(wearRemovalModule, module { single<SecureStorage> { secure } }) }
            try {
                val startup = WearStartup(backgroundScope, {}, { restoreWearRemoval(graph.koin) }, {}, { observing = true })
                startup.start()
                runCurrent()
                assertIs<WearStartupState.Failed>(startup.state.value)
                assertEquals(false, observing)
            } finally {
                graph.close()
            }
        }

    @Test
    fun `every interrupted erasure marker retains the recovery prerequisite`() =
        runTest {
            for (marker in listOf("wiping", "wiping:link", "wiping-revoked:link")) {
                val secure = mockk<SecureStorage>()
                coEvery { secure.getString(any()) } returns marker
                var attempts = 0
                val graph =
                    koinApplication {
                        modules(
                            wearRemovalModule,
                            module {
                                single<SecureStorage> { secure }
                                single<LogDateDatabase> {
                                    attempts++
                                    error("Retained erasure must recover")
                                }
                            },
                        )
                    }
                try {
                    val startup = WearStartup(backgroundScope, {}, { restoreWearRemoval(graph.koin) }, {}, {})
                    startup.start()
                    runCurrent()
                    assertIs<WearStartupState.Failed>(startup.state.value)
                    assertEquals(1, attempts)
                } finally {
                    graph.close()
                }
            }
        }
}
