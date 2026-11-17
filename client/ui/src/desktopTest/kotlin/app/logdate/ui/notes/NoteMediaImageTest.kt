package app.logdate.ui.notes

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class NoteMediaImageTest {
    @Test
    fun missingPhotoStatesItsFailureInsteadOfAnEmptyBox() =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    NoteMediaImage("", NoteMediaKind.Photo, contentDescription = null, modifier = Modifier.size(240.dp))
                }
            }
            onNodeWithTag(NoteMediaTags.UNAVAILABLE).assertExists()
            onNodeWithText("This photo couldn't be loaded").assertExists()
        }

    @Test
    fun compactMissingVideoCarriesItsFailureAsTheIconDescription() =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    NoteMediaImage("", NoteMediaKind.Video, contentDescription = null, modifier = Modifier.size(56.dp), compact = true)
                }
            }
            onNodeWithContentDescription("This video couldn't be loaded").assertExists()
        }

    @Test
    fun videoIsShownAsAVideoWhereFramesCannotBeDecoded() =
        runComposeUiTest {
            setContent {
                MaterialTheme {
                    NoteMediaImage(
                        mediaRef = "file:///clip.mp4",
                        kind = NoteMediaKind.Video,
                        contentDescription = "Beach walk",
                        modifier = Modifier.size(240.dp),
                    )
                }
            }
            onNodeWithTag(NoteMediaTags.READY).assertExists()
            onNodeWithContentDescription("Beach walk").assertExists()
            onNodeWithText("This video couldn't be loaded").assertDoesNotExist()
        }

    @Test
    fun textThumbnailShowsItsWordsWithoutAnnouncingThemTwice() =
        runComposeUiTest {
            val at = Instant.parse("2026-11-17T12:00:00Z")
            setContent {
                MaterialTheme {
                    NoteThumbnail(JournalNote.Text(creationTimestamp = at, lastUpdated = at, content = "Coffee outside"))
                }
            }
            // Drawn on the tile, but left out of what accessibility services read.
            onNodeWithText("Coffee outside", useUnmergedTree = true).assertExists()
            onNodeWithText("Coffee outside").assertDoesNotExist()
        }
}
