package app.logdate.feature.editor.ui.content

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.ui.notes.NoteMediaTags
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class EntryStartContentTest {
    @Test
    fun `saved default makes that capture action primary without creating a block`() {
        val modes =
            mapOf(
                EntryStartMode.HANDWRITING to BlockType.HANDWRITING,
                EntryStartMode.CAMERA to BlockType.CAMERA,
                EntryStartMode.AUDIO to BlockType.AUDIO,
                EntryStartMode.GALLERY to BlockType.IMAGE,
            )
        for ((mode, expected) in modes) {
            for (fontScale in listOf(1f, 2f)) {
                runDesktopComposeUiTest(width = 360, height = 800) {
                    var created: BlockType? = null
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                            LogDateTheme(dynamicColor = false) {
                                EntryStartContent(emptyList(), { type, _ -> created = type }, {}, defaultMode = mode)
                            }
                        }
                    }
                    assertNull(created)
                    // The tile center can be inside its nested alternative-mode button.
                    onNodeWithTag("entry_start_primary").assertIsDisplayed().performTouchInput {
                        click(Offset(width * 0.15f, height * 0.15f))
                    }
                    assertEquals(expected, created)
                }
            }
        }
    }

    @Test
    fun `writing is ready without choosing a mode or creating content`() {
        for (fontScale in listOf(1f, 2f)) {
            runDesktopComposeUiTest(width = 360, height = 800) {
                var created: BlockType? = null
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        LogDateTheme(dynamicColor = false) {
                            EntryStartContent(emptyList(), { type, _ -> created = type }, {})
                        }
                    }
                }
                assertNull(created)
                onNodeWithTag("entry_start_writing").assertIsDisplayed().performTouchInput {
                    click(Offset(width * 0.15f, height * 0.15f))
                }
                assertEquals(BlockType.TEXT, created)
            }
        }
    }

    @Test
    fun `handwriting remains a secondary capture action`() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            var created: BlockType? = null
            setContent {
                LogDateTheme(dynamicColor = false) {
                    EntryStartContent(emptyList(), { type, _ -> created = type }, {})
                }
            }
            onNodeWithTag("editor_start_handwriting_block").assertIsDisplayed().performClick()
            assertEquals(BlockType.HANDWRITING, created)
        }

    @Test
    fun `existing notes are visible context and only open when selected`() =
        runDesktopComposeUiTest(width = 840, height = 700) {
            val note =
                JournalNote.Text(
                    content = "Coffee outside before the day got busy.",
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                )
            var opened: Uuid? = null
            var created: BlockType? = null
            setContent {
                LogDateTheme(dynamicColor = false) {
                    EntryStartContent(listOf(note), { type, _ -> created = type }, { opened = it })
                }
            }
            onNodeWithText(note.content).assertIsDisplayed().performClick()
            assertEquals(note.uid, opened)
            assertNull(created)
            onNodeWithTag("entry_start_writing").assertIsDisplayed()
        }

    @Test
    fun `an earlier photo shows the photo, never the word Photo`() =
        runDesktopComposeUiTest(width = 840, height = 700) {
            val note =
                JournalNote.Image(
                    mediaRef = "logdate-media://images/coffee.jpg",
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                )
            setContent {
                LogDateTheme(dynamicColor = false) {
                    EntryStartContent(listOf(note), { _, _ -> }, {})
                }
            }
            val inCard = hasAnyAncestor(hasTestTag("earlier_note_${note.uid}"))
            onNode(hasText("Photo") and inCard, useUnmergedTree = true).assertDoesNotExist()
            onAllNodes(
                (hasTestTag(NoteMediaTags.LOADING) or hasTestTag(NoteMediaTags.READY) or hasTestTag(NoteMediaTags.UNAVAILABLE)) and inCard,
                useUnmergedTree = true,
            ).assertCountEquals(1)
        }

    @Test
    fun `an earlier video shows its frame, never the word Video`() =
        runDesktopComposeUiTest(width = 840, height = 700) {
            val note =
                JournalNote.Video(
                    mediaRef = "logdate-media://videos/walk.mp4",
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                )
            setContent {
                LogDateTheme(dynamicColor = false) {
                    EarlierEntryContext(listOf(note))
                }
            }
            val inCard = hasAnyAncestor(hasTestTag("earlier_note_${note.uid}"))
            onNode(hasText("Video") and inCard, useUnmergedTree = true).assertDoesNotExist()
            onAllNodes(
                (hasTestTag(NoteMediaTags.LOADING) or hasTestTag(NoteMediaTags.READY) or hasTestTag(NoteMediaTags.UNAVAILABLE)) and inCard,
                useUnmergedTree = true,
            ).assertCountEquals(1)
        }
}
