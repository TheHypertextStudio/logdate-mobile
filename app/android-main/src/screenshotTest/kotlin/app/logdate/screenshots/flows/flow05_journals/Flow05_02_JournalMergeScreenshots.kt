@file:Suppress("ktlint:standard:filename", "ktlint:standard:function-naming", "ktlint:standard:package-name")

package app.logdate.screenshots.flows.flow05_journals

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.repository.journals.JournalMergeCandidate
import app.logdate.client.repository.journals.JournalMergePreview
import app.logdate.feature.journals.ui.merge.JournalMergeScreenContent
import app.logdate.feature.journals.ui.merge.JournalMergeStage
import app.logdate.feature.journals.ui.merge.JournalMergeUiState
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.shared.model.Journal
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val mergeSource =
    Journal(
        id = Uuid.parse("00000000-0000-0000-0000-000000000201"),
        title = "Summer in the mountains",
        description = "Trail notes, cabin weekends, and the places we stopped along the way.",
        lastUpdated = ScreenshotTestData.baseInstant,
    )
private val mergeDestination =
    Journal(
        id = Uuid.parse("00000000-0000-0000-0000-000000000202"),
        title = "Journeys with friends and family",
        lastUpdated = ScreenshotTestData.baseInstant - 2.days,
    )
private val mergePicker =
    JournalMergeUiState(
        sourceTitle = mergeSource.title,
        source = JournalMergeCandidate(mergeSource, 12),
        loading = false,
        candidates =
            listOf(
                JournalMergeCandidate(mergeDestination, 28),
                JournalMergeCandidate(
                    Journal(
                        id = Uuid.parse("00000000-0000-0000-0000-000000000203"),
                        title = "Weekend field notes",
                        lastUpdated = ScreenshotTestData.baseInstant - 3.days,
                    ),
                    16,
                ),
                JournalMergeCandidate(
                    Journal(
                        id = Uuid.parse("00000000-0000-0000-0000-000000000204"),
                        title = "The road to the coast and everything we found",
                        lastUpdated = ScreenshotTestData.baseInstant - 5.days,
                    ),
                    42,
                ),
            ),
    )
private val mergePreview =
    JournalMergePreview(
        mergeSource,
        mergeDestination,
        (1..12).mapTo(mutableSetOf()) { Uuid.parse("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}") },
        (9..36).mapTo(mutableSetOf()) { Uuid.parse("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}") },
    )

@PreviewTest
@ScreenshotPreviewMatrix
@Preview(name = "Compact Phone", widthDp = 320, heightDp = 640)
@Composable
fun S19_JournalMergePicker() {
    ScreenshotTheme { JournalMergeScreenContent(mergePicker) }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Preview(name = "Compact review", widthDp = 320, heightDp = 891)
@Preview(name = "Phone 200% text", device = ScreenshotTestData.PHONE, fontScale = 2f)
@Preview(name = "Phone RTL", device = ScreenshotTestData.PHONE, locale = "ar")
@Composable
fun S20_JournalMergeReview() {
    ScreenshotTheme { JournalMergeScreenContent(mergePicker.copy(stage = JournalMergeStage.Review(mergePreview))) }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S21_JournalMergeWorkspace() {
    ScreenshotTheme {
        CompositionLocalProvider(LocalWorkspaceSearchAction provides {}) {
            JournalMergeScreenContent(mergePicker)
        }
    }
}
