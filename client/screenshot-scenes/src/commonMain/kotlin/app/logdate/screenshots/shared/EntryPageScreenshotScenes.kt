package app.logdate.screenshots.shared

import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.handwriting.EntryPageScene

/**
 * The page a new entry opens on, drawn by the editor itself: empty, with its row of ways to add
 * something, and with something written on it.
 */
internal val entryPageScenes =
    listOf(
        sharedScene(SharedScreenshotSceneId.EntryPageEmpty, ScreenshotSceneGroup.EDITOR, standardMatrixVariants) {
            EntryPageScene()
        },
        sharedScene(SharedScreenshotSceneId.EntryPageWithWriting, ScreenshotSceneGroup.EDITOR, standardMatrixVariants) {
            EntryPageScene(
                blocks = listOf(TextBlockUiState(content = "Took the long way home.\n\nThe light was too good to miss.")),
            )
        },
    )
