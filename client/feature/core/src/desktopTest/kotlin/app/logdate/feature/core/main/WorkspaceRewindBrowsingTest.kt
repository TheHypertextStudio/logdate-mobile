package app.logdate.feature.core.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            var opened: Uuid? = null
            val state =
                RewindOverviewScreenUiState.Ready(
                    pastRewinds = listOf(RewindHistoryUiState(previous, "Previous story", "Last week", date, date, "A quiet week")),
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
            onNodeWithContentDescription("More rewinds below").performClick()
            onNodeWithText("Previous story").performClick()
            assertEquals(previous, opened)
        }
}
