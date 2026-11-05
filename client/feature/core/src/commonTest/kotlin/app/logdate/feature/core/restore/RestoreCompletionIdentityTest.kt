package app.logdate.feature.core.restore

import app.logdate.client.domain.restore.PreviewArchiveUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreCompletionIdentityTest {
    @Test
    fun sameArchiveRestoredTwiceProducesDistinctCompletions() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val launcher = CompletionRestoreLauncher()
                val viewModel = UserDataRestoreViewModel(launcher, PreviewArchiveUseCase())
                val summary =
                    RestoreSummary(
                        source = "backup.zip",
                        journalsImported = 1,
                        notesImported = 2,
                        draftsImported = 0,
                        journalLinksImported = 1,
                        mediaImported = 0,
                    )
                launcher.prepareImport(viewModel)
                launcher.completeRestore(RestoreOutcome.Success(summary))
                val first = viewModel.restoreState.value
                launcher.completeRestore(RestoreOutcome.Success(summary))
                assertEquals(first, viewModel.restoreState.value)
                viewModel.dismissSheet()
                launcher.prepareImport(viewModel)
                launcher.completeRestore(RestoreOutcome.Success(summary))

                assertNotEquals(first, viewModel.restoreState.value)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private class CompletionRestoreLauncher : RestoreLauncher {
    override val restoreProgress = MutableStateFlow<RestoreProgressInfo>(RestoreProgressInfo.Idle)
    private var completion: ((RestoreOutcome) -> Unit)? = null
    private var fileSelected: ((ArchiveFileInfo?) -> Unit)? = null

    override fun startFileSelection() = Unit

    override fun startRestore(options: ImportOptions) = Unit

    override fun cancelRestore() = Unit

    override fun setRestoreCompletionCallback(callback: (RestoreOutcome) -> Unit) {
        completion = callback
    }

    override fun setFileSelectedCallback(callback: (ArchiveFileInfo?) -> Unit) {
        fileSelected = callback
    }

    override fun updateProgress(info: RestoreProgressInfo) {
        restoreProgress.value = info
    }

    override fun completeRestore(outcome: RestoreOutcome) {
        completion?.invoke(outcome)
    }

    fun prepareImport(viewModel: UserDataRestoreViewModel) {
        viewModel.selectFile()
        fileSelected?.invoke(ArchiveFileInfo("backup.zip", "test://backup", METADATA))
        viewModel.confirmImport()
    }

    private companion object {
        val METADATA =
            """
            {"version":"1.0","exportDate":"2026-03-20T10:00:00Z","userId":"test-user",
             "deviceId":"test-device","appVersion":"2.1.0","stats":{"journalCount":1,
             "noteCount":2,"draftCount":0,"journalLinksImported":1,"mediaCount":0}}
            """.trimIndent()
    }
}
