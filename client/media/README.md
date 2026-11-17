# `:client:media`

**Media handling and processing functionality**

## Overview

Provides comprehensive media handling capabilities including image, video, and audio capture, processing, and management across all platforms. This module serves as the foundation for all media-related functionality in the application.

## Managed storage

Media that an entry shows is a file inside the app's own storage, never a reference into another app. This module owns
the pieces that make that safe:

- **`LocalMediaRef`, `MediaFileResolver` and `MediaDirectories`** (package `app.logdate.client.media.storage`) turn a stored
  `logdate-media://<collection>/<path>` string into a file on each platform. Read
  [Media References](../../docs/reference/media-references.md) before storing or opening a media reference.
- **`MediaManager.saveMedia` and `saveMediaFromFile`** copy bytes into managed storage and return the reference to
  store. On Android they write through `AndroidCanonicalMediaStore`, a content-addressed store with a fixed write
  protocol. Read [Android Media Storage](../../docs/reference/android-media-storage.md) before changing how files are
  written or deleted.
- **`ManagedMediaImporter`** stages media that another app owns, such as a picked photo, into a private file and saves it
  through `MediaManager`, so a saved entry never depends on the picker's short-lived grant.

Deleting stored media is deliberately not implemented on Android: `deleteOwnedMedia` returns `false`, because no check
made after one deletion can prove that another writer has not just claimed the same file.

## Guarded recovery writes

Native recovery writes through `ConditionalMediaSaver` so an account or server change can reject
an attachment before it becomes visible. Android exposes `AndroidPhotoLibraryMediaManager`, whose
delegate implements that capability. Kotlin's `MediaManager by delegate` forwards only members of
`MediaManager`; a decorator must explicitly implement and forward `ConditionalMediaSaver` too.
Otherwise recovery can download a valid original and still fail before saving it.

Forward the original `canCommit` predicate into the delegate's conditional save. Checking it once
and then calling an unconditional save leaves a race with sign-out or account changes. Tests should
exercise the manager exposed by dependency injection as well as the underlying store, including a
scope change immediately before the delegate commits. `AndroidPhotoLibraryConditionalSaveTest` and
`NativeMediaRecoveryProductionWrapperTest` cover both the capability and the recovery operation.

File downloads use the same capability through its `saveMediaFromFile` overload. Forward the guard
through decorators into the store's finalization boundary. A returned URI may name a deduplicated
original used by another entry, so cancellation must never delete it from the sync layer.
`FileMediaHydrationScopeTest` checks both shared-original preservation and rejection before a new
object becomes durable.

## Architecture

```
Media Module
├── Core Media Management
├── Audio Recording & Processing
├── Image Handling
└── Platform-Specific Implementations
```

## Key Components

### Core Components

- `MediaManager.kt` - Central media access interface
- `MediaObject.kt` - Media representation models
- `MediaModule.kt` - Dependency injection

### Audio Features

- `AudioRecordingManager.kt` - Audio recording interface
- `AudioPlaybackManager.kt` - Audio playback interface
- `TranscriptionService.kt` - Speech-to-text functionality

### Platform-Specific

- Android implementation with MediaStore
- iOS implementation with AVFoundation
- Desktop implementation with platform APIs

## Features

### Media Management

- **Media Access**: Unified access to device media
- **Media Retrieval**: Query media by type and date
- **Media Collections**: Organize media into collections
- **Recent Media**: Access recently added media
- **Media Metadata**: Extract and process media information

### Audio Recording

- **Voice Notes**: High-quality audio recording
- **Recording States**: Managed recording lifecycle
- **Audio Levels**: Real-time audio level monitoring
- **Duration Tracking**: Recording time tracking
- **Transcription**: Speech-to-text conversion

### Image Handling

- **Image Access**: Retrieve device images
- **Image Storage**: Store application images
- **Image Metadata**: Extract image information
- **Image Collections**: Organize images

### Video Support

- **Video Access**: Retrieve device videos
- **Video Metadata**: Extract video information
- **Duration Handling**: Video duration information
- **Video Collections**: Organize videos

## Dependencies

### Core Dependencies

- **Kotlinx Coroutines**: Asynchronous operations
- **Kotlinx DateTime**: Date and time handling
- **Kotlinx Serialization**: Serialization support
- **Compose Runtime**: Reactive programming
- **Material 3**: UI components
- **Koin**: Dependency injection
- **Napier**: Logging

## Usage Patterns

### Media Access

```kotlin
class MediaRepository(private val mediaManager: MediaManager) {
    suspend fun getRecentMedia(): Flow<List<MediaObject>> {
        return mediaManager.getRecentMedia()
    }

    suspend fun getMediaForDate(date: LocalDate): Flow<List<MediaObject>> {
        val start = date.atStartOfDay().toInstant(TimeZone.currentSystemDefault())
        val end = date.plus(1, DateTimeUnit.DAY)
            .atStartOfDay().toInstant(TimeZone.currentSystemDefault())
        return mediaManager.queryMediaByDate(start, end)
    }
}
```

### Audio Recording

```kotlin
data class RecordingUiState(val audioLevel: Float = 0f, val duration: Duration = Duration.ZERO)

class AudioNoteViewModel(private val audioRecordingManager: AudioRecordingManager) {
    private val _recordingState = MutableStateFlow(RecordingUiState())
    val recordingState: StateFlow<RecordingUiState> = _recordingState

    fun startRecording() {
        viewModelScope.launch {
            val success = audioRecordingManager.startRecording()
            if (success) {
                collectAudioLevels()
                collectDuration()
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            val fileUri = audioRecordingManager.stopRecording()
            // Process the recorded file
        }
    }

    private fun collectAudioLevels() {
        viewModelScope.launch {
            audioRecordingManager.getAudioLevelFlow().collect { level ->
                _recordingState.update { it.copy(audioLevel = level) }
            }
        }
    }

    private fun collectDuration() {
        viewModelScope.launch {
            audioRecordingManager.getRecordingDurationFlow().collect { duration ->
                _recordingState.update { it.copy(duration = duration) }
            }
        }
    }
}
```

## Dependency Injection

```kotlin
val mediaModule = module {
    single<MediaManager> { PlatformMediaManager(get()) }
}

val audioModule = module {
    single<AudioRecordingManager> { PlatformAudioRecordingManager(get()) }
    single<AudioPlaybackManager> { PlatformAudioPlaybackManager() }
    factory<TranscriptionService> { PlatformTranscriptionService() }
}
```
