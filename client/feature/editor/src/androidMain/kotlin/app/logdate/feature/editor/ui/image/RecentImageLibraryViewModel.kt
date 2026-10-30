package app.logdate.feature.editor.ui.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.media.MediaManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Follows the recent photos the image picker offers. Collection runs in the ViewModel, so a picker
 * that recomposes or resumes restarts it without ever showing an error for the interrupted read.
 */
internal class RecentImageLibraryViewModel(
    private val mediaManager: MediaManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ImagePickerLibraryState>(ImagePickerLibraryState.Loading)
    val state: StateFlow<ImagePickerLibraryState> = mutableState.asStateFlow()
    private var loading: Job? = null

    fun requirePermission(required: ImagePickerLibraryState.PermissionRequired) {
        loading?.cancel()
        mutableState.value = required
    }

    /**
     * Shows recent photos, as a partial selection unless [hasFullAccess], and [permissionRevoked]
     * if the system withdraws access while they load.
     */
    fun load(
        hasFullAccess: Boolean,
        permissionRevoked: ImagePickerLibraryState.PermissionRequired,
    ) {
        loading?.cancel()
        mutableState.value = ImagePickerLibraryState.Loading
        loading =
            viewModelScope.launch {
                try {
                    // Ask for ~4x the preview cap so enough images remain once videos are filtered out.
                    mediaManager.getRecentMedia(limit = RECENT_IMAGE_PREVIEW_LIMIT * 4).collect { media ->
                        val images = recentImagePreviews(media)
                        mutableState.value =
                            when {
                                images.isEmpty() -> ImagePickerLibraryState.Empty
                                !hasFullAccess -> ImagePickerLibraryState.PartialAccess(images)
                                else -> ImagePickerLibraryState.Loaded(images)
                            }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: SecurityException) {
                    Napier.w("Recent image access requires permission", error)
                    mutableState.value = permissionRevoked
                } catch (error: Exception) {
                    Napier.e("Failed to load recent Android images", error)
                    mutableState.value = ImagePickerLibraryState.Error
                }
            }
    }
}
