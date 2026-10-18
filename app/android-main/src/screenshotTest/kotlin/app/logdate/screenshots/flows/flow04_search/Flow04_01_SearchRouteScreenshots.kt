package app.logdate.screenshots.flows.flow04_search

import androidx.compose.runtime.Composable
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.SharedScreenshotScene
import app.logdate.screenshots.shared.SharedScreenshotSceneId
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S01_SearchIdleWithRecents() = SharedSearchScene(SharedScreenshotSceneId.SearchIdle)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S02_SearchSearching() = SharedSearchScene(SharedScreenshotSceneId.SearchSearching)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S03_SearchEmpty() = SharedSearchScene(SharedScreenshotSceneId.SearchEmpty)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S04_SearchResults() = SharedSearchScene(SharedScreenshotSceneId.SearchResults)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S05_SearchResultsAdaptive() = SharedSearchScene(SharedScreenshotSceneId.SearchResultsAdaptive)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S06_SearchNarrowingPanel() = SharedSearchScene(SharedScreenshotSceneId.SearchNarrowingPanel)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S07_SearchNarrowingRow() = SharedSearchScene(SharedScreenshotSceneId.SearchNarrowingRow)

@Composable
private fun SharedSearchScene(sceneId: SharedScreenshotSceneId) {
    ScreenshotTheme {
        SharedScreenshotScene(sceneId)
    }
}
