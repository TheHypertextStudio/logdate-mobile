package app.logdate.feature.core.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.rewind.ui.RewindScreenContent
import app.logdate.feature.rewind.ui.overview.RewindHistoryUiState
import app.logdate.feature.rewind.ui.overview.RewindOverviewScreenUiState
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
import app.logdate.ui.platform.LocalReduceMotionOverride
import app.logdate.ui.theme.LogDateTheme
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class WorkspaceRewindBrowsingTest {
    @Test fun storyNavigationCanAdvanceAndOpenThePreviousWeek() =
        runDesktopComposeUiTest(width = 411, height = 800) {
            val current = Uuid.random()
            val previous = Uuid.random()
            val date = LocalDate(2026, 9, 20)
            val previousDate = LocalDate(2026, 9, 13)
            var opened: Uuid? = null
            val state =
                RewindOverviewScreenUiState.Ready(
                    pastRewinds =
                        listOf(
                            RewindHistoryUiState(previous, "Previous story", "Last week", previousDate, previousDate, "A quiet week"),
                        ),
                    mostRecentRewind =
                        RewindPreviewUiState(
                            "A week outside",
                            current,
                            "This week",
                            "Current story",
                            date,
                            date,
                            rewindAvailable = true,
                        ),
                )
            setContent {
                LogDateTheme {
                    CompositionLocalProvider(LocalReduceMotionOverride provides true) {
                        RewindScreenContent(state, { opened = it })
                    }
                }
            }
            onNode(hasScrollAction()).performScrollToNode(hasText("Previous story"))
            onNodeWithText("Previous story").assertIsDisplayed()
            assertEquals(null, opened, "Scrolling to a week must not open playback")
            onAllNodesWithText("Play Rewind").onLast().performScrollTo().performClick()
            assertEquals(previous, opened)
        }
}
