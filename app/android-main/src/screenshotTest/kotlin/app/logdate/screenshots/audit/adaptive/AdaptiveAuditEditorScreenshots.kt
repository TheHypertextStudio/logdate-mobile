package app.logdate.screenshots.audit.adaptive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.profile.ui.ProfileEditState
import app.logdate.feature.core.profile.ui.ProfileScreenContent
import app.logdate.feature.core.profile.ui.ProfileUiState
import app.logdate.feature.editor.ui.MainEditorContent
import app.logdate.feature.editor.ui.audio.ActiveRecordingDisplay
import app.logdate.feature.editor.ui.audio.AudioRecordingControls
import app.logdate.feature.editor.ui.audio.AudioTranscriptionUi
import app.logdate.feature.editor.ui.audio.AudioUiState
import app.logdate.feature.editor.ui.common.NoteEditorToolbar
import app.logdate.feature.editor.ui.content.EditorBottomContent
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.layout.ImmersiveEditorLayout
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.feature.timeline.ui.details.TimelineDayDetailPanel
import app.logdate.screenshots.common.PostureRouteFrame
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.location.PlaceUiState
import app.logdate.ui.timeline.AudioNoteUiState
import app.logdate.ui.timeline.DayEventUiState
import app.logdate.ui.timeline.ImageNoteUiState
import app.logdate.ui.timeline.TextNoteUiState
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.timeline.VideoNoteUiState
import com.android.tools.screenshot.PreviewTest
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val auditEditorUiState =
    BlocksUiState(
        blocks =
            listOf(
                TextBlockUiState(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000621"),
                    timestamp = ScreenshotTestData.baseInstant,
                    content =
                        "Drafted a field note with enough text to check reading width, scroll behavior, " +
                            "and the position of editor controls on a folding display.",
                ),
                ImageBlockUiState(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000622"),
                    timestamp = ScreenshotTestData.baseInstant,
                    uri = SAMPLE_IMAGE_URI,
                    caption = "Notebook reference",
                ),
                VideoBlockUiState(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000623"),
                    timestamp = ScreenshotTestData.baseInstant,
                    uri = SAMPLE_VIDEO_URI,
                    caption = "Walkthrough clip",
                    durationMs = 84_000L,
                ),
            ),
        availableJournals = ScreenshotTestData.sampleJournals,
        selectedJournalIds = listOf(ScreenshotTestData.sampleJournal.id),
        onBlockFocused = {},
        onJournalSelectionChanged = {},
        onUpdateBlock = {},
        onCreateBlock = { _: BlockType, id: Uuid -> TextBlockUiState(id = id) },
        onDeleteBlock = {},
    )

@Composable
private fun FoldableEntryEditorScene(foldableLayoutInfo: FoldableLayoutInfo) {
    provideFoldableLayoutInfo(foldableLayoutInfo) {
        ScreenshotTheme {
            ImmersiveEditorLayout(
                topBarContent = {
                    NoteEditorToolbar(
                        onBack = {},
                        onSave = {},
                        onShowDrafts = {},
                    )
                },
                editorContent = {
                    MainEditorContent(
                        uiState = auditEditorUiState,
                        shouldReturnToPickerOnBack = false,
                        onDismissExpanded = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                },
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
}

private val auditTimelineDayState =
    TimelineDayUiState(
        summary =
            "Closed the adaptive audit gaps that were still assuming a phone-width canvas, then reviewed " +
                "the foldable screenshots before recording a short follow-up note.",
        date = LocalDate(2025, 2, 20),
        notes =
            listOf(
                TextNoteUiState(
                    noteId = Uuid.parse("00000000-0000-0000-0000-000000000301"),
                    text =
                        "Timeline detail needs room for a readable daily summary and the mixed notes " +
                            "that explain what happened. Book posture keeps those jobs on separate panes.",
                    timestamp = ScreenshotTestData.baseInstant,
                ),
                ImageNoteUiState(
                    noteId = Uuid.parse("00000000-0000-0000-0000-000000000302"),
                    uri = SAMPLE_IMAGE_URI,
                    timestamp = ScreenshotTestData.baseInstant - 28.minutes,
                    caption = "Reference capture for the layout pass.",
                ),
                AudioNoteUiState(
                    noteId = Uuid.parse("00000000-0000-0000-0000-000000000303"),
                    uri = "preview://audio",
                    timestamp = ScreenshotTestData.baseInstant - 48.minutes,
                    duration = 93_000L,
                ),
                VideoNoteUiState(
                    noteId = Uuid.parse("00000000-0000-0000-0000-000000000304"),
                    uri = SAMPLE_VIDEO_URI,
                    thumbnailUri = SAMPLE_IMAGE_URI,
                    timestamp = ScreenshotTestData.baseInstant - 67.minutes,
                    duration = 61_000L,
                    caption = "Playback review",
                ),
            ),
        events =
            listOf(
                DayEventUiState(
                    eventId = "adaptive-review",
                    title = "Adaptive QA review",
                    description = "Checked the timeline, editor, media, and rewind surfaces against the foldable audit.",
                    start = ScreenshotTestData.baseInstant - 2.hours,
                    end = ScreenshotTestData.baseInstant - 1.hours,
                ),
            ),
        placesVisited =
            listOf(
                PlaceUiState(
                    id = "mission-office",
                    title = "Mission office",
                    latitude = 37.7596,
                    longitude = -122.4269,
                ),
                PlaceUiState(
                    id = "ferry-building",
                    title = "Ferry Building",
                    latitude = 37.7955,
                    longitude = -122.3937,
                ),
            ),
    )

private val tabletopPostureLayoutInfo =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Tabletop,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Horizontal,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds =
                    FoldableHingeBounds(
                        left = 0.dp,
                        top = 438.dp,
                        right = 1440.dp,
                        bottom = 462.dp,
                        width = 1440.dp,
                        height = 24.dp,
                    ),
                isSeparating = true,
            ),
    )

private val bookPostureLayoutInfo =
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
                        left = 708.dp,
                        top = 0.dp,
                        right = 732.dp,
                        bottom = 900.dp,
                        width = 24.dp,
                        height = 900.dp,
                    ),
                isSeparating = true,
            ),
    )

