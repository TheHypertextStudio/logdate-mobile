@file:Suppress("ktlint:standard:function-naming")

package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.journals.navigation.NoteDetailRoute
import app.logdate.feature.journals.navigation.journalDetailsEntry
import app.logdate.feature.journals.navigation.journalMergeEntry
import app.logdate.feature.journals.navigation.noteDetailEntry
import app.logdate.feature.journals.ui.detail.EntryDisplayData
import app.logdate.feature.journals.ui.detail.JournalContext
import app.logdate.feature.journals.ui.detail.JournalDetailScreenContent
import app.logdate.feature.journals.ui.detail.JournalDetailUiState
import app.logdate.feature.journals.ui.detail.NoteViewerScaffoldContent
import app.logdate.feature.journals.ui.detail.NoteViewerShared
import app.logdate.navigation.scenes.RelatedContentSceneStrategy
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.activityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.hasViewModelsPerVisit
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ActivityRestorationTest {
    private val journalId = id(201)
    private val noteId = id(1)
    private val journalRoute = JournalDetailsRoute(journalId)
    private val noteRoute = NoteDetailRoute(noteId.toString(), journalId.toString())
    private val productionEntries =
        entryProvider<NavKey> {
            journalDetailsEntry(onBack = {}, onJournalDeleted = {}, onOpenNote = { _, _ -> })
            journalMergeEntry(onBack = {}, onJournalMerged = {})
            noteDetailEntry(onBack = {})
        }

    @Test
    fun homeRestoresItsSavedDestinationAndExactScrollAfterSettingsAndJournalReplacement() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val owner = storeOwner()
            val stack = mutableStateListOf<NavKey>(HomeRoute)
            var browsing = BrowsingState("timeline", 0, 0)
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NavDisplay(
                            backStack = stack,
                            onBack = { stack.removeLastOrNull() },
                            sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>({ true })),
                            entryDecorators =
                                listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberPerVisitViewModelsDecorator(),
                                    rememberWorkspaceRouteDecorator(),
                                ),
                            entryProvider =
                                entryProvider {
                                    taggedEntry<HomeRoute>(ActivityPresentation.Home.asMetadata()) {
                                        RestorableHome(
                                            onSettings = { stack.add(SettingsRoute()) },
                                            onJournal = { stack.add(journalRoute) },
                                            onBrowsing = { browsing = it },
                                        )
                                    }
                                    taggedEntry<SettingsRoute>(ActivityPresentation.SettingsRoot.asMetadata()) {
                                        Box(Modifier.fillMaxSize().testTag("settings_replacement"))
                                    }
                                    taggedEntry<JournalDetailsRoute>(metadata = { productionEntries(it).metadata }) {
                                        Box(Modifier.fillMaxSize().testTag("journal_replacement"))
                                    }
                                },
                        )
                    }
                }
            }
            onNodeWithText("Places").performClick()
            listOf("Settings" to "settings_replacement", "Journal" to "journal_replacement").forEach { (label, replacement) ->
                onNodeWithTag("retained_home").performScrollToNode(hasText("$label at memory 40"))
                lateinit var saved: BrowsingState
                runOnIdle {
                    saved = browsing
                    assertEquals("places", saved.destination)
                    assertTrue(saved.index > 0)
                }
                onNodeWithText("$label at memory 40").performClick()
                onNodeWithTag(replacement).assertIsDisplayed()
                onAllNodesWithTag("retained_home").assertCountEquals(0)
                onAllNodesWithTag("workspace_search").assertCountEquals(0)
                runOnIdle { stack.removeAt(stack.lastIndex) }
                waitForIdle()
                onNodeWithTag("retained_home").assertIsDisplayed()
                onAllNodesWithTag("workspace_search").assertCountEquals(1)
                runOnIdle { assertEquals(saved, browsing, "Back must restore the destination and exact list offset") }
            }
            runOnIdle { owner.viewModelStore.clear() }
        }

    @Test
    fun noteKeepsOnePerVisitViewModelWhileResizingIntoRelatedPanesAndClearsItOnPop() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val owner = storeOwner()
            val stack = mutableStateListOf<NavKey>(HomeRoute, journalRoute, noteRoute)
            val created = mutableListOf<VisitNoteViewModel>()
            var active: VisitNoteViewModel? = null
            var width by mutableStateOf(411)
            val metadata = productionEntries(noteRoute)
            assertTrue(metadata.hasViewModelsPerVisit(), "Production note registration must request a per-visit store")
            assertEquals(
                (productionEntries(journalRoute).activityPresentation() as ActivityPresentation.Detail).childContext,
                (metadata.activityPresentation() as ActivityPresentation.Detail).context,
            )
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        Box(Modifier.fillMaxSize()) {
                            NavDisplay(
                                modifier = Modifier.width(width.dp),
                                backStack = stack,
                                onBack = { stack.removeLastOrNull() },
                                sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>({ width >= 1000 })),
                                entryDecorators =
                                    listOf(
                                        rememberSaveableStateHolderNavEntryDecorator(),
                                        rememberPerVisitViewModelsDecorator(),
                                        rememberWorkspaceRouteDecorator(),
                                    ),
                                entryProvider =
                                    entryProvider {
                                        taggedEntry<HomeRoute>(ActivityPresentation.Home.asMetadata()) {
                                            Box(Modifier.testTag("unrelated_home"))
                                        }
                                        taggedEntry<JournalDetailsRoute>(metadata = { productionEntries(it).metadata }) {
                                            Box(Modifier.fillMaxSize().testTag("journal_context"))
                                        }
                                        taggedEntry<NoteDetailRoute>(metadata = { productionEntries(it).metadata }) { route ->
                                            val model = viewModel { VisitNoteViewModel(route.noteId).also(created::add) }
                                            SideEffect { active = model }
                                            Box(Modifier.fillMaxSize().testTag("note_content"))
                                        }
                                    },
                            )
                        }
                    }
                }
            }
            onNodeWithTag("note_content").assertIsDisplayed()
            onAllNodesWithTag("journal_context").assertCountEquals(0)
            lateinit var original: VisitNoteViewModel
            runOnIdle {
                original = requireNotNull(active)
                assertEquals(1, created.size)
                width = 1280
            }
            waitForIdle()
            onNodeWithTag("journal_context").assertIsDisplayed()
            onNodeWithTag("note_content").assertIsDisplayed()
            onAllNodesWithTag("unrelated_home").assertCountEquals(0)
            runOnIdle {
                assertSame(original, active)
                assertEquals(1, created.size, "Showing a retained entry in related panes must not create another model")
                width = 411
            }
            waitForIdle()
            runOnIdle {
                assertSame(original, active)
                assertEquals(1, created.size)
                stack.removeAt(stack.lastIndex)
            }
            waitUntil(timeoutMillis = 5_000) { original.cleared }
            runOnIdle { stack.add(noteRoute) }
            waitForIdle()
            runOnIdle {
                assertEquals(2, created.size)
                assertNotSame(original, active, "Reopening the same note after Back creates a new visit")
                assertEquals(noteId.toString(), requireNotNull(active).noteId)
                owner.viewModelStore.clear()
            }
        }

    @Test
    fun cancelledMergeClearsItsVisitAndBothSameAndDifferentSourcesGetFreshModels() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val owner = storeOwner()
            val firstRoute = JournalMergeRoute(journalId.toString())
            val secondRoute = JournalMergeRoute(id(202).toString())
            val stack = mutableStateListOf<NavKey>(HomeRoute, firstRoute)
            val created = mutableListOf<VisitMergeViewModel>()
            var active: VisitMergeViewModel? = null
            assertTrue(
                productionEntries(firstRoute).hasViewModelsPerVisit(),
                "Production merge registration must request its own per-visit store",
            )
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NavDisplay(
                            backStack = stack,
                            onBack = { stack.removeLastOrNull() },
                            sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>({ true })),
                            entryDecorators =
                                listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberPerVisitViewModelsDecorator(),
                                    rememberWorkspaceRouteDecorator(),
                                ),
                            entryProvider =
                                entryProvider {
                                    taggedEntry<HomeRoute>(ActivityPresentation.Home.asMetadata()) {
                                        Box(Modifier.fillMaxSize().testTag("merge_home"))
                                    }
                                    taggedEntry<JournalMergeRoute>(metadata = { productionEntries(it).metadata }) { route ->
                                        val model = viewModel { VisitMergeViewModel(route.sourceId).also(created::add) }
                                        SideEffect { active = model }
                                        Box(Modifier.fillMaxSize().testTag("merge_visit"))
                                    }
                                },
                        )
                    }
                }
            }
            onNodeWithTag("merge_visit").assertIsDisplayed()
            lateinit var original: VisitMergeViewModel
            runOnIdle {
                original = requireNotNull(active)
                original.query = "unfinished destination"
                stack.removeAt(stack.lastIndex)
            }
            waitUntil(timeoutMillis = 5_000) { original.cleared }
            runOnIdle { stack.add(firstRoute) }
            waitForIdle()
            lateinit var reopened: VisitMergeViewModel
            runOnIdle {
                reopened = requireNotNull(active)
                assertNotSame(original, reopened)
                assertEquals(firstRoute.sourceId, reopened.sourceId)
                assertEquals("", reopened.query)
                assertEquals(2, created.size)
                stack.removeAt(stack.lastIndex)
            }
            waitUntil(timeoutMillis = 5_000) { reopened.cleared }
            runOnIdle { stack.add(secondRoute) }
            waitForIdle()
            runOnIdle {
                assertNotSame(reopened, active)
                assertEquals(secondRoute.sourceId, requireNotNull(active).sourceId)
                assertEquals("", requireNotNull(active).query)
                assertEquals(3, created.size)
                owner.viewModelStore.clear()
            }
        }

    @Test
    fun populatedRelatedJournalAndNoteRenderWithProductionContextMetadata() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            val owner = storeOwner()
            val time = Instant.parse("2026-10-09T08:15:00Z")
            val journalTitle = "Summer in the mountains"
            val entries =
                listOf(
                    EntryDisplayData.TextEntry(noteId, time, "We reached the lake just as the morning light crossed the ridge."),
                    EntryDisplayData.TextEntry(id(2), time - 3.hours, "A slow afternoon at the cabin, with maps spread over the table."),
                    EntryDisplayData.TextEntry(id(3), time - 6.hours, "Wildflowers along the trail and one last stop for coffee."),
                )
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NavDisplay(
                            backStack = listOf(HomeRoute, journalRoute, noteRoute),
                            onBack = {},
                            sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>({ true })),
                            entryDecorators =
                                listOf(
                                    rememberSaveableStateHolderNavEntryDecorator(),
                                    rememberPerVisitViewModelsDecorator(),
                                    rememberWorkspaceRouteDecorator(),
                                ),
                            entryProvider =
                                entryProvider {
                                    taggedEntry<HomeRoute>(ActivityPresentation.Home.asMetadata()) {
                                        Box(Modifier.testTag("unrelated_home"))
                                    }
                                    taggedEntry<JournalDetailsRoute>(metadata = { productionEntries(it).metadata }) {
                                        Box(Modifier.fillMaxSize().testTag("populated_journal")) {
                                            JournalDetailScreenContent(
                                                JournalDetailUiState.Success(journalId, journalTitle, entries),
                                                onGoBack = {},
                                            )
                                        }
                                    }
                                    taggedEntry<NoteDetailRoute>(metadata = { productionEntries(it).metadata }) {
                                        NoteViewerScaffoldContent(
                                            NoteViewerShared(
                                                noteId,
                                                time,
                                                time,
                                                null,
                                                JournalContext(journalId, journalTitle, entries.map { entry -> entry.id }, 0),
                                            ),
                                            onGoBack = {},
                                            modifier = Modifier.fillMaxSize().testTag("populated_note"),
                                        ) {
                                            Text(
                                                "We reached the lake just as the morning light crossed the ridge.\n\n" +
                                                    "The water was still enough to hold the whole sky. " +
                                                    "We stayed until the trail began to fill, " +
                                                    "then took the long way back through the trees.",
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                    }
                                },
                        )
                    }
                }
            }
            onAllNodesWithTag("unrelated_home").assertCountEquals(0)
            onAllNodesWithTag("workspace_search").assertCountEquals(0)
            onNodeWithTag("populated_journal").assertIsDisplayed()
            onNodeWithTag("populated_note").assertIsDisplayed()
            val journal = onNodeWithTag("populated_journal").fetchSemanticsNode().boundsInRoot
            val note = onNodeWithTag("populated_note").fetchSemanticsNode().boundsInRoot
            assertTrue(journal.right <= note.left)
            saveReading(onRoot().captureToImage())
            runOnIdle { owner.viewModelStore.clear() }
        }

    private class VisitNoteViewModel(
        val noteId: String,
    ) : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    private class VisitMergeViewModel(
        val sourceId: String,
    ) : ViewModel() {
        var query = ""
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    private fun storeOwner() =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

    private fun saveReading(image: ImageBitmap) {
        val directory = System.getenv("LOGDATE_READING_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "related-scene.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}

private data class BrowsingState(
    val destination: String,
    val index: Int,
    val offset: Int,
)

@Composable
private fun RestorableHome(
    onSettings: () -> Unit,
    onJournal: () -> Unit,
    onBrowsing: (BrowsingState) -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf("timeline") }
    val scroll = rememberLazyListState()
    val browsing = BrowsingState(destination, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset)
    SideEffect { onBrowsing(browsing) }
    WorkspaceScaffold(
        listOf(
            WorkspaceDestination("timeline", "Timeline", Icons.Rounded.History),
            WorkspaceDestination("places", "Places", Icons.Rounded.Place),
        ),
        destination,
        { destination = it },
        onSearch = {},
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("retained_home"), state = scroll) {
            items((1..60).toList(), key = { it }) { index ->
                Row(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("${if (destination == "places") "Places" else "Timeline"} memory $index", Modifier.weight(1f))
                    TextButton(onClick = onSettings) { Text("Settings at memory $index") }
                    TextButton(onClick = onJournal) { Text("Journal at memory $index") }
                }
            }
        }
    }
}

private fun id(value: Int) = Uuid.parse("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")
