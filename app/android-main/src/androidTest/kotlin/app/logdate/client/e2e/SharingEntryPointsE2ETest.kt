package app.logdate.client.e2e

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.feature.journals.ui.share.ShareJournalContent
import app.logdate.feature.library.ui.detail.MediaDetailContent
import app.logdate.feature.library.ui.detail.MediaDetailUiState
import app.logdate.shared.model.Journal
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.timeline.MediaObjectUiState
import app.logdate.ui.timeline.TimelineSuggestionBlock
import app.logdate.ui.timeline.TimelineSuggestionBlockType
import app.logdate.ui.timeline.TimelineSuggestionBlockUiState
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Instrumented tests for various sharing entry points across the application.
 *
 * This suite verifies that sharing actions in the timeline, media detail view,
 * and journal screens correctly capture and emit the expected state (like
 * memory details, media references, or journal metadata) to their respective
 * share handlers.
 */
@RunWith(AndroidJUnit4::class)
class SharingEntryPointsE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ShareEntryPointHostActivity>()

    @Test
    fun `timeline memory share action emits current memory recall state`() {
        val memoryState =
            TimelineSuggestionBlockUiState(
                type = TimelineSuggestionBlockType.MEMORY_RECALL,
                message = "Trip to the coast",
                memoryDate = LocalDate(2024, 7, 4),
                mediaUris = listOf(MediaObjectUiState(uri = "content://media/coast.jpg", uid = "coast")),
                people = listOf("Lane"),
            )
        var sharedState: TimelineSuggestionBlockUiState? = null

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    TimelineSuggestionBlock(
                        state = memoryState,
                        onStartWriting = {},
                        onOpenDraft = {},
                        onViewMemoryDay = {},
                        onShareMemory = { sharedState = it },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timeline_memory_share_action").assertIsDisplayed().performClick()

        assertNotNull(sharedState)
        assertEquals(memoryState, sharedState)
    }

    @Test
    fun `media detail share action emits current media reference`() {
        val mediaRef = "content://media/external/images/media/42"
        var sharedMediaRef: String? = null

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    MediaDetailContent(
                        state =
                            MediaDetailUiState.ImageContent(
                                mediaId = Uuid.random(),
                                mediaRef = mediaRef,
                                createdAt = Clock.System.now(),
                                location = null,
                            ),
                        onBack = {},
                        onShare = { sharedMediaRef = it },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("media_detail_share_action").assertIsDisplayed().performClick()

        assertEquals(mediaRef, sharedMediaRef)
    }

    @Test
    fun `journal share actions emit current journal state`() {
        val journal = Journal(title = "Road Trip")
        var qrJournal: Journal? = null
        var sharedJournal: Journal? = null

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    ShareJournalContent(
                        journal = journal,
                        linkSharingAvailable = true,
                        onShareToInstagram = {},
                        onShareQrCode = { qrJournal = journal },
                        onShareJournal = { sharedJournal = journal },
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share_journal_qr_action").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("share_journal_sheet_action").assertIsDisplayed().performClick()

        assertEquals(journal, qrJournal)
        assertEquals(journal, sharedJournal)
    }

    @Test
    fun `journal share offers no link when the server cannot host shared journals`() {
        val journal = Journal(title = "Road Trip")
        var instagramShares = 0

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    ShareJournalContent(
                        journal = journal,
                        linkSharingAvailable = false,
                        onShareToInstagram = { instagramShares++ },
                        onShareQrCode = {},
                        onShareJournal = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share_journal_qr_action").assertDoesNotExist()
        composeRule.onNodeWithTag("share_journal_sheet_action").assertDoesNotExist()
        composeRule.onNodeWithText("Only you can see what’s inside this journal.").assertIsDisplayed()
        composeRule.onNodeWithTag("share_journal_instagram_action").assertIsDisplayed().performClick()

        assertEquals(1, instagramShares)
    }

    @Test
    fun `journal share shows its saved cover photo`() {
        val journal =
            Journal(
                title = "Life with Milo",
                coverImageUri = "android.resource://studio.hypertext.logdate.debug/drawable/sample_note_photo",
            )

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    ShareJournalContent(
                        journal = journal,
                        linkSharingAvailable = false,
                        onShareToInstagram = {},
                        onShareQrCode = {},
                        onShareJournal = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val cover = composeRule.onNodeWithTag("share_journal_cover_image")
        cover.assertExists()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val pixels = cover.captureToImage().toPixelMap()
            val upperGreen = pixels[pixels.width / 4, pixels.height / 6].green
            val lowerGreen = pixels[pixels.width / 2, pixels.height * 5 / 6].green
            kotlin.math.abs(upperGreen - lowerGreen) > 0.1f
        }
    }

    @Test
    fun `book sharing keeps the journal preview left and access options right`() {
        val journal = Journal(title = "Life with Milo")

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    BoxWithConstraints(modifier = Modifier.requiredSize(width = 1280.dp, height = 800.dp)) {
                        val hingeLeft = maxWidth / 2 - 12.dp
                        val foldableLayoutInfo =
                            FoldableLayoutInfo(
                                isFoldable = true,
                                posture = FoldablePosture.Book,
                                hinge =
                                    FoldableHingeInfo(
                                        orientation = FoldableHingeOrientation.Vertical,
                                        state = FoldableHingeState.HalfOpened,
                                        occlusionType = FoldableOcclusionType.Full,
                                        bounds =
                                            FoldableHingeBounds(
                                                left = hingeLeft,
                                                top = 0.dp,
                                                right = hingeLeft + 24.dp,
                                                bottom = maxHeight,
                                                width = 24.dp,
                                                height = maxHeight,
                                            ),
                                        isSeparating = true,
                                    ),
                            )
                        provideFoldableLayoutInfo(foldableLayoutInfo) {
                            ShareJournalContent(
                                journal = journal,
                                linkSharingAvailable = false,
                                onShareToInstagram = {},
                                onShareQrCode = {},
                                onShareJournal = {},
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val preview = composeRule.onNodeWithTag("share_journal_preview").fetchSemanticsNode().boundsInRoot
        val access = composeRule.onNodeWithTag("share_journal_access").fetchSemanticsNode().boundsInRoot
        val share = composeRule.onNodeWithTag("share_journal_instagram_action").fetchSemanticsNode().boundsInRoot

        assertTrue(preview.right < access.left)
        assertTrue(preview.width / composeRule.activity.resources.displayMetrics.density >= 380f)
        assertTrue(access.left < share.right)
    }

    @Test
    fun `wide tablet sharing gives the preview a full pane`() {
        val journal = Journal(title = "Life with Milo")

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    ShareJournalContent(
                        journal = journal,
                        linkSharingAvailable = false,
                        onShareToInstagram = {},
                        onShareQrCode = {},
                        onShareJournal = {},
                        modifier = Modifier.requiredSize(width = 1280.dp, height = 800.dp),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val preview = composeRule.onNodeWithTag("share_journal_preview").fetchSemanticsNode().boundsInRoot
        val access = composeRule.onNodeWithTag("share_journal_access").fetchSemanticsNode().boundsInRoot

        assertTrue(preview.right < access.left)
        assertTrue(preview.width / composeRule.activity.resources.displayMetrics.density >= 380f)
    }

    @Test
    fun `short landscape sharing keeps the preview and actions visible together`() {
        val journal = Journal(title = "Life with Milo")

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                LogDateTheme(dynamicColor = false) {
                    ShareJournalContent(
                        journal = journal,
                        linkSharingAvailable = false,
                        onShareToInstagram = {},
                        onShareQrCode = {},
                        onShareJournal = {},
                        modifier = Modifier.requiredSize(width = 900.dp, height = 400.dp),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        val preview = composeRule.onNodeWithTag("share_journal_preview").fetchSemanticsNode().boundsInRoot
        val access = composeRule.onNodeWithTag("share_journal_access").fetchSemanticsNode().boundsInRoot
        val share = composeRule.onNodeWithTag("share_journal_instagram_action").fetchSemanticsNode().boundsInRoot

        assertTrue(preview.right < access.left, "preview=$preview, access=$access")
        assertTrue(share.top < preview.bottom, "share=$share, preview=$preview")
    }
}
