package app.logdate.screenshots.flows.flow03_editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.MainEditorContent
import app.logdate.feature.editor.ui.common.NoteEditorToolbar
import app.logdate.feature.editor.ui.content.EditorBottomContent
import app.logdate.feature.editor.ui.content.EmptyEditorStateContent
import app.logdate.feature.editor.ui.dialog.DraftsBottomSheet
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.layout.ImmersiveEditorLayout
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.screenshots.common.BubbleWindowPreview
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val sampleImageUri = "android.resource://studio.hypertext.logdate.debug/mipmap/ic_launcher"
private const val sampleVideoUri = sampleImageUri

private val editorCreatedAt = Instant.fromEpochMilliseconds(1_740_000_000_000L)

private val sampleDrafts =
    listOf(
        EntryDraft(
            id = Uuid.parse("00000000-0000-0000-0000-000000000111"),
            notes =
                listOf(
                    JournalNote.Text(
                        uid = Uuid.parse("00000000-0000-0000-0000-000000000112"),
                        creationTimestamp = editorCreatedAt,
                        lastUpdated = editorCreatedAt,
                        content = "Finished the route-level screenshot harness.",
                    ),
                ),
            createdAt = editorCreatedAt,
            updatedAt = editorCreatedAt,
        ),
        EntryDraft(
            id = Uuid.parse("00000000-0000-0000-0000-000000000113"),
            notes =
                listOf(
                    JournalNote.Text(
                        uid = Uuid.parse("00000000-0000-0000-0000-000000000114"),
                        creationTimestamp = editorCreatedAt,
                        lastUpdated = editorCreatedAt,
                        content = "Need to rerun the screenshot compiler after the sync refactor settles.",
                    ),
                ),
            createdAt = editorCreatedAt,
            updatedAt = editorCreatedAt,
        ),
    )

@PreviewTest
@ScreenshotPreviewMatrix
@BubbleWindowPreview
@Composable
fun S01_EntryEditorEmpty() {
    EditorRouteFrame {
        EmptyEditorStateContent(
            onStartTextBlock = {},
            onStartPhotoBlock = {},
            onStartAudioBlock = {},
            onStartCameraBlock = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@BubbleWindowPreview
@Composable
fun S02_EntryEditorListMixedContent() {
    EditorRouteFrame {
        MainEditorContent(
            uiState = previewListUiState(),
            shouldReturnToPickerOnBack = false,
            onDismissExpanded = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S05_EntryEditorDraftsDialog() {
    EditorRouteFrame {
        MainEditorContent(
            uiState = previewListUiState(),
            shouldReturnToPickerOnBack = false,
            onDismissExpanded = {},
            modifier = Modifier.fillMaxSize(),
        )
        DraftsBottomSheet(
            drafts = sampleDrafts,
            onDismiss = {},
            onDraftSelected = { _: EntryDraft -> },
            onDraftDeleted = { _: Uuid -> },
            onDeleteAllDrafts = {},
        )
    }
}

@Composable
private fun EditorRouteFrame(content: @Composable () -> Unit) {
    ScreenshotTheme {
        ImmersiveEditorLayout(
            topBarContent = {
                NoteEditorToolbar(
                    onBack = {},
                    onSave = {},
                    onShowDrafts = {},
                )
            },
            editorContent = content,
            bottomContent = {
                EditorBottomContent(
                    availableJournals = ScreenshotTestData.sampleJournals,
                    selectedJournalIds = listOf(ScreenshotTestData.sampleJournal.id),
                    onJournalSelectionChanged = {},
                    journalSelectorExpanded = false,
                    onJournalSelectorExpandedChange = {},
                )
            },
        )
    }
}

private fun previewListUiState() =
    BlocksUiState(
        blocks =
            listOf(
                TextBlockUiState(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000101"),
                    content = "Wrapped the gallery picker in a calmer in-editor surface.",
                ),
                ImageBlockUiState(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000102"),
                    uri = sampleImageUri,
                    caption = "Workspace",
                ),
                VideoBlockUiState(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000103"),
                    uri = sampleVideoUri,
                    caption = "Golden hour commute",
                    durationMs = 84_000L,
                ),
            ),
        expandedBlockId = null,
        availableJournals = ScreenshotTestData.sampleJournals,
        selectedJournalIds = listOf(ScreenshotTestData.sampleJournal.id),
        onBlockFocused = {},
        onJournalSelectionChanged = {},
        onUpdateBlock = {},
        onCreateBlock = { _: BlockType, _: Uuid -> error("Not used in screenshots") },
        onDeleteBlock = {},
    )
