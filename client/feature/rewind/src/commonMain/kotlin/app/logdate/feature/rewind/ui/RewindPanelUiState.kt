package app.logdate.feature.rewind.ui

import app.logdate.client.text.UiText
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Base interface for all rewind panel UI states.
 *
 * This sealed interface represents the various types of panels that can be displayed
 * in a rewind story, each with specific content and display requirements.
 */
sealed interface RewindPanelUiState {
    // Common properties could be added here in the future
}

/**
 * UI state for a panel with basic text content.
 *
 * Represents a simple panel with centered text and customizable background.
 * Ideal for introductory or concluding panels in a rewind story.
 *
 * @property text The main text content to display
 * @property background Background styling information
 */
data class BasicTextRewindPanelUiState(
    val text: String,
    val background: RewindPanelBackgroundSpec = RewindPanelBackgroundSpec(),
) : RewindPanelUiState

/**
 * UI state for a panel with title and subtitle.
 *
 * Represents a panel with hierarchical text content - a prominent title and
 * supporting subtitle text. Optional background image for visual interest.
 *
 * @property title The primary headline text
 * @property subtitle The supporting descriptive text
 * @property backgroundUri Optional URI for a background image
 */
data class SubtitledRewindPanelUiState(
    val title: String,
    val subtitle: UiText,
    val backgroundUri: String? = null,
    val weatherChip: WeatherChipUiState? = null,
) : RewindPanelUiState

/**
 * Display data for the small weather chip overlaid on a rewind's title panel.
 *
 * Built by [RewindDetailViewModel] from the rewind's [app.logdate.shared.model.WeatherContext].
 * The composable side resolves the actual icon and label from these primitives so the
 * ViewModel doesn't need to reach into compose-resources.
 *
 * @property category Dominant weather condition for the rewind period — picks the icon.
 * @property avgTempCelsius Average daily mean temperature in Celsius — the composable
 *   converts to Fahrenheit when the locale calls for it before rendering.
 */
data class WeatherChipUiState(
    val category: WeatherChipCategory,
    val avgTempCelsius: Double,
)

/**
 * Coarse weather buckets the title chip knows how to render an icon for.
 *
 * Mirrors the domain `WeatherCategory` but lives in the feature module so the panel
 * state stays decoupled from the shared model when the renderer eventually grows more
 * categories than the data model needs.
 */
enum class WeatherChipCategory {
    SUNNY,
    CLOUDY,
    RAINY,
    SNOWY,
    MIXED,
}

/**
 * UI state for a panel displaying a significant statistic.
 *
 * Represents a panel highlighting an important numerical value with context.
 * Designed for metrics and achievements in the rewind story.
 *
 * @property title Contextual title for the statistic
 * @property statistic The numerical value to display prominently
 * @property units Units or label for the statistic (e.g., "steps", "miles")
 * @property description Additional context or interpretation of the statistic
 * @property background Background styling information
 */
data class BigStatisticRewindPanelUiState(
    val title: String,
    val statistic: String,
    val units: UiText,
    val description: UiText,
    val background: RewindPanelBackgroundSpec = RewindPanelBackgroundSpec(),
) : RewindPanelUiState

/**
 * UI state for a panel displaying a text note.
 *
 * Represents a panel showing journal text content with date information.
 *
 * @property sourceId Unique identifier of the original note
 * @property timestamp When the note was created
 * @property content The text content from the journal note
 * @property dateFormatted Formatted date string showing when the note was created
 * @property background Background styling information
 */
data class TextNoteRewindPanelUiState(
    val sourceId: Uuid,
    val timestamp: Instant,
    val content: String,
    val dateFormatted: UiText,
    val background: RewindPanelBackgroundSpec =
        RewindPanelBackgroundSpec(
            color = 0xFF1A1A1A, // Dark gray background by default
        ),
) : RewindPanelUiState

/**
 * UI state for a panel displaying an image note.
 *
 * Represents a panel showing a photo with optional caption and date information.
 *
 * @property sourceId Unique identifier of the original image
 * @property timestamp When the image was created
 * @property imageUri URI to the image resource
 * @property caption Optional caption text for the image
 * @property dateFormatted Formatted date string showing when the image was captured
 */
data class ImageRewindPanelUiState(
    val sourceId: Uuid,
    val timestamp: Instant,
    val imageUri: String,
    val caption: String? = null,
    val dateFormatted: UiText,
    val isVideoFrame: Boolean = false,
) : RewindPanelUiState

/**
 * UI state for a panel displaying an audio journal entry.
 *
 * Transcript text is null when the recording hadn't been transcribed yet by the time
 * this rewind was generated — the panel must render fine either way. [cachedAmplitudes]
 * is null when no waveform data was ever cached for this recording (older entries
 * predate [app.logdate.ui.audio.WaveformStorage] or transcription is still pending);
 * the renderer reads the waveform from the recording in that case.
 *
 * @property sourceId Unique identifier of the original audio note
 * @property timestamp When the recording was made
 * @property uri Playable URI of the audio file
 * @property durationMs Recording length in milliseconds
 * @property transcriptionText The transcript, or null if not yet available
 * @property dateFormatted Formatted date string showing when the recording was made
 * @property cachedAmplitudes Cached waveform amplitudes (0.0–1.0), or null if uncached
 */