private const val BOOK_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val TABLETOP_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val SAMPLE_IMAGE_URI =
    "android.resource://studio.hypertext.logdate.debug/drawable/sample_note_photo"
private const val SAMPLE_VIDEO_URI = "android.resource://studio.hypertext.logdate.debug/mipmap/ic_launcher"

@PreviewTest
@Preview(name = "Audio recording controls tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A60_AudioRecordingControlsTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            AudioRecordingControls(
                recordingState = RecordingState.RECORDING,
                audioLevels = ScreenshotTestData.mockAudioLevels,
                recordingDuration = 1.minutes + 23.seconds,
                onStartRecording = {},
                onStopRecording = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Active recording tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A61_ActiveRecordingTabletopPosture() {
    provideFoldableLayoutInfo(tabletopPostureLayoutInfo) {
        ScreenshotTheme {
            ActiveRecordingDisplay(
                audioLevels = ScreenshotTestData.mockAudioLevels,
                recordingDuration = 2.minutes + 15.seconds,
                onRestart = {},
                onPause = {},
                onFinish = {},
                transcriptionText =
                    "Started capturing notes for the adaptive audit. The active recording surface keeps the " +
                        "live transcript readable while recording controls remain in the reachable lower pane.",
                transcriptionIsRefining = true,
                isPaused = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Entry editor mixed content book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A62_EntryEditorMixedContentBookPosture() {
    FoldableEntryEditorScene(bookPostureLayoutInfo)
}

@PreviewTest
@Preview(name = "Entry editor mixed content tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A63_EntryEditorMixedContentTabletopPosture() {
    FoldableEntryEditorScene(tabletopPostureLayoutInfo)
}

@PreviewTest
@Preview(name = "Audio transcript book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A64_AudioTranscriptBookPosture() {
    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            AudioTranscriptionUi(
                transcriptionState =
                    AudioUiState.TranscriptionState.Success(
                        text =
                            "We walked through the adaptive audit and captured the places where the " +
                                "editor still assumed a phone-sized canvas. The transcript reader keeps " +
                                "long-form generated text constrained to a readable pane while status " +
                                "context stays on the adjacent side of the fold.",
                        isRefining = true,
                    ),
                onRequestTranscription = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Timeline day detail book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A65_TimelineDayDetailBookPosture() {
    PostureRouteFrame(bookPostureLayoutInfo) {
        TimelineDayDetailPanel(
            uiState = auditTimelineDayState,
            onExit = {},
            onOpenEvent = {},
            onOpenLocations = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(name = "Timeline day detail tabletop posture", showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun A66_TimelineDayDetailTabletopPosture() {
    PostureRouteFrame(tabletopPostureLayoutInfo) {
        TimelineDayDetailPanel(
            uiState = auditTimelineDayState,
            onExit = {},
            onOpenEvent = {},
            onOpenLocations = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(name = "Profile edit book posture", showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun A67_ProfileEditBookPosture() {
    val snackbarHostState = remember { SnackbarHostState() }

    provideFoldableLayoutInfo(bookPostureLayoutInfo) {
        ScreenshotTheme {
            ProfileScreenContent(
                uiState =
                    ProfileUiState(
                        localProfile = auditProfile,
                        account = auditAccount,
                        userData = null,
                        editState = ProfileEditState.DisplayName("Alex Johnson"),
                    ),
                onBack = {},
                onStartEditingDisplayName = {},
                onCancelEditing = {},
                onSaveDisplayName = {},
                onSaveBio = {},
                onNavigateToBirthday = {},
                snackbarHostState = snackbarHostState,
            )
        }
    }
}
