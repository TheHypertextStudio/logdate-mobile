package app.logdate.feature.editor.ui.content

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.EntryEditorTopBar
import app.logdate.feature.editor.ui.MainEditorContent
import app.logdate.feature.editor.ui.editor.AutoSaveStatus
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.handwriting.EntryFocusPrototypeTools
import app.logdate.feature.editor.ui.handwriting.captureModeForBlock
import app.logdate.feature.editor.ui.handwriting.rememberHandwritingBlockPresentations
import app.logdate.feature.editor.ui.handwriting.sampleInk
import app.logdate.feature.editor.ui.layout.EditorFocus
import app.logdate.feature.editor.ui.layout.ImmersiveEditorLayout
import app.logdate.feature.editor.ui.layout.LocalEditorContextControlsVisible
import app.logdate.feature.editor.ui.layout.editorFocus
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.shared.model.Journal
import app.logdate.ui.theme.LogDateTheme
import org.jetbrains.skia.Image
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class EntryStartScenesTest(
    private val name: String,
    private val width: Int,
    private val height: Int,
) {
    @Test
    fun capture() =
        runDesktopComposeUiTest(width = width, height = height) {
            val now = Clock.System.now()
            val notes =
                if (name.contains("first")) {
                    emptyList()
                } else {
                    listOf(
                        JournalNote.Text(
                            content = "Coffee outside before the day got busy.",
                            creationTimestamp = now,
                            lastUpdated = now,
                        ),
                        JournalNote.Text(
                            content = "Lunch ran longer than planned. Glad it did.",
                            creationTimestamp = now,
                            lastUpdated = now,
                        ),
                    )
                }
            val journal = Journal(title = "Everyday")
            val defaultMode =
                when {
                    name.contains("ink-return") -> EntryStartMode.HANDWRITING
                    name.contains("camera-return") -> EntryStartMode.CAMERA
                    name.contains("audio-return") -> EntryStartMode.AUDIO
                    name.contains("gallery-return") -> EntryStartMode.GALLERY
                    else -> EntryStartMode.TEXT
                }
            val ink = HandwritingBlockUiState(page = sampleInk())
            val text = TextBlockUiState(content = "Took the long way home.\n\nThe light was too good to miss.")
            val content =
                when {
                    name.contains("overview") -> listOf(ink, text)
                    name.contains("ink-focus") -> listOf(ink)
                    name.contains("text-focus") -> listOf(text)
                    else -> emptyList()
                }
            val focused =
                when {
                    name.contains("ink-focus") -> ink.id
                    name.contains("text-focus") -> text.id
                    else -> null
                }
            val state =
                EditorState(
                    blocks = content,
                    expandedBlockId = focused,
                    availableJournals = listOf(journal),
                    selectedJournalIds = listOf(journal.id),
                    todayNotes = notes,
                )
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, if (name.contains("large-text")) 2f else 1f),
                    LocalLayoutDirection provides if (name.contains("rtl")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalEditorContextControlsVisible provides false,
                ) {
                    LogDateTheme(darkTheme = name.contains("dark"), dynamicColor = false) {
                        val presentations =
                            rememberHandwritingBlockPresentations(
                                state,
                                {},
                                if (focused != null) 1f else 0f,
                                width >= 840,
                                adaptToTheme = true,
                                keepInkBelowControls = true,
                            )
                        val immersive = state.editorFocus() is EditorFocus.Drawing
                        val blocks =
                            BlocksUiState(
                                blocks = state.blocks,
                                expandedBlockId = state.expandedBlockId,
                                blockPresentations = presentations,
                                availableJournals = state.availableJournals,
                                selectedJournalIds = state.selectedJournalIds,
                                onBlockFocused = {},
                                onJournalSelectionChanged = {},
                                onUpdateBlock = {},
                                onCreateBlock = { _, id -> TextBlockUiState(id = id) },
                                onDeleteBlock = {},
                                allowHandwritingCreation = true,
                            )
                        ImmersiveEditorLayout(
                            isImmersiveBlockActive = immersive,
                            focusState = state.editorFocus(),
                            bottomContentTopPadding = 0.dp,
                            topBarContent = {
                                EntryEditorTopBar(
                                    state,
                                    immersive,
                                    AutoSaveStatus.IDLE,
                                    onBack = {},
                                    onReturnToEntry = {},
                                    onShowDrafts = {},
                                    onSave = {},
                                    onCaptureMode = {},
                                    onJournalSelection = {},
                                )
                            },
                            editorContent = {
                                MainEditorContent(
                                    blocks,
                                    false,
                                    {},
                                    expandAnySelectedBlock = true,
                                    todayNotes = state.todayNotes,
                                    defaultStartMode = defaultMode,
                                    entryContext = entryDayContext(state),
                                )
                            },
                            bottomContent = {
                                if (state.blocks.isNotEmpty() && !state.isRecordingAudio) {
                                    val mode = captureModeForBlock(state.blocks.firstOrNull { it.id == state.expandedBlockId })
                                    EntryFocusPrototypeTools(mode, {})
                                }
                            },
                        )
                    }
                }
            }
            waitForIdle()
            val root = onRoot()
            File("build/reports/entry-start/$name.png").apply {
                parentFile.mkdirs()
                writeBytes(Image.makeFromBitmap(root.captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
            }
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> =
            listOf(
                arrayOf("phone-first", 360, 800),
                arrayOf("phone-today", 360, 800),
                arrayOf("tablet-first", 1280, 800),
                arrayOf("tablet-today", 1280, 800),
                arrayOf("phone-dark-today", 360, 800),
                arrayOf("phone-large-text-today", 360, 800),
                arrayOf("phone-rtl-today", 360, 800),
                arrayOf("landscape-today", 800, 360),
                arrayOf("phone-ink-return", 360, 800),
                arrayOf("phone-camera-return", 360, 800),
                arrayOf("phone-audio-return", 360, 800),
                arrayOf("phone-gallery-return", 360, 800),
                arrayOf("phone-overview", 360, 800),
                arrayOf("tablet-overview", 1280, 800),
                arrayOf("phone-text-focus", 360, 800),
                arrayOf("phone-ink-focus", 360, 800),
            )
    }
}
