package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.SavedState
import androidx.savedstate.read
import androidx.savedstate.savedState
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.SyncManager
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.journals.navigation.journalMergeEntry
import app.logdate.feature.journals.ui.merge.JournalMergeViewModel
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

@OptIn(ExperimentalTestApi::class)
class PerVisitSavedStateTest {
    @Test
    fun realKoinMergeViewModelCreatesRestoresItsHandleAndIsolatesNewVisits() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val created = mutableListOf<JournalMergeViewModel>()
            val handles = mutableListOf<SavedStateHandle>()
            val application =
                koinApplication {
                    modules(
                        module {
                            single {
                                MergeJournalsUseCase(
                                    inert<JournalRepository>(),
                                    inert<JournalContentRepository>(),
                                    inert<SyncManager>(),
                                )
                            }
                            viewModel {
                                val handle = get<SavedStateHandle>().also(handles::add)
                                JournalMergeViewModel(get(), handle).also(created::add)
                            }
                        },
                    )
                }
            val route = JournalMergeRoute("00000000-0000-0000-0000-000000000201")
            val secondRoute = JournalMergeRoute("00000000-0000-0000-0000-000000000202")
            val production = entryProvider<NavKey> { journalMergeEntry(onBack = {}, onJournalMerged = {}) }
            val stack = mutableStateListOf<NavKey>(HomeRoute, route)
            var active: JournalMergeViewModel? = null
            var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
            var mounted by mutableStateOf(true)
            setContent {
                if (mounted) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        KoinIsolatedContext(application) {
                            val owner =
                                remember {
                                    object : ViewModelStoreOwner {
                                        override val viewModelStore = ViewModelStore()
                                    }
                                }
                            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                                NavDisplay(
                                    backStack = stack,
                                    onBack = { stack.removeLastOrNull() },
                                    entryDecorators =
                                        listOf(
                                            rememberSaveableStateHolderNavEntryDecorator(),
                                            rememberPerVisitViewModelsDecorator(),
                                        ),
                                    entryProvider =
                                        entryProvider {
                                            taggedEntry<HomeRoute>(ActivityPresentation.Home.asMetadata()) {
                                                Box(Modifier.fillMaxSize().testTag("saved_state_home"))
                                            }
                                            taggedEntry<JournalMergeRoute>(metadata = { production(it).metadata }) {
                                                val model = koinViewModel<JournalMergeViewModel>()
                                                SideEffect { active = model }
                                                Box(Modifier.fillMaxSize().testTag("saved_state_merge"))
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            }
            onNodeWithTag("saved_state_merge").assertIsDisplayed()
            lateinit var original: JournalMergeViewModel
            runOnIdle {
                original = requireNotNull(active)
                assertEquals(1, created.size)
                original.updateQuery("mountain archive")
                handles.last()["journal_merge_operation"] = "retained-operation"
                handles.last()["journal_merge_destination"] = "retained-destination"
            }
            lateinit var savedValues: Map<String, List<Any?>>
            runOnIdle {
                savedValues = registry.performSave().mapValues { (_, values) -> values.map(::copySavedValue) }
                original.updateQuery("changed only after the saved snapshot")
                mounted = false
            }
            waitForIdle()
            runOnIdle {
                registry = SaveableStateRegistry(restoredValues = savedValues) { true }
                mounted = true
            }
            onNodeWithTag("saved_state_merge").assertIsDisplayed()
            runOnIdle {
                assertNotSame(original, active, "Process restoration must construct a new ViewModel")
                assertEquals("mountain archive", requireNotNull(active).uiState.value.query)
                assertEquals("retained-operation", handles.last().get<String>("journal_merge_operation"))
                assertEquals("retained-destination", handles.last().get<String>("journal_merge_destination"))
                assertEquals(2, created.size)
                stack.removeAt(stack.lastIndex)
            }
            onNodeWithTag("saved_state_home").assertIsDisplayed()
            runOnIdle { stack.add(route) }
            onNodeWithTag("saved_state_merge").assertIsDisplayed()
            runOnIdle {
                assertEquals("", requireNotNull(active).uiState.value.query, "Cancellation must not restore a previous visit's handle")
                assertEquals(null, handles.last().get<String>("journal_merge_operation"))
                assertEquals(null, handles.last().get<String>("journal_merge_destination"))
                assertEquals(3, created.size)
                stack.removeAt(stack.lastIndex)
            }
            onNodeWithTag("saved_state_home").assertIsDisplayed()
            runOnIdle { stack.add(secondRoute) }
            onNodeWithTag("saved_state_merge").assertIsDisplayed()
            runOnIdle {
                assertEquals("", requireNotNull(active).uiState.value.query)
                assertEquals(null, handles.last().get<String>("journal_merge_operation"))
                assertEquals(null, handles.last().get<String>("journal_merge_destination"))
                assertEquals(4, created.size)
            }
            application.close()
        }
}

/** The real constructor must not read repositories until open() is called. */
private inline fun <reified T> inert(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unexpected dependency call: ${method.name}")
    } as T

/** Desktop's restoration codec is unavailable; detach every mutable snapshot container before disposal. */
private fun copySavedValue(value: Any?): Any? =
    when (value) {
        null, is String, is Number, is Boolean, is Char, is NavKey -> {
            value
        }

        is SavedState -> {
            savedState(value.read { toMap() }.mapValues { (_, item) -> copySavedValue(item) })
        }

        is Map<*, *> -> {
            value.entries.associate { copySavedValue(it.key) to copySavedValue(it.value) }.toMutableMap()
        }

        is List<*> -> {
            value.map(::copySavedValue).toMutableList()
        }

        is Set<*> -> {
            value.map(::copySavedValue).toMutableSet()
        }

        is Array<*> -> {
            java.lang.reflect.Array.newInstance(value.javaClass.componentType, value.size).also { copy ->
                value.forEachIndexed { index, item ->
                    java.lang.reflect.Array
                        .set(copy, index, copySavedValue(item))
                }
            }
        }

        is BooleanArray -> {
            value.copyOf()
        }

        is ByteArray -> {
            value.copyOf()
        }

        is CharArray -> {
            value.copyOf()
        }

        is DoubleArray -> {
            value.copyOf()
        }

        is FloatArray -> {
            value.copyOf()
        }

        is IntArray -> {
            value.copyOf()
        }

        is LongArray -> {
            value.copyOf()
        }

        is ShortArray -> {
            value.copyOf()
        }

        else -> {
            error("Unsupported saved snapshot value: ${value::class}")
        }
    }
