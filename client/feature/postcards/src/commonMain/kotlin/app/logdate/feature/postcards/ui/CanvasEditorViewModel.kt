package app.logdate.feature.postcards.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.client.repository.postcards.Postcard
import app.logdate.client.repository.postcards.PostcardRepository
import app.logdate.client.repository.stickers.StickerRepository
import app.logdate.feature.postcards.model.CanvasBackground
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.ElementTransform
import app.logdate.feature.postcards.model.PostcardDocument
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logdate.client.feature.postcards.generated.resources.Res
import logdate.client.feature.postcards.generated.resources.postcards_save_failed
import logdate.client.feature.postcards.generated.resources.postcards_untitled
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The currently active editing tool.
 */
enum class CanvasTool {
    SELECT,
    INK,
    SHAPE,
    TEXT,
    STICKER,
}

/**
 * State of the content shelf at the bottom of the editor.
 */
sealed interface ShelfMode {
    data object Photos : ShelfMode

    data object Stickers : ShelfMode

    data object Browse : ShelfMode
}

/**
 * Mutable state of the canvas editor.
 */
data class CanvasEditorState(
    val document: PostcardDocument,
    val selectedElementId: Uuid? = null,
    val activeTool: CanvasTool = CanvasTool.SELECT,
    val shelfMode: ShelfMode = ShelfMode.Photos,
    val shelfPhotos: List<ShelfPhoto> = emptyList(),
    val isSaving: Boolean = false,
    val isNewPostcard: Boolean = true,
    val saveError: StringResource? = null,
    val editingTextElementId: Uuid? = null,
    val isTextEditorVisible: Boolean = false,
    val shelfStickers: List<StickerShelfItem> = emptyList(),
    val stickerUriMap: Map<Uuid, String> = emptyMap(),
    val browsePhotos: List<ShelfPhoto> = emptyList(),
)

/**
 * A photo available on the shelf for placement onto the canvas.
 */
data class ShelfPhoto(
    val mediaUri: String,
    val momentRef: Uuid,
)

/**
 * A sticker available on the shelf for placement onto the canvas.
 */
data class StickerShelfItem(
    val id: Uuid,
    val imageUri: String,
    val label: String?,
)

/**
 * ViewModel for the canvas editor screen.
 *
 * Manages the document state, undo/redo history, tool selection, and shelf content.
 */
class CanvasEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val postcardRepository: PostcardRepository,
    private val stickerRepository: StickerRepository,
    private val indexedMediaRepository: IndexedMediaRepository,
) : ViewModel() {
    private val postcardId: Uuid? =
        savedStateHandle
            .get<String>("postcardId")
            ?.let { Uuid.parse(it) }

    private val sourceMomentRef: Uuid? =
        savedStateHandle
            .get<String>("sourceMomentRef")
            ?.let { Uuid.parse(it) }

    private val json = PostcardDocument.json

    private val _state = MutableStateFlow(createInitialState())
    val state: StateFlow<CanvasEditorState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<PostcardDocument>()
    private val redoStack = ArrayDeque<PostcardDocument>()

    init {
        if (postcardId != null) {
            loadExistingPostcard(postcardId)
        }
        loadStickers()
        loadBrowsePhotos()
    }

    private fun loadStickers() {
        stickerRepository
            .observeAllStickers()
            .onEach { stickers ->
                val items =
                    stickers.map { sticker ->
                        StickerShelfItem(
                            id = sticker.id,
                            imageUri = sticker.imageUri,
                            label = sticker.label,
                        )
                    }
                val uriMap = stickers.associate { it.id to it.imageUri }
                _state.update { it.copy(shelfStickers = items, stickerUriMap = uriMap) }
            }.launchIn(viewModelScope)
    }

    private fun loadBrowsePhotos() {
        indexedMediaRepository
            .observeAllMedia()
            .onEach { mediaList ->
                val photos =
                    mediaList
                        .filterIsInstance<IndexedMedia.Image>()
                        .map { image ->
                            ShelfPhoto(
                                mediaUri = image.uri,
                                momentRef = image.uid,
                            )
                        }
                _state.update {
                    it.copy(
                        browsePhotos = photos,
                        // Auto-populate the Photos shelf if it's empty (first load)
                        shelfPhotos = it.shelfPhotos.ifEmpty { photos },
                    )
                }
            }.launchIn(viewModelScope)
    }

    private fun createInitialState(): CanvasEditorState {
        val now = Clock.System.now()
        val document =
            PostcardDocument(
                id = postcardId ?: Uuid.random(),
                title = "",
                createdAt = now,
                modifiedAt = now,
                sourceMomentRef = sourceMomentRef,
                background = CanvasBackground.SolidColor(DEFAULT_CANVAS_BACKGROUND),
            )
        return CanvasEditorState(
            document = document,
            isNewPostcard = postcardId == null,
        )
    }

    private fun loadExistingPostcard(id: Uuid) {
        viewModelScope.launch {
            val postcard = postcardRepository.getPostcard(id) ?: return@launch
            try {
                val document =
                    json.decodeFromString(
                        PostcardDocument.serializer(),
                        postcard.documentJson,
                    )
                _state.update { it.copy(document = document, isNewPostcard = false) }
            } catch (e: Exception) {
                Napier.e("Failed to parse postcard document, starting fresh", e)
            }
        }
    }

    // --- Element operations ---

    /**
     * Adds a photo element from the shelf onto the canvas at the given position.
     */
    fun addPhotoElement(
        shelfPhoto: ShelfPhoto,
        x: Float,
        y: Float,
    ) {
        pushUndo()
        val element =
            CanvasElement.Photo(
                id = Uuid.random(),
                momentRef = shelfPhoto.momentRef,
                mediaUri = shelfPhoto.mediaUri,
                transform = ElementTransform(x = x, y = y),
                zIndex = nextZIndex(),
            )
        updateDocument { doc ->
            doc.copy(elements = doc.elements + element)
        }
    }

    /**
     * Selects an element by ID, or deselects if null.
     */
    fun selectElement(elementId: Uuid?) {
        _state.update { it.copy(selectedElementId = elementId) }
    }

    /**
     * Records one undo entry at the start of a drag, pinch or rotate gesture.
     * Call this once before the first [moveElement] or [transformElement] of the gesture.
     */
    fun beginGesture() {
        pushUndo()
    }

    /**
     * Moves an element by the given delta.
     * Does not push undo — call [beginGesture] once at the start of the gesture.
     */
    fun moveElement(
        elementId: Uuid,
        dx: Float,
        dy: Float,
    ) {
        updateDocument { doc ->
            doc.copy(
                elements =
                    doc.elements.map { el ->
                        if (el.id == elementId) {
                            el.withTransform(
                                el.transform.copy(
                                    x = el.transform.x + dx,
                                    y = el.transform.y + dy,
                                ),
                            )
                        } else {
                            el
                        }
                    },
            )
        }
    }

    /**
     * Scales and rotates an element by the given deltas.
     * Does not push undo — call [beginGesture] once at the start of the gesture.
     */
    fun transformElement(
        elementId: Uuid,
        scaleDelta: Float,
        rotationDelta: Float,
    ) {
        updateDocument { doc ->
            doc.copy(
                elements =
                    doc.elements.map { el ->
                        if (el.id == elementId) {
                            el.withTransform(
                                el.transform.copy(
                                    scaleX = (el.transform.scaleX * scaleDelta).coerceIn(0.1f, 10f),
                                    scaleY = (el.transform.scaleY * scaleDelta).coerceIn(0.1f, 10f),
                                    rotation = el.transform.rotation + rotationDelta,
                                ),
                            )
                        } else {
                            el
                        }
                    },
            )
        }
    }

    /**
     * Removes the selected element from the canvas.
     */
    fun deleteSelectedElement() {
        val selectedId = _state.value.selectedElementId ?: return
        pushUndo()
        updateDocument { doc ->
            doc.copy(elements = doc.elements.filter { it.id != selectedId })
        }
        selectElement(null)
    }

    // --- Ink creation ---

    /**
     * Adds a completed ink stroke as a new element.
     */
    fun addInkStroke(
        points: List<app.logdate.feature.postcards.model.InkPoint>,
        tool: app.logdate.feature.postcards.model.InkTool,
        color: String,
        strokeWidth: Float,
    ) {
        pushUndo()
        val element =
            CanvasElement.Ink(
                id = Uuid.random(),
                tool = tool,
                color = color,
                strokeWidth = strokeWidth,
                points = points,
                zIndex = nextZIndex(),
            )
        updateDocument { doc -> doc.copy(elements = doc.elements + element) }
    }

    // --- Shape creation ---

    /**
     * Adds a shape element from a completed drag gesture.
     */
    fun addShape(
        shapeKind: app.logdate.feature.postcards.model.ShapeKind,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        color: String,
        strokeWidth: Float,
    ) {
        pushUndo()
        val element =
            CanvasElement.Shape(
                id = Uuid.random(),
                shapeKind = shapeKind,
                color = color,
                strokeWidth = strokeWidth,
                width = width,
                height = height,
                transform = ElementTransform(x = x, y = y),
                zIndex = nextZIndex(),
            )
        updateDocument { doc -> doc.copy(elements = doc.elements + element) }
    }

    // --- Text creation and editing ---

    /**
     * Opens the text editor. If [elementId] is non-null, edits that existing
     * text element; otherwise creates a new one on confirm.
     */
    fun startTextEditing(elementId: Uuid? = null) {
        _state.update {
            it.copy(
                editingTextElementId = elementId,
                isTextEditorVisible = true,
            )
        }
    }

    /**
     * Closes the text editor without applying changes.
     */
    fun cancelTextEditing() {
        _state.update {
            it.copy(
                editingTextElementId = null,
                isTextEditorVisible = false,
            )
        }
    }

    /**
     * Confirms text from the editor. Creates a new element or updates an existing one.
     */
    fun confirmTextEditing(
        content: String,
        fontFamily: String,
        color: String,
        fontSize: Float,
        x: Float = 0f,
        y: Float = 0f,
    ) {
        val editingId = _state.value.editingTextElementId
        if (editingId != null) {
            updateTextElement(editingId, content, fontFamily, color, fontSize)
        } else {
            addTextElement(content, fontFamily, color, fontSize, x = x, y = y)
        }
        _state.update {
            it.copy(
                editingTextElementId = null,
                isTextEditorVisible = false,
            )
        }
    }

    /**
     * Adds a text element at the given position.
     */
    private fun addTextElement(
        content: String,
        fontFamily: String,
        color: String,
        fontSize: Float,
        x: Float,
        y: Float,
    ) {
        pushUndo()
        val element =
            CanvasElement.Text(
                id = Uuid.random(),
                content = content,
                fontFamily = fontFamily,
                color = color,
                fontSize = fontSize,
                transform = ElementTransform(x = x, y = y),
                zIndex = nextZIndex(),
            )
        updateDocument { doc -> doc.copy(elements = doc.elements + element) }
    }

    /**
     * Updates an existing text element's content and styling.
     */
    private fun updateTextElement(
        elementId: Uuid,
        content: String,
        fontFamily: String,
        color: String,
        fontSize: Float,
    ) {
        pushUndo()
        updateDocument { doc ->
            doc.copy(
                elements =
                    doc.elements.map { el ->
                        if (el.id == elementId && el is CanvasElement.Text) {
                            el.copy(
                                content = content,
                                fontFamily = fontFamily,
                                color = color,
                                fontSize = fontSize,
                            )
                        } else {
                            el
                        }
                    },
            )
        }
    }

    // --- Sticker creation ---

    /**
     * Adds a sticker element from the shelf onto the canvas.
     */
    fun addStickerElement(
        stickerRef: Uuid,
        x: Float,
        y: Float,
    ) {
        pushUndo()
        val element =
            CanvasElement.Sticker(
                id = Uuid.random(),
                stickerRef = stickerRef,
                transform = ElementTransform(x = x, y = y),
                zIndex = nextZIndex(),
            )
        updateDocument { doc -> doc.copy(elements = doc.elements + element) }
    }

    // --- Tool selection ---

    fun setActiveTool(tool: CanvasTool) {
        _state.update { it.copy(activeTool = tool) }
    }

    // --- Shelf ---

    fun setShelfMode(mode: ShelfMode) {
        _state.update { it.copy(shelfMode = mode) }
    }

    // --- Undo / Redo ---

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.document)
        _state.update { it.copy(document = previous) }
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.document)
        _state.update { it.copy(document = next) }
    }

    private fun pushUndo() {
        undoStack.addLast(_state.value.document)
        redoStack.clear()
        // Limit undo history
        if (undoStack.size > MAX_UNDO_HISTORY) {
            undoStack.removeFirst()
        }
    }

    // --- Persistence ---

    /**
     * Saves the current Postcard to the database.
     */
    fun save() {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            try {
                val doc =
                    _state.value.document.copy(
                        modifiedAt = Clock.System.now(),
                    )
                val postcard =
                    Postcard(
                        id = doc.id,
                        title = doc.title.ifEmpty { getString(Res.string.postcards_untitled) },
                        createdAt = doc.createdAt,
                        modifiedAt = doc.modifiedAt,
                        sourceMomentRef = doc.sourceMomentRef,
                        documentJson = json.encodeToString(PostcardDocument.serializer(), doc),
                    )
                if (_state.value.isNewPostcard) {
                    postcardRepository.createPostcard(postcard)
                } else {
                    postcardRepository.updatePostcard(postcard)
                }
                _state.update { it.copy(document = doc, isSaving = false, isNewPostcard = false) }
            } catch (e: Exception) {
                Napier.e("Failed to save postcard", e)
                _state.update { it.copy(isSaving = false, saveError = Res.string.postcards_save_failed) }
            }
        }
    }

    fun clearSaveError() {
        _state.update { it.copy(saveError = null) }
    }

    fun setTitle(title: String) {
        updateDocument { doc -> doc.copy(title = title) }
    }

    // --- Helpers ---

    private fun updateDocument(transform: (PostcardDocument) -> PostcardDocument) {
        _state.update { it.copy(document = transform(it.document)) }
    }

    private fun nextZIndex(): Int {
        val maxZ =
            _state.value.document.elements
                .maxOfOrNull { it.zIndex } ?: -1
        return maxZ + 1
    }

    companion object {
        private const val MAX_UNDO_HISTORY = 50
        private const val DEFAULT_CANVAS_BACKGROUND = "#FFFFFF"
    }
}

/**
 * Returns a copy of this element with a new transform.
 */
private fun CanvasElement.withTransform(newTransform: ElementTransform): CanvasElement =
    when (this) {
        is CanvasElement.Photo -> copy(transform = newTransform)
        is CanvasElement.Text -> copy(transform = newTransform)
        is CanvasElement.Ink -> copy(transform = newTransform)
        is CanvasElement.Shape -> copy(transform = newTransform)
        is CanvasElement.Sticker -> copy(transform = newTransform)
    }
