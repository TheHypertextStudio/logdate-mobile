package app.logdate.feature.library.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.places.PlaceResolutionResult
import app.logdate.client.domain.places.ResolveLocationToPlaceUseCase
import app.logdate.client.media.display.RemoteDisplayManager
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.feature.library.ui.LibraryMediaSource
import app.logdate.feature.library.ui.buildLibraryMediaSources
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logdate.client.feature.library.generated.resources.Res
import logdate.client.feature.library.generated.resources.library_media_detail_error_load_failed
import logdate.client.feature.library.generated.resources.library_media_detail_error_not_found
import kotlin.uuid.Uuid

/**
 * ViewModel for the media detail screen.
 *
 * Loads a single indexed image or video by its ID and exposes its content, indexed metadata,
 * optional journal-derived enrichment, and resolved location names.
 */
class MediaDetailViewModel(
    private val mediaId: Uuid,
    private val notesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    private val indexedMediaRepository: IndexedMediaRepository,
    private val resolveLocationToPlaceUseCase: ResolveLocationToPlaceUseCase,
    private val remoteDisplayManager: RemoteDisplayManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MediaDetailUiState>(MediaDetailUiState.Loading)
    val uiState: StateFlow<MediaDetailUiState> = _uiState.asStateFlow()
    private val currentMediaId = MutableStateFlow(mediaId)
    private val _viewerState = MutableStateFlow(MediaViewerState())
    val viewerState: StateFlow<MediaViewerState> = _viewerState.asStateFlow()

    /** All library media for presenter navigation. */
    private val allMediaItems = MutableStateFlow<List<LibraryMediaSource>>(emptyList())

    /** Presenter mode state. */
    val presenterState: StateFlow<PresenterState> =
        combine(
            remoteDisplayManager.observeExternalDisplays(),
            remoteDisplayManager.observeIsPresenting(),
            viewerState,
        ) { displays, isPresenting, viewer ->
            val items =
                viewer.mediaItems.map { media ->
                    PresenterMediaItem(
                        uid = media.uid,
                        uri = media.uri,
                        isVideo = media.isVideo,
                    )
                }
            PresenterState(
                isExternalDisplayAvailable = displays.isNotEmpty(),
                isPresenting = isPresenting,
                currentIndex = viewer.currentIndex,
                totalItems = items.size,
                mediaItems = items,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PresenterState(),
        )

    init {
        observeMedia()
    }

    /** Start presenting the current media on the first available external display. */
    fun startPresenting() {
        viewModelScope.launch {
            try {
                val displays = remoteDisplayManager.observeExternalDisplays().first()
                val displayId = displays.firstOrNull()?.id ?: return@launch
                val item = viewerState.value.mediaItems.getOrNull(viewerState.value.currentIndex) ?: return@launch
                if (item.isAudio) return@launch
                val mimeType = if (item.isVideo) "video/*" else "image/*"
                remoteDisplayManager.present(displayId, item.uri, mimeType)
            } catch (e: Exception) {
                Napier.e("Failed to start presenting", e)
            }
        }
    }

    /** Navigate to a specific item in presenter mode and update the external display. */
    fun presentItem(index: Int) {
        selectMedia(index, updatePresentation = true)
    }

    /** Stop presenting and dismiss the external display. */
    fun stopPresenting() {
        remoteDisplayManager.dismiss()
    }

    fun selectMedia(index: Int) {
        selectMedia(index, updatePresentation = presenterState.value.isPresenting)
    }

    override fun onCleared() {
        super.onCleared()
        remoteDisplayManager.dismiss()
    }

    private fun observeMedia() {
        viewModelScope.launch {
            combine(
                indexedMediaRepository.observeAllMedia(),
                notesRepository.allNotesObserved,
                currentMediaId,
            ) { mediaItems, notes, selectedMediaId ->
                Triple(mediaItems, notes, selectedMediaId)
            }.catch { error ->
                Napier.e("Failed to load media detail", error)
                _uiState.value = MediaDetailUiState.Error(Res.string.library_media_detail_error_load_failed)
            }.collect { (mediaItems, notes, selectedMediaId) ->
                val libraryMedia = buildLibraryMediaSources(mediaItems, notes)
                allMediaItems.value = libraryMedia

                val media =
                    libraryMedia.find { it.id == selectedMediaId || it.matchingNotes.any { note -> note.uid == selectedMediaId } }
                if (media == null) {
                    _viewerState.value = MediaViewerState()
                    _uiState.value = MediaDetailUiState.Error(Res.string.library_media_detail_error_not_found)
                } else {
                    updateViewerState(libraryMedia, media.id)
                    if (media.id != selectedMediaId) {
                        currentMediaId.value = media.id
                    }
                    updateForMedia(media)
                }
            }
        }
    }

    private suspend fun updateForMedia(media: LibraryMediaSource) =
        coroutineScope {
            val primaryNote = media.matchingNotes.firstOrNull()

            val journalsDeferred = async { loadJournals(media.matchingNotes) }
            val exifDeferred =
                async {
                    if (media.indexedMedia is IndexedMedia.Image) {
                        loadExif(media.indexedMedia.uid)
                    } else {
                        null
                    }
                }
            val locationDeferred = async { resolveLocationName(primaryNote?.location) }

            val journals = journalsDeferred.await()
            val exif = exifDeferred.await()
            val locationDisplayName = locationDeferred.await()

            _uiState.value =
                when {
                    media.isVideo -> {
                        MediaDetailUiState.VideoContent(
                            mediaId = media.id,
                            mediaRef = media.uri,
                            createdAt = media.timestamp,
                            location = primaryNote?.location,
                            locationDisplayName = locationDisplayName,
                            journals = journals,
                        )
                    }

                    media.isAudio -> {
                        MediaDetailUiState.AudioContent(
                            mediaId = media.id,
                            mediaRef = media.uri,
                            durationMs = media.durationMs,
                            createdAt = media.timestamp,
                            location = primaryNote?.location,
                            locationDisplayName = locationDisplayName,
                            journals = journals,
                        )
                    }

                    media.indexedMedia is IndexedMedia.Image -> {
                        imageContentState(media, primaryNote, locationDisplayName, journals, exif)
                    }

                    else -> {
                        imageContentState(media, primaryNote, locationDisplayName, journals, exif)
                    }
                }
        }

    private fun imageContentState(
        media: LibraryMediaSource,
        primaryNote: JournalNote?,
        locationDisplayName: String?,
        journals: List<JournalReference>,
        exif: ExifDisplayData?,
    ): MediaDetailUiState.ImageContent =
        MediaDetailUiState.ImageContent(
            mediaId = media.id,
            mediaRef = media.uri,
            createdAt = media.timestamp,
            location = primaryNote?.location,
            locationDisplayName = locationDisplayName,
            journals = journals,
            exif = exif,
        )

    private suspend fun loadJournals(notes: List<JournalNote>): List<JournalReference> =
        notes
            .flatMap { note ->
                try {
                    journalContentRepository.observeJournalsForContent(note.uid).first()
                } catch (e: Exception) {
                    Napier.e("Failed to load cross-references", e)
                    emptyList()
                }
            }.distinctBy { it.id }
            .map { JournalReference(id = it.id, title = it.title) }

    private suspend fun loadExif(mediaId: Uuid): ExifDisplayData? =
        try {
            indexedMediaRepository.getExifMetadata(mediaId)?.let { metadata ->
                ExifDisplayData(
                    cameraMake = metadata.cameraMake,
                    cameraModel = metadata.cameraModel,
                    aperture = metadata.aperture,
                    iso = metadata.iso,
                    focalLength = metadata.focalLength,
                    shutterSpeed = metadata.shutterSpeed,
                )
            }
        } catch (e: Exception) {
            Napier.e("Failed to load EXIF data", e)
            null
        }

    /**
     * Resolves the best available display name for a note's location.
     *
     * Fallback chain: resolved place name → note's display name → raw coordinates → null.
     */
    private suspend fun resolveLocationName(noteLocation: NoteLocation?): String? {
        if (noteLocation == null) return null

        val coords = noteLocation.coordinates
        if (coords != null) {
            try {
                val location =
                    Location(
                        coords.latitude,
                        coords.longitude,
                        altitude = LocationAltitude(coords.altitude ?: 0.0, AltitudeUnit.METERS),
                    )
                val resolved =
                    when (val result = resolveLocationToPlaceUseCase(location)) {
                        is PlaceResolutionResult.UserDefinedPlace -> {
                            result.place.name
                        }

                        is PlaceResolutionResult.ExternalSuggestion -> {
                            result.suggestion.name
                        }

                        is PlaceResolutionResult.CoarseLocation -> {
                            val addr = result.address
                            listOfNotNull(addr.locality, addr.adminArea)
                                .joinToString(", ")
                                .ifEmpty { null }
                        }

                        is PlaceResolutionResult.UnknownLocation -> {
                            null
                        }
                    }
                if (resolved != null) return resolved
            } catch (e: Exception) {
                Napier.w("Failed to resolve location name", e)
            }
        }

        return noteLocation.displayName
            ?: coords?.let { "${it.latitude}, ${it.longitude}" }
    }

    private fun updateViewerState(
        libraryMedia: List<LibraryMediaSource>,
        selectedMediaId: Uuid,
    ) {
        val items =
            libraryMedia.map { media ->
                MediaViewerItem(
                    uid = media.id,
                    uri = media.uri,
                    isVideo = media.isVideo,
                    isAudio = media.isAudio,
                )
            }
        val currentIndex =
            items
                .indexOfFirst { it.uid == selectedMediaId }
                .takeIf { it >= 0 }
                ?: 0
        _viewerState.value =
            MediaViewerState(
                currentIndex = currentIndex,
                totalItems = items.size,
                mediaItems = items,
            )
    }

    private fun selectMedia(
        index: Int,
        updatePresentation: Boolean,
    ) {
        val item = viewerState.value.mediaItems.getOrNull(index) ?: return
        if (item.uid != currentMediaId.value) {
            currentMediaId.value = item.uid
        }

        if (updatePresentation) {
            if (item.isAudio) return
            val mimeType = if (item.isVideo) "video/*" else "image/*"
            remoteDisplayManager.updatePresentation(item.uri, mimeType)
        }
    }
}