data class AudioNoteRewindPanelUiState(
    val sourceId: Uuid,
    val timestamp: Instant,
    val uri: String,
    val durationMs: Long,
    val transcriptionText: String?,
    val dateFormatted: UiText,
    val cachedAmplitudes: List<Float>? = null,
) : RewindPanelUiState

/**
 * Playback state for the audio panel currently being listened to in a rewind story.
 *
 * At most one audio panel plays at a time. [playingSourceId] is null when nothing is
 * playing; [progress] is only meaningful while it's set.
 */
data class AudioPanelPlaybackUiState(
    val playingSourceId: Uuid? = null,
    val progress: Float = 0f,
)

/**
 * UI state for a narrative context panel.
 *
 * Sets the scene and provides context for the rewind story. Used to establish
 * the overall narrative or introduce thematic segments.
 *
 * Example: "You finally made it to the California coast"
 *
 * @property sourceId Unique identifier of the original content
 * @property timestamp When this narrative context was created
 * @property contextText The narrative text that sets the scene
 * @property backgroundImageUri Optional background image URI
 */
data class NarrativeContextRewindPanelUiState(
    val sourceId: Uuid,
    val timestamp: Instant,
    val contextText: String,
    val backgroundImageUri: String? = null,
) : RewindPanelUiState

/**
 * UI state for a transition panel.
 *
 * Connects story beats and explains thematic shifts in the narrative.
 * These panels create flow by bridging different moments.
 *
 * Example: "But evenings shifted to culinary adventures"
 *
 * @property sourceId Unique identifier of the original content
 * @property timestamp When this transition was created
 * @property transitionText The transition text connecting story beats
 */
data class TransitionRewindPanelUiState(
    val sourceId: Uuid,
    val timestamp: Instant,
    val transitionText: String,
) : RewindPanelUiState

/**
 * UI state for a verbatim line the AI pulled from one of the user's actual journal
 * entries this period. Renders as a quiet "you wrote this and it landed" beat.
 *
 * @property text The exact line as the user typed it. Never edited or paraphrased.
 * @property whyItHits A one-line note from the AI about why this line was picked out
 *   of the surrounding entry. Drawn small underneath the quote.
 * @property accentSeed Used to vary the per-card hue so consecutive quote panels look
 *   distinct.
 */
data class HighlightedQuoteRewindPanelUiState(
    val text: String,
    val whyItHits: String,
    val accentSeed: Int = 0,
) : RewindPanelUiState

/**
 * UI state for an AI-invented noticing prompt drawn from the rewind's actual content.
 *
 * Each prompt has two parts: an [observation] line that grounds the prompt in something
 * specific from the user's week ("Sarah came up in five entries this week, the last on
 * Friday at the bookshop") and an open-ended [invitation] that the observation leads
 * into ("What stayed with you?"). The renderer draws the observation quietly above the
 * invitation so the connection-to-real-data is visible at a glance.
 *
 * @property observation The factual line drawn from the user's actual content.
 * @property invitation The open-ended question the observation leads into.
 * @property accentSeed Used to vary background hue between prompts so consecutive prompt
 *   panels look distinct.
 * @property existingResponse What the user previously typed in reply, or null if no reply
 *   exists yet. Rendered as a quiet preview beneath the invitation when present.
 * @property repliesAllowed Whether reply chrome should be visible at all. Driven by a
 *   user preference; when false, the panel still renders the prompt itself but the
 *   existing-response preview and reply CTA are suppressed.
 */
data class ReflectionPromptRewindPanelUiState(
    val observation: String,
    val invitation: String,
    val accentSeed: Int = 0,
    val existingResponse: String? = null,
    val repliesAllowed: Boolean = true,
) : RewindPanelUiState

/**
 * UI state for a panel that visualizes where the user actually was during the rewind.
 *
 * Built from the rewind's downsampled location path and displayed over the
 * platform's geographic basemap. Automatic framing keeps the main area legible;
 * the backdrop leaves touch gestures to story navigation.
 *
 * @property points Downsampled locations in chronological order.
 * @property title Localized headline above the map.
 * @property subtitle Localized supporting line, typically a place or stop count.
 * @property accentSeed Seed for the surrounding story background.
 */
data class LocationMapRewindPanelUiState(
    val points: List<MapPanelPoint>,
    val title: UiText,
    val subtitle: UiText,
    val accentSeed: Int = 0,
) : RewindPanelUiState

/**
 * Plain lat/lon point handed to the [LocationMapPanel] composable.
 *
 * Decoupled from the shared model `MapPoint` so the panel state doesn't drag a domain
 * type into the UI module's API surface. The ViewModel converts.
 */
data class MapPanelPoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Background styling information for rewind panels.
 *
 * Defines the visual appearance of a panel's background, supporting either
 * solid colors or image backgrounds.
 *
 * @property color Optional hex color value, null for default theme color
 * @property uri Optional URI to an image resource for background
 */
data class RewindPanelBackgroundSpec(
    val color: Long? = null,
    val uri: String? = null,
)
