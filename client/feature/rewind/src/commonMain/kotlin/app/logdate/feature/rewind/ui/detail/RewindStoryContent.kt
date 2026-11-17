@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.rewind.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import app.logdate.feature.rewind.ui.AudioNoteRewindPanelUiState
import app.logdate.feature.rewind.ui.AudioPanelPlaybackUiState
import app.logdate.feature.rewind.ui.BasicTextRewindPanelUiState
import app.logdate.feature.rewind.ui.BigStatisticRewindPanelUiState
import app.logdate.feature.rewind.ui.HighlightedQuoteRewindPanelUiState
import app.logdate.feature.rewind.ui.ImageRewindPanelUiState
import app.logdate.feature.rewind.ui.LocationMapRewindPanelUiState
import app.logdate.feature.rewind.ui.NarrativeContextRewindPanelUiState
import app.logdate.feature.rewind.ui.ReflectionPromptRewindPanelUiState
import app.logdate.feature.rewind.ui.RewindPanelUiState
import app.logdate.feature.rewind.ui.SubtitledRewindPanelUiState
import app.logdate.feature.rewind.ui.TextNoteRewindPanelUiState
import app.logdate.feature.rewind.ui.TransitionRewindPanelUiState
import app.logdate.feature.rewind.ui.WeatherChipUiState
import app.logdate.ui.workspace.LocalWorkspacePlaybackInsets
import coil3.compose.AsyncImage
import logdate.client.feature.rewind.generated.resources.*
import logdate.client.feature.rewind.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * Main content renderer for rewind story panels.
 *
 * This composable handles rendering different types of rewind panel content within
 * the Instagram Stories-like interface. It provides a consistent visual framework
 * while allowing for diverse content types.
 *
 * ## Supported Panel Types:
 * - **BasicTextRewindPanelUiState**: Simple text with background
 * - **SubtitledRewindPanelUiState**: Title/subtitle with optional background image
 * - **BigStatisticRewindPanelUiState**: Large statistic display with description
 *
 * ## Design Principles:
 * - **Consistent Layout**: All panels follow similar spacing and typography patterns
 * - **Visual Hierarchy**: Clear distinction between titles, subtitles, and body content
 * - **Background Flexibility**: Supports both solid colors and background images
 * - **Readability**: Text uses localized scrims for legibility over images
 *
 * @param panel The rewind panel data to render
 * @param modifier Modifier for customizing the content container
 */
