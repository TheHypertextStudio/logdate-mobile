package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.sync.SyncStatus
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class JournalMergeRecoveryTest {
    @Test
    fun `sync settings offers a new destination only for retained pending merge issue`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val operation =
                JournalMergeOperation(
                    Uuid.random(),
                    Uuid.random(),
                    Uuid.random(),
                    emptySet(),
                    JournalMergeScope("owner", "server"),
                    "Summer",
                    "Travel",
                    needsDestination = true,
                )
            var recovered: JournalMergeOperation? = null
            setContent {
                LogDateTheme {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = SyncStatus(true, null, 1, false, true),
                        isAuthenticated = true,
                        onNavigateToSignIn = {},
                        snackbarHostState = SnackbarHostState(),
                        mergeIssues = listOf(operation),
                        onRecoverJournalMerge = { recovered = it },
                    )
                }
            }
            onNodeWithText("Choose destination").performScrollTo().performClick()
            assertEquals(operation, recovered)
        }
}
