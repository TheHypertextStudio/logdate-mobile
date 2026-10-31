package app.logdate.screenshots.components.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.feature.library.ui.LibraryPanel
import app.logdate.feature.library.ui.LibraryScreenContent
import app.logdate.feature.library.ui.LibraryUiState
import app.logdate.feature.library.ui.detail.MediaDetailContent
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTestData.PHONE_LANDSCAPE
import app.logdate.screenshots.common.ScreenshotTestData.TABLET
import app.logdate.screenshots.common.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun LibraryGrid_Empty() {
    ScreenshotTheme {
        LibraryPanel(
            state = LibraryUiState.Empty,
            onItemClick = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun LibraryGrid_PermissionRequired() {
    ScreenshotTheme {
        LibraryPanel(
            state = LibraryUiState.PermissionRequired,
            onItemClick = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun LibraryGrid_Content() {
    ScreenshotTheme {
        LibraryScreenContent(
            state = LibraryScreenshotData.gridContent,
            onItemClick = {},
            onOpenPostcards = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = TABLET)
@Composable
fun LibraryGrid_Content_Tablet() {
    ScreenshotTheme {
        LibraryScreenContent(
            state = LibraryScreenshotData.gridContent,
            onItemClick = {},
            onOpenPostcards = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun LibraryGrid_Audio() {
    ScreenshotTheme {
        LibraryScreenContent(
            state = LibraryScreenshotData.audioGridContent,
            onItemClick = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun MediaDetail_Compact() {
    ScreenshotTheme {
        MediaDetailContent(
            state = LibraryScreenshotData.imageDetail,
            onBack = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = TABLET)
@Composable
fun MediaDetail_Expanded() {
    ScreenshotTheme {
        MediaDetailContent(
            state = LibraryScreenshotData.imageDetail,
            onBack = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE_LANDSCAPE)
@Composable
fun MediaDetail_Landscape() {
    ScreenshotTheme {
        MediaDetailContent(
            state = LibraryScreenshotData.imageDetail,
            onBack = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun MediaDetail_Presenting() {
    ScreenshotTheme {
        MediaDetailContent(
            state = LibraryScreenshotData.imageDetail,
            presenterState = LibraryScreenshotData.presenterActive,
            onBack = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = TABLET)
@Composable
fun MediaDetail_VideoPresenting_Expanded() {
    ScreenshotTheme {
        MediaDetailContent(
            state = LibraryScreenshotData.videoDetail,
            presenterState = LibraryScreenshotData.presenterActive,
            onBack = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun MediaDetail_Audio() {
    ScreenshotTheme {
        MediaDetailContent(
            state = LibraryScreenshotData.audioDetail,
            onBack = {},
        )
    }
}
