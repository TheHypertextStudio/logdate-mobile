package app.logdate.feature.journals.ui.merge

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.repository.journals.JournalMergeCandidate
import app.logdate.client.repository.journals.JournalMergePreview
import app.logdate.feature.journals.ui.detail.JournalDetailScreenContent
import app.logdate.feature.journals.ui.detail.JournalDetailUiState
import app.logdate.shared.model.Journal
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class JournalMergeScreenTest {
    private val source = Journal(title = "Summer in the mountains")
    private val destination = Journal(title = "Journeys with friends and family")
    private val note = Uuid.random()
    private val preview = JournalMergePreview(source, destination, setOf(note), setOf(note, Uuid.random()))

    @Test
    fun `merge action stays hidden until feature enabled`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    JournalDetailScreenContent(
                        uiState = JournalDetailUiState.Success(journalId = source.id, title = source.title, entries = emptyList()),
                        onGoBack = {},
                    )
                }
            }
            onNodeWithText("Merge into…").assertDoesNotExist()
        }

    @Test
    fun `enabled merge appears in journal overflow and opens the source picker`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var opened: Uuid? = null
            setContent {
                LogDateTheme {
                    JournalDetailScreenContent(
                        uiState = JournalDetailUiState.Success(source.id, source.title, emptyList()),
                        onGoBack = {},
                        mergeEnabled = true,
                        onNavigateToMerge = { opened = it },
                    )
                }
            }
            onNodeWithText("Merge into…").assertDoesNotExist()
            onNodeWithContentDescription("Journal options").performClick()
            onNodeWithText("Merge into…").performClick()
            assertEquals(source.id, opened)
        }

    @Test
    fun `tapping destination goes directly to review without continue action`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var selected: Uuid? = null
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        JournalMergeUiState(
                            sourceTitle = source.title,
                            loading = false,
                            candidates = listOf(JournalMergeCandidate(destination, 2)),
                        ),
                        onChoose = { selected = it },
                    )
                }
            }
            onNodeWithText(destination.title).performClick()
            assertEquals(destination.id, selected)
            onNodeWithText("Continue").assertDoesNotExist()
            onNode(hasText("Merge journals") and hasClickAction()).assertDoesNotExist()
        }

    @Test
    fun `review shows useful counts directly before explicit confirmation`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var confirmed = 0
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        JournalMergeUiState(sourceTitle = source.title, loading = false, stage = JournalMergeStage.Review(preview)),
                        onConfirm = { confirmed++ },
                    )
                }
            }
            onNodeWithText("Details").assertDoesNotExist()
            onNodeWithContentDescription("2 items after merging").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("1 item in both journals").assertIsDisplayed()
            onNodeWithText("Merge journals").assertIsDisplayed().performClick()
            assertEquals(1, confirmed)
        }

    @Test
    fun `fully overlapping journals keep the resulting count unchanged`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val overlapping = preview.copy(sourceContentIds = preview.destinationContentIds)
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(JournalMergeUiState(loading = false, stage = JournalMergeStage.Review(overlapping)))
                }
            }
            onNodeWithContentDescription("2 items in source journal").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription("2 items after merging").assertIsDisplayed()
        }

    @Test
    fun `empty picker separates no journals from no matching journals`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var query = "uncharted"
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        JournalMergeUiState(
                            sourceTitle = source.title,
                            query = query,
                            loading = false,
                            candidates = listOf(JournalMergeCandidate(destination, 2)),
                        ),
                        onQueryChange = { query = it },
                    )
                }
            }
            onNodeWithText("No matching journals.").assertIsDisplayed()
            onNodeWithText("Clear search").assertIsDisplayed()
            onNodeWithText("There’s no other journal to merge into.").assertDoesNotExist()
        }

    @Test
    fun `merging disables the primary action`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var confirmed = 0
            setContent {
                LogDateTheme {
                    JournalMergeScreenContent(
                        JournalMergeUiState(loading = false, stage = JournalMergeStage.Merging(preview)),
                        onConfirm = { confirmed++ },
                    )
                }
            }
            onNodeWithText("Merging…").assertIsDisplayed().performClick()
            assertEquals(0, confirmed)
        }
}
