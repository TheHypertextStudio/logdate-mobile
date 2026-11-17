package app.logdate.screenshots.components.journal

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.feature.journals.ui.creation.JournalCreationScreenContent
import app.logdate.feature.journals.ui.list.JournalsListDialog
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

// ─── Journal Creation ───────────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun JournalCreation_Empty() {
    ScreenshotTheme {
        JournalCreationScreenContent(
            onGoBack = {},
            onNewJournal = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun JournalCreation_Filled() {
    ScreenshotTheme {
        JournalCreationScreenContent(
            onGoBack = {},
            onNewJournal = {},
            initialTitle = "The Willie Diaries",
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun JournalCreation_Empty_Dark() {
    ScreenshotTheme(darkTheme = true) {
        JournalCreationScreenContent(
            onGoBack = {},
            onNewJournal = {},
        )
    }
}

// ─── Journals List Dialog ───────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun JournalsListDialog_Populated() {
    ScreenshotTheme {
        JournalsListDialog(
            journals = ScreenshotTestData.sampleJournals,
            onJournalClick = {},
            onDismiss = {},
        )
    }
}
