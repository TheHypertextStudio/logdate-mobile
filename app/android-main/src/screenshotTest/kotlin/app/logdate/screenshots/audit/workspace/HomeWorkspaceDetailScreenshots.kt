@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package app.logdate.screenshots.audit.workspace

import androidx.compose.runtime.Composable
import app.logdate.feature.journals.ui.detail.EntryDisplayData
import app.logdate.feature.journals.ui.detail.JournalDetailScreenContent
import app.logdate.feature.journals.ui.detail.JournalDetailUiState
import app.logdate.feature.journals.ui.detail.NoteViewerScaffoldContent
import app.logdate.feature.journals.ui.detail.NoteViewerShared
import app.logdate.feature.journals.ui.detail.TextNoteViewerContent
import app.logdate.feature.library.ui.detail.MediaDetailContent
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryActions
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent
import app.logdate.feature.timeline.ui.details.TimelineDayDetailPanel
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.components.home_timeline.HistoryMapFixture
import app.logdate.screenshots.components.home_timeline.historyDay
import app.logdate.screenshots.components.library.LibraryScreenshotData
import app.logdate.screenshots.flows.flow02_home_timeline.timelineDetailState
import com.android.tools.screenshot.PreviewTest
import kotlin.uuid.Uuid

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceDayDetail() =
    WorkspaceScene(
        "timeline",
        detail = { TimelineDayDetailPanel(timelineDetailState, {}) },
    ) { TimelineFixture() }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceVisitDetail() =
    WorkspaceScene("locations") {
        HumanLocationHistoryContent(
            historyDay.copy(detailVisible = true),
            HumanLocationHistoryActions(),
            toolbarActions = { PlacesOptionsFixture() },
            mapContent = { HistoryMapFixture(it) },
        )
    }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceJournalDetail() =
    WorkspaceScene("journals", detail = {
        JournalDetailScreenContent(
            uiState =
                JournalDetailUiState.Success(
                    ScreenshotTestData.sampleJournal.id,
                    "Everyday moments",
                    listOf(
                        EntryDisplayData.TextEntry(
                            Uuid.parse("00000000-0000-0000-0000-000000000031"),
                            ScreenshotTestData.baseInstant,
                            "A quiet table, a good book, and nowhere to rush.",
                        ),
                    ),
                ),
            onGoBack = {},
            onOpenEditor = {},
            onNavigateToShare = {},
            onNavigateToSettings = {},
            onNavigateToNoteDetail = {},
            onToggleSortOrder = {},
            onRequestDelete = {},
        )
    }) { JournalsFixture() }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceMediaDetail() =
    WorkspaceScene("library", detail = {
        MediaDetailContent(LibraryScreenshotData.imageDetail, onBack = {})
    }) { LibraryFixture() }

@PreviewTest @WorkspacePreviewMatrix @Composable
fun WorkspaceNoteDetail() =
    WorkspaceScene("timeline", detail = {
        val shared =
            NoteViewerShared(
                Uuid.parse("00000000-0000-0000-0000-000000000031"),
                ScreenshotTestData.baseInstant,
                ScreenshotTestData.baseInstant,
                null,
            )
        NoteViewerScaffoldContent(shared, {}) { TextNoteViewerContent("A quiet table, a good book, and nowhere to rush.", shared) }
    }) { TimelineFixture() }