@Composable
fun RewindStoryContent(
    panel: RewindPanelUiState,
    modifier: Modifier = Modifier,
    audioPlaybackState: AudioPanelPlaybackUiState = AudioPanelPlaybackUiState(),
    onToggleAudioPlayback: (Uuid, String) -> Unit = { _, _ -> },
) {
    when (panel) {
        is BasicTextRewindPanelUiState -> {
            BasicTextPanel(
                text = panel.text,
                backgroundColor = panel.background.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
                backgroundImageUri = panel.background.uri,
                modifier = modifier,
            )
        }

        is SubtitledRewindPanelUiState -> {
            SubtitledPanel(
                title = panel.title,
                subtitle = panel.subtitle.asString(),
                backgroundImageUri = panel.backgroundUri,
                weatherChip = panel.weatherChip,
                modifier = modifier,
            )
        }

        is BigStatisticRewindPanelUiState -> {
            StatisticPanel(
                title = panel.title,
                statistic = panel.statistic,
                units = panel.units.asString(),
                description = panel.description.asString(),
                backgroundColor = panel.background.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
                backgroundImageUri = panel.background.uri,
                modifier = modifier,
            )
        }

        is TextNoteRewindPanelUiState -> {
            TextNotePanel(
                content = panel.content,
                dateFormatted = panel.dateFormatted.asString(),
                backgroundColor = panel.background.color?.let { Color(it) } ?: Color(0xFF1A1A1A),
                backgroundImageUri = panel.background.uri,
                modifier = modifier,
                memoryId = panel.sourceId.toString(),
            )
        }

        is ImageRewindPanelUiState -> {
            ImageNotePanel(
                imageUri = panel.imageUri,
                caption = panel.caption,
                dateFormatted = panel.dateFormatted.asString(),
                isVideoFrame = panel.isVideoFrame,
                modifier = modifier,
                memoryId = panel.sourceId.toString(),
            )
        }

        is NarrativeContextRewindPanelUiState -> {
            NarrativeContextPanel(
                contextText = panel.contextText,
                backgroundImageUri = panel.backgroundImageUri,
                modifier = modifier,
            )
        }

        is TransitionRewindPanelUiState -> {
            TransitionPanel(
                transitionText = panel.transitionText,
                modifier = modifier,
            )
        }

        is HighlightedQuoteRewindPanelUiState -> {
            HighlightedQuotePanel(
                text = panel.text,
                whyItHits = panel.whyItHits,
                accentSeed = panel.accentSeed,
                modifier = modifier,
            )
        }

        is ReflectionPromptRewindPanelUiState -> {
            ReflectionPromptPanel(
                observation = panel.observation,
                invitation = panel.invitation,
                accentSeed = panel.accentSeed,
                modifier = modifier,
                existingResponse = panel.existingResponse?.takeIf { panel.repliesAllowed },
            )
        }

        is LocationMapRewindPanelUiState -> {
            LocationMapPanel(
                points = panel.points,
                title = panel.title.asString(),
                subtitle = panel.subtitle.asString(),
                accentSeed = panel.accentSeed,
                modifier = modifier,
            )
        }

        is AudioNoteRewindPanelUiState -> {
            AudioNotePanel(
                sourceId = panel.sourceId,
                uri = panel.uri,
                durationMs = panel.durationMs,
                transcriptionText = panel.transcriptionText,
                dateFormatted = panel.dateFormatted.asString(),
                recordedAt = panel.timestamp,
                cachedAmplitudes = panel.cachedAmplitudes,
                isPlaying = audioPlaybackState.playingSourceId == panel.sourceId,
                playbackProgress = if (audioPlaybackState.playingSourceId == panel.sourceId) audioPlaybackState.progress else 0f,
                onTogglePlayback = onToggleAudioPlayback,
                modifier = modifier,
            )
        }

        // Add this catch-all branch for any future panel types
        else -> {
            // This should never happen with the current implementation
            Box(
                modifier = modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.unsupported_panel_type),
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * A simple text panel with optional background image.
 *
 * Displays centered text content with flexible background options. When a background
 * image is provided, keeps contrast behind its text without darkening the whole image.
 *
 * ## Visual Design:
 * - **Typography**: Uses headline medium for prominent text display
 * - **Alignment**: Centers text both horizontally and vertically
 * - **Background**: Supports both solid colors and images with overlay
 * - **Padding**: Generous margins for comfortable reading
 *
 * @param text The main text content to display
 * @param backgroundColor Solid background color (used when no image provided)
 * @param backgroundImageUri Optional background image URI
 * @param modifier Modifier for customizing the panel container
 */
@Composable
private fun BasicTextPanel(
    text: String,
    backgroundColor: Color,
    backgroundImageUri: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Background
        if (backgroundImageUri != null) {
            AsyncImage(
                model = backgroundImageUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
            )
        }

        // Content
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium,
            modifier =
                Modifier
                    .padding(24.dp)
                    .background(
                        if (backgroundImageUri !=
                            null
                        ) {
                            Color.Black.copy(alpha = 0.6f)
                        } else {
                            Color.Transparent
                        },
                    ).padding(16.dp),
        )
    }
}

/**
 * A title/subtitle panel with optional background image.
 *
 * Displays hierarchical text content with a title and subtitle in a visually
 * appealing layout. Optimized for storytelling with clear content hierarchy.
 *
 * ## Content Hierarchy:
 * - **Title**: Large, bold text for primary message
 * - **Subtitle**: Medium-sized text for supporting details
 * - **Layout**: Vertically stacked with appropriate spacing
 *
 * ## Background Support:
 * - **Image**: When provided, displays as full-screen background with overlay
 * - **Fallback**: Uses primary container color when no image available
 *
 * @param title Primary headline text
 * @param subtitle Supporting descriptive text
 * @param backgroundImageUri Optional background image URI
 * @param modifier Modifier for customizing the panel container
 */
@Composable
private fun SubtitledPanel(
    title: String,
    subtitle: String,
    backgroundImageUri: String?,
    weatherChip: WeatherChipUiState?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Background
        if (backgroundImageUri != null) {
            AsyncImage(
                model = backgroundImageUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
            )
        }

        // Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(if (backgroundImageUri != null) Color.Black.copy(alpha = 0.6f) else Color.Transparent)
                    .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
            )
        }

        // Weather chip overlay — quietly placed in the top-end corner so it grounds
        // the title without competing with it.
        if (weatherChip != null) {
            WeatherChip(
                state = weatherChip,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(LocalWorkspacePlaybackInsets.current)
                        .padding(top = 56.dp, end = 16.dp),
            )
        }
    }
}

/**
 * A large statistic display panel for showcasing numerical data.
 *
 * Designed to highlight important metrics and achievements from the user's week.
 * Uses large typography and clear visual hierarchy to make statistics impactful.
 *
 * ## Visual Design:
 * - **Statistic Display**: Extra large typography for maximum impact
 * - **Units**: Smaller text paired with the main number
 * - **Context**: Title and description provide meaning to the statistic
 * - **Layout**: Vertically centered with optimal spacing
 *
 * ## Use Cases:
 * - Steps taken, distance traveled, places visited
 * - Time spent journaling, photos taken, memories captured
 * - Social interactions, new experiences, achievements
 *
 * @param title Contextual title for the statistic
 * @param statistic The numerical value to display prominently
 * @param units Units or label for the statistic (e.g., "steps", "miles", "photos")
 * @param description Additional context or interpretation of the statistic
 * @param backgroundColor Solid background color (used when no image provided)
 * @param backgroundImageUri Optional background image URI
 * @param modifier Modifier for customizing the panel container
 */
@Composable
private fun StatisticPanel(
    title: String,
    statistic: String,
    units: String,
    description: String,
    backgroundColor: Color,
    backgroundImageUri: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Background
        if (backgroundImageUri != null) {
            AsyncImage(
                model = backgroundImageUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
            )
        }

        // Content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier =
                Modifier
                    .padding(24.dp)
                    .background(
                        if (backgroundImageUri !=
                            null
                        ) {
                            Color.Black.copy(alpha = 0.6f)
                        } else {
                            Color.Transparent
                        },
                    ).padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )

            // Statistic display with visual emphasis
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .padding(24.dp),
            ) {
                Text(
                    text = statistic,
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = units,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
            )
        }
    }
}
