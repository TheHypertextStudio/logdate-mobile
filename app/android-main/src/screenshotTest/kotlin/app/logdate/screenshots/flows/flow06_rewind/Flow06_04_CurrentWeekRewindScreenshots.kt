@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package app.logdate.screenshots.flows.flow06_rewind

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.logdate.feature.rewind.ui.RewindScreenContent
import app.logdate.feature.rewind.ui.overview.RewindOverviewPeriod
import app.logdate.feature.rewind.ui.overview.RewindOverviewScreenUiState
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
import app.logdate.feature.rewind.ui.overview.RewindSceneMemory
import app.logdate.screenshots.common.OsIntegrationPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.common.formatting.LocalToday
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.android.tools.screenshot.PreviewTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun S01_CurrentWeekRewindReady() {
    ScreenshotTheme {
        val context = LocalContext.current
        val image =
            remember(context) {
                BitmapFactory.decodeResource(context.resources, app.logdate.client.R.drawable.workspace_sample_photo).asImage()
            }
        val handler = remember(image) { AsyncImagePreviewHandler { image } }
        CompositionLocalProvider(
            LocalAsyncImagePreviewHandler provides handler,
            LocalToday provides LocalDate(2026, 10, 28),
        ) {
            RewindScreenContent(
                state =
                    RewindOverviewScreenUiState.Ready(
                        pastRewinds = emptyList(),
                        mostRecentRewind =
                            RewindPreviewUiState(
                                rewindId = Uuid.parse("00000000-0000-0000-0000-000000000044"),
                                message = "A quiet walk and a slower morning.",
                                label = "This week so far",
                                title = "A little room to breathe",
                                start = LocalDate(2026, 10, 26),
                                end = LocalDate(2026, 10, 28),
                                rewindAvailable = true,
                                entryCount = 3,
                                photoCount = 1,
                                audioCount = 1,
                                memories =
                                    listOf(
                                        RewindSceneMemory.Visual(
                                            "android.resource://studio.hypertext.logdate.debug/drawable/workspace_sample_photo",
                                        ),
                                        RewindSceneMemory.Writing("I took the long way home and stopped by the park."),
                                        RewindSceneMemory.Recording(42000, "A few thoughts from the walk home."),
                                    ),
                                heroImageUri = "android.resource://studio.hypertext.logdate.debug/drawable/workspace_sample_photo",
                            ),
                    ),
                onOpenRewind = {},
                selectedPeriod = RewindOverviewPeriod.ThisWeek,
                onSelectPeriod = {},
                onRefreshCurrentWeek = {},
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
        }
    }
}

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun S02_CurrentWeekRewindEmpty() {
    ScreenshotTheme {
        CompositionLocalProvider(
            LocalToday provides LocalDate(2026, 10, 28),
        ) {
            RewindScreenContent(
                state = RewindOverviewScreenUiState.NotReady(emptyList()),
                onOpenRewind = {},
                selectedPeriod = RewindOverviewPeriod.ThisWeek,
                onSelectPeriod = {},
                onCreateMemory = {},
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
        }
    }
}

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun S03_CurrentWeekTextMemories() {
    ScreenshotTheme {
        CompositionLocalProvider(
            LocalToday provides LocalDate(2026, 10, 28),
        ) {
            RewindScreenContent(
                state =
                    RewindOverviewScreenUiState.Ready(
                        pastRewinds = emptyList(),
                        mostRecentRewind =
                            RewindPreviewUiState(
                                rewindId = Uuid.parse("00000000-0000-0000-0000-000000000044"),
                                message = "A quiet walk and a slower morning.",
                                label = "This week so far",
                                title = "October 26–28",
                                start = LocalDate(2026, 10, 26),
                                end = LocalDate(2026, 10, 28),
                                rewindAvailable = true,
                                highlightedQuote = "I took the long way home and stopped by the park.",
                                memories =
                                    listOf(
                                        RewindSceneMemory.Writing("I took the long way home and stopped by the park."),
                                        RewindSceneMemory.Writing("A slower start. The walk was worth keeping."),
                                    ),
                            ),
                    ),
                onOpenRewind = {},
                selectedPeriod = RewindOverviewPeriod.ThisWeek,
                onSelectPeriod = {},
                onRefreshCurrentWeek = {},
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
        }
    }
}
