package app.logdate.screenshots.flows.flow05_journals

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.feature.journals.ui.JournalLayoutMode
import app.logdate.feature.journals.ui.JournalListItemUiState
import app.logdate.feature.journals.ui.JournalListPanel
import app.logdate.feature.journals.ui.JournalSortOption
import app.logdate.screenshots.common.LargeScreenAuditPreviewMatrix
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

private val sampleJournalItems =
    ScreenshotTestData.sampleJournals.map {
        JournalListItemUiState.ExistingJournal(it)
    } + JournalListItemUiState.CreateJournalPlaceholder

/**
 * Verifies the JournalListPanel surface container adapts across standard device sizes.
 */
@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S15_JournalSurfaceCarousel() {
    ScreenshotTheme {
        JournalListPanel(
            journals = sampleJournalItems,
            layoutMode = JournalLayoutMode.CAROUSEL,
            sortOption = JournalSortOption.LAST_UPDATED,
            onOpenJournal = {},
            onBrowseJournals = {},
            onCreateJournal = {},
            onToggleLayoutMode = {},
            onSortOptionSelected = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Verifies the JournalListPanel surface container in grid mode across standard device sizes.
 */
@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S16_JournalSurfaceGrid() {
    ScreenshotTheme {
        JournalListPanel(
            journals = sampleJournalItems,
            layoutMode = JournalLayoutMode.GRID,
            sortOption = JournalSortOption.LAST_UPDATED,
            onOpenJournal = {},
            onBrowseJournals = {},
            onCreateJournal = {},
            onToggleLayoutMode = {},
            onSortOptionSelected = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Verifies the surface container expands properly on large screens (tablet, split, desktop).
 */
@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun S17_JournalSurfaceCarouselLargeScreen() {
    ScreenshotTheme {
        JournalListPanel(
            journals = sampleJournalItems,
            layoutMode = JournalLayoutMode.CAROUSEL,
            sortOption = JournalSortOption.LAST_UPDATED,
            onOpenJournal = {},
            onBrowseJournals = {},
            onCreateJournal = {},
            onToggleLayoutMode = {},
            onSortOptionSelected = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Verifies the surface container in grid mode expands properly on large screens.
 */
@PreviewTest
@LargeScreenAuditPreviewMatrix
@Composable
fun S18_JournalSurfaceGridLargeScreen() {
    ScreenshotTheme {
        JournalListPanel(
            journals = sampleJournalItems,
            layoutMode = JournalLayoutMode.GRID,
            sortOption = JournalSortOption.LAST_UPDATED,
            onOpenJournal = {},
            onBrowseJournals = {},
            onCreateJournal = {},
            onToggleLayoutMode = {},
            onSortOptionSelected = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
