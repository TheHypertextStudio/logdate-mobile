package app.logdate.feature.postcards.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.InkTool
import app.logdate.feature.postcards.model.ShapeKind
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.common.CursorType
import app.logdate.ui.common.TooltipBox
import app.logdate.ui.common.cursorIcon
import logdate.client.feature.postcards.generated.resources.Res
import logdate.client.feature.postcards.generated.resources.postcards_delete_shortcut
import logdate.client.feature.postcards.generated.resources.postcards_fullscreen
import logdate.client.feature.postcards.generated.resources.postcards_redo
import logdate.client.feature.postcards.generated.resources.postcards_redo_shortcut
import logdate.client.feature.postcards.generated.resources.postcards_save_shortcut
import logdate.client.feature.postcards.generated.resources.postcards_undo
import logdate.client.feature.postcards.generated.resources.postcards_undo_shortcut
import logdate.client.feature.postcards.generated.resources.postcards_untitled
import logdate.client.ui.generated.resources.common_back
import logdate.client.ui.generated.resources.common_delete
import logdate.client.ui.generated.resources.common_save
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Full-screen immersive canvas editor for creating and editing Postcards.
 *
 * Adapts layout by screen size:
 * - **Compact (phones):** Vertical layout — canvas, shelf, bottom tool bar
 * - **Medium+ (tablets, landscape):** Horizontal layout — side tool rail, canvas, shelf panel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasEditorScreen(
    viewModel: CanvasEditorViewModel = koinViewModel(),
    onNavigateBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val onUndo: () -> Unit = { viewModel.undo() }
    val onRedo: () -> Unit = { viewModel.redo() }
    val onToolSelected: (CanvasTool) -> Unit = { tool -> viewModel.setActiveTool(tool) }

    LaunchedEffect(state.saveError) {
        state.saveError?.let { error ->
            snackbarHostState.showSnackbar(getString(error))
            viewModel.clearSaveError()
        }
    }

    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val useWideLayout = windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            EditorTopBar(
                title = state.document.title,
                onTitleChange = viewModel::setTitle,
                canUndo = viewModel.canUndo,
                canRedo = viewModel.canRedo,
                hasSelection = state.selectedElementId != null,
                onBack = onNavigateBack,
                onUndo = onUndo,
                onRedo = onRedo,
                onDelete = viewModel::deleteSelectedElement,
                onSave = {
                    viewModel.save()
                    onSaved()
                },
                onToggleFullscreen = onToggleFullscreen,
                modifier = Modifier.statusBarsPadding(),
            )
        },
    ) { paddingValues ->
        val viewportState = rememberCanvasViewportState()

        val keyboardModifier =
            Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val isModifier = event.isCtrlPressed || event.isMetaPressed
                when {
                    isModifier && !event.isShiftPressed && event.key == Key.Z -> {
                        onUndo()
                        true
                    }

                    isModifier && event.isShiftPressed && event.key == Key.Z -> {
                        onRedo()
                        true
                    }

                    isModifier && event.key == Key.S -> {
                        viewModel.save()
                        true
                    }

                    event.key == Key.Delete || event.key == Key.Backspace -> {
                        if (state.selectedElementId != null) {
                            viewModel.deleteSelectedElement()
                            true
                        } else {
                            false
                        }
                    }

                    event.key == Key.Escape -> {
                        if (state.isTextEditorVisible) {
                            viewModel.cancelTextEditing()
                        } else {
                            viewModel.selectElement(null)
                        }
                        true
                    }

                    event.key == Key.One -> {
                        onToolSelected(CanvasTool.SELECT)
                        true
                    }

                    event.key == Key.Two -> {
                        onToolSelected(CanvasTool.INK)
                        true
                    }

                    event.key == Key.Three -> {
                        onToolSelected(CanvasTool.SHAPE)
                        true
                    }

                    event.key == Key.Four -> {
                        onToolSelected(CanvasTool.TEXT)
                        true
                    }

                    event.key == Key.Five -> {
                        onToolSelected(CanvasTool.STICKER)
                        true
                    }

                    else -> {
                        false
                    }
                }
            }

        // Editor canvas and tools react to a separating hinge: the canvas keeps to one physical
        // pane while the tool rail/palette and shelf move into the other, clear of the fold.
        // Padding and keyboard shortcuts are applied once on the outer wrapper so the nested
        // posture layouts never double-apply them.
        FoldableTabletopLayout(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .then(keyboardModifier),
            topPane = {
                Box(modifier = Modifier.fillMaxSize()) {
                    CanvasArea(
                        state = state,
                        viewModel = viewModel,
                        viewportState = viewportState,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Text editor slides up over the canvas pane when active
                    TextElementEditorOverlay(
                        state = state,
                        viewModel = viewModel,
                        viewportState = viewportState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            },
            bottomPane = {
                Column(modifier = Modifier.fillMaxSize()) {
                    EditorShelf(
                        mode = state.shelfMode,
                        photos = state.shelfPhotos,
                        browsePhotos = state.browsePhotos,
                        stickers = state.shelfStickers,
                        onModeChange = viewModel::setShelfMode,
                        onPhotoDrag = { photo, x, y -> viewModel.addPhotoElement(photo, x, y) },
                        onStickerTap = { sticker -> viewModel.addStickerElement(sticker.id, 0f, 0f) },
                    )

                    EditorToolPalette(
                        activeTool = state.activeTool,
                        onToolSelected = onToolSelected,
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            },
            standardContent = {
                FoldableBookLayout(
                    startPane = {
                        Box(modifier = Modifier.fillMaxSize()) {
                            CanvasArea(
                                state = state,
                                viewModel = viewModel,
                                viewportState = viewportState,
                                modifier = Modifier.fillMaxSize(),
                            )

                            // Text editor slides up over the canvas pane when active
                            TextElementEditorOverlay(
                                state = state,
                                viewModel = viewModel,
                                viewportState = viewportState,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    },
                    endPane = {
                        Row(modifier = Modifier.fillMaxSize()) {
                            EditorToolRail(
                                activeTool = state.activeTool,
                                onToolSelected = onToolSelected,
                                modifier =
                                    Modifier
                                        .fillMaxHeight()
                                        .navigationBarsPadding(),
                            )

                            EditorShelf(
                                mode = state.shelfMode,
                                photos = state.shelfPhotos,
                                browsePhotos = state.browsePhotos,
                                stickers = state.shelfStickers,
                                onModeChange = viewModel::setShelfMode,
                                onPhotoDrag = { photo, x, y -> viewModel.addPhotoElement(photo, x, y) },
                                onStickerTap = { sticker -> viewModel.addStickerElement(sticker.id, 0f, 0f) },
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .navigationBarsPadding(),
                            )
                        }
                    },
                    standardContent = {
                        if (useWideLayout) {
                            // Tablet / landscape: tool rail on left, canvas center, shelf on right
                            Row(modifier = Modifier.fillMaxSize()) {
                                EditorToolRail(
                                    activeTool = state.activeTool,
                                    onToolSelected = onToolSelected,
                                    modifier =
                                        Modifier
                                            .fillMaxHeight()
                                            .navigationBarsPadding(),
                                )

                                CanvasArea(
                                    state = state,
                                    viewModel = viewModel,
                                    viewportState = viewportState,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )

                                EditorShelf(
                                    mode = state.shelfMode,
                                    photos = state.shelfPhotos,
                                    browsePhotos = state.browsePhotos,
                                    stickers = state.shelfStickers,
                                    onModeChange = viewModel::setShelfMode,
                                    onPhotoDrag = { photo, x, y -> viewModel.addPhotoElement(photo, x, y) },
                                    onStickerTap = { sticker -> viewModel.addStickerElement(sticker.id, 0f, 0f) },
                                    modifier =
                                        Modifier
                                            .width(200.dp)
                                            .fillMaxHeight()
                                            .navigationBarsPadding(),
                                )
                            }
                        } else {
                            // Phone: canvas on top, shelf + tool bar at bottom
                            Column(modifier = Modifier.fillMaxSize()) {
                                CanvasArea(
                                    state = state,
                                    viewModel = viewModel,
                                    viewportState = viewportState,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )

                                // Text editor slides up over the shelf when active
                                TextElementEditorOverlay(
                                    state = state,
                                    viewModel = viewModel,
                                    viewportState = viewportState,
                                )

                                EditorShelf(
                                    mode = state.shelfMode,
                                    photos = state.shelfPhotos,
                                    browsePhotos = state.browsePhotos,
                                    stickers = state.shelfStickers,
                                    onModeChange = viewModel::setShelfMode,
                                    onPhotoDrag = { photo, x, y -> viewModel.addPhotoElement(photo, x, y) },
                                    onStickerTap = { sticker -> viewModel.addStickerElement(sticker.id, 0f, 0f) },
                                )

                                EditorToolPalette(
                                    activeTool = state.activeTool,
                                    onToolSelected = onToolSelected,
                                    modifier = Modifier.navigationBarsPadding(),
                                )
                            }
                        }
                    },
                )
            },
        )
    }
}

/**
 * The main canvas area with viewport, renderer, and tool overlays.
 */
@Composable
private fun CanvasArea(
    state: CanvasEditorState,
    viewModel: CanvasEditorViewModel,
    viewportState: CanvasViewportState,
    modifier: Modifier = Modifier,
) {
    val cursorType =
        when (state.activeTool) {
            CanvasTool.SELECT -> if (state.selectedElementId != null) CursorType.MOVE else CursorType.DEFAULT
            CanvasTool.INK, CanvasTool.SHAPE -> CursorType.CROSSHAIR
            CanvasTool.TEXT -> CursorType.TEXT
            CanvasTool.STICKER -> CursorType.DEFAULT
        }

    Box(modifier = modifier.cursorIcon(cursorType)) {
        val viewportGesturesEnabled =
            state.activeTool != CanvasTool.INK && state.activeTool != CanvasTool.SHAPE

        CanvasViewport(
            state = viewportState,
            gestureEnabled = viewportGesturesEnabled,
        ) {
            CanvasRenderer(
                document = state.document,
                selectedElementId = state.selectedElementId,
                stickerUriMap = state.stickerUriMap,
                viewportOffsetX = viewportState.offset.x,
                viewportOffsetY = viewportState.offset.y,
                onElementTap = { elementId ->
                    val element = state.document.elements.find { it.id == elementId }
                    if (element is CanvasElement.Text) {
                        viewModel.startTextEditing(elementId)
                    } else {
                        viewModel.selectElement(elementId)
                    }
                },
            )
        }

        // Tool overlays with animated crossfade
        AnimatedContent(
            targetState = state.activeTool,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tool-overlay",
        ) { tool ->
            when (tool) {
                CanvasTool.SELECT -> {
                    val selectedId = state.selectedElementId
                    if (selectedId != null) {
                        SelectionOverlay(
                            selectedElementId = selectedId,
                            viewportScale = viewportState.scale,
                            onBeginDrag = viewModel::beginDrag,
                            onMoveElement = viewModel::moveElement,
                            onEndDrag = viewModel::endDrag,
                            onTransformElement = viewModel::transformElement,
                            onDeselect = { viewModel.selectElement(null) },
                        )
                    } else {
                        Box(Modifier)
                    }
                }

                CanvasTool.INK -> {
                    InkCaptureOverlay(
                        tool = InkTool.PEN,
                        color = DEFAULT_STROKE_COLOR,
                        strokeWidth = DEFAULT_INK_WIDTH,
                        onStrokeComplete = { points ->
                            viewModel.addInkStroke(
                                points = points,
                                tool = InkTool.PEN,
                                color = DEFAULT_STROKE_COLOR,
                                strokeWidth = DEFAULT_INK_WIDTH,
                            )
                        },
                    )
                }

                CanvasTool.SHAPE -> {
                    ShapeCaptureOverlay(
                        shapeKind = ShapeKind.RECTANGLE,
                        color = DEFAULT_STROKE_COLOR,
                        strokeWidth = DEFAULT_SHAPE_WIDTH,
                        onShapeComplete = { draft ->
                            viewModel.addShape(
                                shapeKind = draft.kind,
                                x = draft.x,
                                y = draft.y,
                                width = draft.width,
                                height = draft.height,
                                color = DEFAULT_STROKE_COLOR,
                                strokeWidth = DEFAULT_SHAPE_WIDTH,
                            )
                        },
                    )
                }

                CanvasTool.TEXT -> {
                    LaunchedEffect(Unit) {
                        if (!state.isTextEditorVisible) {
                            viewModel.startTextEditing()
                        }
                    }
                    Box(Modifier)
                }

                CanvasTool.STICKER -> {
                    LaunchedEffect(Unit) {
                        viewModel.setShelfMode(ShelfMode.Stickers)
                    }
                    Box(Modifier)
                }
            }
        }
    }
}

/**
 * Text authoring surface that slides up over the canvas while text editing is active.
 *
 * Shared across every posture (phone, book, tabletop) so tapping the TEXT tool or an existing
 * text element always mounts an editor. Uses the single [viewportState] created once in the
 * editor content so confirmed text lands at the current viewport center.
 */
@Composable
private fun TextElementEditorOverlay(
    state: CanvasEditorState,
    viewModel: CanvasEditorViewModel,
    viewportState: CanvasViewportState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = state.isTextEditorVisible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        val editingElement =
            state.editingTextElementId?.let { id ->
                state.document.elements.find { it.id == id } as? CanvasElement.Text
            }
        TextElementEditor(
            initialText = editingElement?.content ?: "",
            initialFont = editingElement?.fontFamily ?: FontChoice.CAVEAT.id,
            initialColor = editingElement?.color ?: DEFAULT_STROKE_COLOR,
            initialFontSize = editingElement?.fontSize ?: DEFAULT_TEXT_FONT_SIZE,
            onConfirm = { content, fontFamily, color, fontSize ->
                val centerX = -viewportState.offset.x / viewportState.scale
                val centerY = -viewportState.offset.y / viewportState.scale
                viewModel.confirmTextEditing(content, fontFamily, color, fontSize, centerX, centerY)
            },
            onDismiss = viewModel::cancelTextEditing,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    title: String,
    onTitleChange: (String) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onToggleFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isEditingTitle by remember { mutableStateOf(false) }
    var editableTitle by remember(title) { mutableStateOf(title) }
    val untitledLabel = stringResource(Res.string.postcards_untitled)

    TopAppBar(
        modifier = modifier,
        title = {
            if (isEditingTitle) {
                BasicTextField(
                    value = editableTitle,
                    onValueChange = { editableTitle = it },
                    singleLine = true,
                    textStyle =
                        MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                    decorationBox = { innerTextField ->
                        if (editableTitle.isEmpty()) {
                            Text(
                                untitledLabel,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    },
                )
            } else {
                Text(
                    text = title.ifEmpty { untitledLabel },
                    style = MaterialTheme.typography.titleMedium,
                    color =
                        if (title.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.clickable { isEditingTitle = true },
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiRes.string.common_back))
            }
        },
        actions = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(stringResource(Res.string.postcards_undo_shortcut)) } },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(Res.string.postcards_undo))
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(stringResource(Res.string.postcards_redo_shortcut)) } },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(Res.string.postcards_redo))
                }
            }
            if (hasSelection) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(stringResource(Res.string.postcards_delete_shortcut)) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(UiRes.string.common_delete))
                    }
                }
            }
            if (onToggleFullscreen != null) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(stringResource(Res.string.postcards_fullscreen)) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(Icons.Filled.Fullscreen, contentDescription = stringResource(Res.string.postcards_fullscreen))
                    }
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = { PlainTooltip { Text(stringResource(Res.string.postcards_save_shortcut)) } },
                state = rememberTooltipState(),
            ) {
                IconButton(onClick = {
                    if (isEditingTitle) {
                        onTitleChange(editableTitle)
                        isEditingTitle = false
                    }
                    onSave()
                }) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(UiRes.string.common_save))
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ),
    )
}

private const val DEFAULT_STROKE_COLOR = "#333333"
private const val DEFAULT_INK_WIDTH = 4f
private const val DEFAULT_SHAPE_WIDTH = 3f
private const val DEFAULT_TEXT_FONT_SIZE = 24f
