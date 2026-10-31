package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.journals.navigation.JournalsOverviewRoute
import app.logdate.feature.journals.navigation.NoteDetailRoute
import app.logdate.navigation.scenes.RelatedContentSceneStrategy
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ActivityOwnershipTest {
    @Test
    fun journalAndItsNoteComposeRelatedPanes() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    NavDisplay(
                        backStack = listOf(JournalDetailsRoute(Uuid.random()), NoteDetailRoute(Uuid.random())),
                        onBack = {},
                        sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>(supportsDualPane = { true })),
                        entryProvider =
                            entryProvider {
                                taggedEntry<JournalDetailsRoute>(
                                    metadata = ActivityPresentation.Detail("journals", childContext = "journal").asMetadata(),
                                ) { Box(Modifier.fillMaxSize().testTag("journal")) }
                                taggedEntry<NoteDetailRoute>(metadata = ActivityPresentation.Detail("journal").asMetadata()) {
                                    Box(Modifier.fillMaxSize().testTag("note"))
                                }
                            },
                    )
                }
            }
            onAllNodesWithTag("journal").assertCountEquals(1)
            onAllNodesWithTag("note").assertCountEquals(1)
            val journal = onNodeWithTag("journal").fetchSemanticsNode().boundsInRoot
            val note = onNodeWithTag("note").fetchSemanticsNode().boundsInRoot
            assertEquals(true, journal.right <= note.left)
        }

    @Test
    fun mismatchedDeclaredContextDoesNotPairEvenWhenRouteTypesPreviouslyDid() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    NavDisplay(
                        backStack = listOf(JournalsOverviewRoute, JournalDetailsRoute(Uuid.random())),
                        onBack = {},
                        sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>(supportsDualPane = { true })),
                        entryProvider =
                            entryProvider {
                                taggedEntry<JournalsOverviewRoute>(metadata = ActivityPresentation.Collection("other").asMetadata()) {
                                    Box(Modifier.fillMaxSize().testTag("unrelated_collection"))
                                }
                                taggedEntry<JournalDetailsRoute>(metadata = ActivityPresentation.Detail("journals").asMetadata()) {
                                    Box(Modifier.fillMaxSize().testTag("journal"))
                                }
                            },
                    )
                }
            }
            onAllNodesWithTag("unrelated_collection").assertCountEquals(0)
            onAllNodesWithTag("journal").assertCountEquals(1)
        }

    @Test
    fun mergeOwnsTheWindowWithoutAHomeHost() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(
                        LocalWorkspaceSearchAction provides {},
                    ) {
                        NavDisplay(
                            backStack = listOf(HomeRoute, JournalMergeRoute(Uuid.random().toString())),
                            onBack = {},
                            sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>(supportsDualPane = { true })),
                            entryDecorators = listOf(rememberWorkspaceRouteDecorator()),
                            entryProvider =
                                entryProvider {
                                    taggedEntry<HomeRoute> { Box(Modifier.fillMaxSize().testTag("home")) }
                                    taggedEntry<JournalMergeRoute> {
                                        Box(Modifier.fillMaxSize().testTag("merge"))
                                        if (LocalWorkspaceHosted.current) Box(Modifier.testTag("home_host"))
                                    }
                                },
                        )
                    }
                }
            }
            onAllNodesWithTag("home").assertCountEquals(0)
            onAllNodesWithTag("home_host").assertCountEquals(0)
            val bounds = onNodeWithTag("merge").fetchSemanticsNode().boundsInRoot
            assertEquals(1280f, bounds.width)
            assertEquals(800f, bounds.height)
        }

    @Test
    fun journalReadingReplacesUnrelatedHomeContent() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    NavDisplay(
                        backStack = listOf(HomeRoute, JournalDetailsRoute(Uuid.random())),
                        onBack = {},
                        sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>(supportsDualPane = { true })),
                        entryProvider =
                            entryProvider {
                                taggedEntry<HomeRoute> { Box(Modifier.fillMaxSize().testTag("home")) }
                                taggedEntry<JournalDetailsRoute> { Box(Modifier.fillMaxSize().testTag("journal")) }
                            },
                    )
                }
            }
            onAllNodesWithTag("home").assertCountEquals(0)
            val bounds = onNodeWithTag("journal").fetchSemanticsNode().boundsInRoot
            assertEquals(1280f, bounds.width)
        }
}
