package app.logdate.feature.stickers.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.stickers.StickerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * UI state for the sticker extraction flow.
 */
sealed interface StickerExtractionState {
    data object Idle : StickerExtractionState

    data object Processing : StickerExtractionState

    data class Preview(
        val pngBytes: ByteArray,
        val sourceUri: String,
    ) : StickerExtractionState

    data class Saved(
        val stickerId: Uuid,
    ) : StickerExtractionState

    data class Failed(
        val message: String,
    ) : StickerExtractionState
}

/**
 * Orchestrates photo → subject extraction → preview → save to library.
 */
class StickerExtractionViewModel(
    private val extractor: StickerSubjectExtractor,
    private val stickerRepository: StickerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<StickerExtractionState>(StickerExtractionState.Idle)
    val state: StateFlow<StickerExtractionState> = _state.asStateFlow()

    /**
     * Runs extraction on the given photo.
     */
    fun extractFromPhoto(photoUri: String) {
        _state.value = StickerExtractionState.Processing
        viewModelScope.launch {
            val pngBytes = extractor.extractSubject(photoUri)
            if (pngBytes != null) {
                _state.value = StickerExtractionState.Preview(pngBytes, photoUri)
            } else {
                _state.value = StickerExtractionState.Failed("No subject found in photo")
            }
        }
    }

    /**
     * Confirms the extraction and saves the sticker to the library.
     */
    fun confirmAndSave(
        savedImageUri: String,
        sourcePhotoUri: String,
        sourceMomentRef: Uuid? = null,
    ) {
        viewModelScope.launch {
            val stickerId =
                stickerRepository.saveSticker(
                    sourcePhotoUri = sourcePhotoUri,
                    sourceMomentRef = sourceMomentRef,
                    imageUri = savedImageUri,
                )
            _state.value = StickerExtractionState.Saved(stickerId)
        }
    }

    fun reset() {
        _state.value = StickerExtractionState.Idle
    }
}
