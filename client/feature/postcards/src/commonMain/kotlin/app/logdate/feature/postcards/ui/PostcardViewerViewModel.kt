package app.logdate.feature.postcards.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.postcards.Postcard
import app.logdate.client.repository.postcards.PostcardRepository
import app.logdate.client.repository.stickers.Sticker
import app.logdate.client.repository.stickers.StickerRepository
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.PostcardDocument
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlin.uuid.Uuid

/**
 * UI state for the Postcard viewer.
 */
sealed interface PostcardViewerUiState {
    data object Loading : PostcardViewerUiState

    data class Loaded(
        val document: PostcardDocument,
        val stickerUriMap: Map<Uuid, String> = emptyMap(),
    ) : PostcardViewerUiState

    data class Error(
        val message: String,
    ) : PostcardViewerUiState
}

/**
 * ViewModel for the Postcard viewer screen.
 *
 * Observes a Postcard by ID together with the sticker library, so the viewer only shows a
 * Postcard once the stickers it places are resolved and follows later changes to either.
 */
class PostcardViewerViewModel(
    savedStateHandle: SavedStateHandle,
    postcardRepository: PostcardRepository,
    stickerRepository: StickerRepository,
) : ViewModel() {
    private val postcardId: Uuid =
        savedStateHandle
            .get<String>("postcardId")
            ?.let { Uuid.parse(it) }
            ?: error("postcardId is required")

    val uiState: StateFlow<PostcardViewerUiState> =
        combine(
            postcardRepository.observePostcard(postcardId),
            stickerRepository.observeAllStickers(),
        ) { postcard, stickers -> viewerState(postcard, stickers) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = PostcardViewerUiState.Loading,
            )

    private fun viewerState(
        postcard: Postcard?,
        stickers: List<Sticker>,
    ): PostcardViewerUiState {
        if (postcard == null) return PostcardViewerUiState.Error("Postcard not found")
        val document =
            try {
                PostcardDocument.json.decodeFromString(PostcardDocument.serializer(), postcard.documentJson)
            } catch (e: Exception) {
                Napier.e("Failed to parse Postcard $postcardId", e)
                return PostcardViewerUiState.Error("Failed to parse Postcard: ${e.message}")
            }
        val stickerRefs =
            document.elements
                .filterIsInstance<CanvasElement.Sticker>()
                .mapTo(mutableSetOf()) { it.stickerRef }
        val stickerUriMap =
            stickers
                .filter { it.id in stickerRefs }
                .associate { it.id to it.imageUri }
        return PostcardViewerUiState.Loaded(document, stickerUriMap)
    }
}
