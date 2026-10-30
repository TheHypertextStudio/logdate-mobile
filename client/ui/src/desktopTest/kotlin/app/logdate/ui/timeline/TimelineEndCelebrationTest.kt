package app.logdate.ui.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class TimelineEndCelebrationTest {
    private val day =
        TimelineDayUiState(
            summary = "A short day",
            date = LocalDate(2026, 10, 28),
            notes =
                listOf(
                    TextNoteUiState(
                        Uuid.parse("00000000-0000-0000-0000-000000000001"),
                        "One note",
                        Instant.parse("2026-10-28T18:30:00Z"),
                    ),
                ),
        )

    @Test
    fun theEndOfTheTimelineCelebratesTheSavedBirthday() =
        render(birthday = Instant.parse("1995-06-16T00:00:00Z"), hasMoreOlderContent = false) {
            onAllNodesWithText("Happy birthday.").assertCountEquals(1)
        }

    @Test
    fun theEndOfTheTimelineInvitesABirthdayWhenNoneIsSaved() =
        render(birthday = null, hasMoreOlderContent = false) {
            onAllNodesWithText("You've reached the end").assertCountEquals(1)
            onAllNodesWithText("Happy birthday.").assertCountEquals(0)
        }

    @Test
    fun olderEntriesStillToLoadKeepTheEndHidden() =
        render(birthday = Instant.parse("1995-06-16T00:00:00Z"), hasMoreOlderContent = true) {
            onAllNodesWithText("Happy birthday.").assertCountEquals(0)
        }

    private fun render(
        birthday: Instant?,
        hasMoreOlderContent: Boolean,
        assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 411, height = 891) {
        setContent {
            LogDateTheme(dynamicColor = false) {
                TimelinePane(
                    uiState =
                        TimelineUiState(
                            items = listOf(day),
                            loadingState = TimelineLoadingState.Loaded,
                            hasMoreOlderContent = hasMoreOlderContent,
                        ),
                    onNewEntry = {},
                    onOpenDay = {},
                    birthday = birthday,
                )
            }
        }
        waitForIdle()
        assertions()
    }
}
