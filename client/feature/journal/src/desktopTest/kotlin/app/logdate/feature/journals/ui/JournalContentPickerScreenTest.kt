package app.logdate.feature.journals.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.journals.ui.detail.JournalDetailScreenContent
import app.logdate.feature.journals.ui.detail.JournalDetailUiState
import app.logdate.feature.journals.ui.picker.JournalContentPickerDateGroup
import app.logdate.feature.journals.ui.picker.JournalContentPickerItem
import app.logdate.feature.journals.ui.picker.JournalContentPickerItemKind
import app.logdate.feature.journals.ui.picker.JournalContentPickerScreenContent
import app.logdate.feature.journals.ui.picker.JournalContentPickerUiState
import app.logdate.feature.journals.ui.picker.contentPickerGridColumnCount
import app.logdate.ui.adaptive.ActivityRouteFrame
import app.logdate.ui.theme.LogDateTheme
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class JournalContentPickerScreenTest {
    @Test
    fun `nonvisual cards prioritize their content over type labels`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val recording =
                JournalContentPickerItem(
                    id = Uuid.random(),
                    kind = JournalContentPickerItemKind.RECORDING,
                    timestamp = Instant.parse("2026-10-06T08:00:00Z"),
                    title = "Rain against the glass",
                )
            val photo =
                JournalContentPickerItem(
                    id = Uuid.random(),
                    kind = JournalContentPickerItemKind.PHOTO,
                    timestamp = Instant.parse("2026-10-06T07:00:00Z"),
                    title = "Rain on the windowsill",
                )
            setContent {
                LogDateTheme {
                    JournalContentPickerScreenContent(
                        state =
                            JournalContentPickerUiState(
                                groups =
                                    listOf(
                                        JournalContentPickerDateGroup(
                                            LocalDate(2026, 10, 6),
                                            items = listOf(sampleWriting, recording, photo),
                                        ),
                                    ),
                            ),
                    )
                }
            }

            onNodeWithText(checkNotNull(sampleWriting.title)).assertIsDisplayed()
            onNodeWithText(checkNotNull(recording.title)).assertIsDisplayed()
            onNodeWithText(checkNotNull(photo.title)).assertIsDisplayed()
            onNodeWithText("Writing").assertDoesNotExist()
            onNodeWithText("Recording").assertDoesNotExist()
            onNodeWithText("Photo").assertDoesNotExist()
        }

    @Test
    fun `picker keeps a readable number of masonry columns`() {
        assertEquals(1, contentPickerGridColumnCount(360.dp, fontScale = 1f))
        assertEquals(1, contentPickerGridColumnCount(411.dp, fontScale = 1f))
        assertEquals(2, contentPickerGridColumnCount(840.dp, fontScale = 1f))
        assertEquals(3, contentPickerGridColumnCount(1280.dp, fontScale = 1f))
        assertEquals(1, contentPickerGridColumnCount(411.dp, fontScale = 2f))
    }

    @Test
    fun `picker exposes its search field as a search control`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    ActivityRouteFrame {
                        JournalContentPickerScreenContent(
                            state = JournalContentPickerUiState(),
                        )
                    }
                }
            }

            onNodeWithTag("workspace_search").assertIsDisplayed()
            onAllNodes(hasSetTextAction()).assertCountEquals(1)
        }

    @Test
    fun `picker presents date headings in the user's locale`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val date = LocalDate(2026, 10, 6)
            setContent {
                LogDateTheme {
                    JournalContentPickerScreenContent(
                        state =
                            JournalContentPickerUiState(
                                groups = listOf(JournalContentPickerDateGroup(date, listOf(sampleWriting))),
                            ),
                    )
                }
            }

            onNodeWithText(formatDateLocalized(date)).assertIsDisplayed()
        }

    @Test
    fun `Add existing content opens the nested picker`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var openedPicker = false
            setContent {
                LogDateTheme {
                    JournalDetailScreenContent(
                        uiState =
                            JournalDetailUiState.Success(
                                journalId = Uuid.random(),
                                title = "October notes",
                                entries = emptyList(),
                            ),
                        onGoBack = {},
                        onOpenContentPicker = { openedPicker = true },
                    )
                }
            }

            onNodeWithContentDescription("Add to journal").performClick()
            onNodeWithText("Create entry").assertIsDisplayed()
            onNodeWithText("Add existing content").performClick()

            assertTrue(openedPicker)
        }

    @Test
    fun `compact picker exposes selection and review removal semantics`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val writing =
                JournalContentPickerItem(
                    id = Uuid.random(),
                    kind = JournalContentPickerItemKind.WRITING,
                    timestamp = Instant.parse("2026-10-06T08:00:00Z"),
                    title = "Morning note",
                )
            var selectedItems by mutableStateOf(emptyList<JournalContentPickerItem>())
            setContent {
                LogDateTheme {
                    JournalContentPickerScreenContent(
                        state =
                            JournalContentPickerUiState(
                                journalTitle = "October notes",
                                groups =
                                    listOf(
                                        JournalContentPickerDateGroup(
                                            date = LocalDate(2026, 10, 6),
                                            items = listOf(writing),
                                        ),
                                    ),
                                selectedItems = selectedItems,
                            ),
                        onToggleSelection = { contentId ->
                            selectedItems =
                                if (contentId in selectedItems.map { it.id }) {
                                    selectedItems.filterNot { it.id == contentId }
                                } else {
                                    selectedItems + writing
                                }
                        },
                        onRemoveSelection = { contentId ->
                            selectedItems = selectedItems.filterNot { it.id == contentId }
                        },
                    )
                }
            }

            onNodeWithContentDescription("Writing: Morning note. Not selected").performClick()
            assertTrue(
                onNodeWithContentDescription("Writing: Morning note. Selected")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Selected],
            )
            onNodeWithContentDescription("Remove Morning note from selection").performClick()

            onNodeWithContentDescription("Writing: Morning note. Not selected").assertIsDisplayed()
            onNodeWithContentDescription("Remove Morning note from selection").assertDoesNotExist()
        }
}

private val sampleWriting =
    JournalContentPickerItem(
        id = Uuid.random(),
        kind = JournalContentPickerItemKind.WRITING,
        timestamp = Instant.parse("2026-10-06T08:00:00Z"),
        title = "Morning note",
    )
