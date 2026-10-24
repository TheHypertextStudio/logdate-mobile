package app.logdate.feature.editor.ui.audio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.PlaybackState
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.ui.platform.PlatformIcons
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.pause
import logdate.client.feature.editor.generated.resources.play
import logdate.client.feature.editor.generated.resources.resume_recording
import logdate.client.feature.editor.generated.resources.start_recording
import logdate.client.feature.editor.generated.resources.stop_recording
import org.jetbrains.compose.resources.stringResource

/**
 * A button that animates between play/pause/record icons and state-appropriate shapes.
 *
 * Features graceful path-morphing animations where:
 * - Play triangle morphs smoothly into pause bars
 * - Play triangle morphs into stop square (for recording)
 * - Mic icon uses standard AnimatedContent for recording start
 *
 * @param playbackState Current playback state (PLAYING, PAUSED, STOPPED)
 * @param recordingState Current recording state (RECORDING, INACTIVE, etc)
 * @param onClick Callback to be invoked when the button is clicked
 * @param modifier Modifier for the button
 * @param iconSize Size of the icon in dp
 * @param colors Button colors configuration, defaults based on current state
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AnimatedPlayPauseButton(
    playbackState: PlaybackState,
    recordingState: RecordingState = RecordingState.INACTIVE,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    colors: IconButtonColors =
        when {
            recordingState == RecordingState.RECORDING -> {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            }

            recordingState == RecordingState.PROCESSING -> {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            }

            playbackState == PlaybackState.PLAYING -> {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }

            else -> {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
) {
    // Determine if the button is in an active state (playing or recording)
    val isActive =
        playbackState == PlaybackState.PLAYING ||
            recordingState == RecordingState.RECORDING

    // Animate corner radius based on active state
    // Circle when inactive, rounded square when active
    val cornerRadius by animateFloatAsState(
        targetValue = if (isActive) 12f else 50f, // 50% = circle, 12 = rounded square
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        label = "ButtonCornerRadiusAnimation",
    )

    // Create the shape with animated corner radius
    val shape = RoundedCornerShape(cornerRadius.dp)

    // Determine content description for accessibility
    val description =
        stringResource(
            when {
                recordingState == RecordingState.RECORDING -> Res.string.stop_recording
                recordingState == RecordingState.PAUSED -> Res.string.resume_recording
                recordingState == RecordingState.INACTIVE && playbackState == PlaybackState.STOPPED -> Res.string.start_recording
                playbackState == PlaybackState.PLAYING -> Res.string.pause
                else -> Res.string.play
            },
        )

    FilledIconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = shape,
        colors = colors,
    ) {
        // Get content color for morphing icons
        val contentColor =
            when {
                recordingState == RecordingState.RECORDING -> MaterialTheme.colorScheme.onError
                recordingState == RecordingState.PROCESSING -> MaterialTheme.colorScheme.onError
                else -> MaterialTheme.colorScheme.onPrimary
            }

        // Use morphing icons for play/pause/stop transitions
        // Use AnimatedContent only for mic icon (recording start state)
        when {
            // Recording active: show morphing stop icon
            recordingState == RecordingState.RECORDING -> {
                MorphingPlayStopIcon(
                    isActive = true,
                    size = iconSize,
                    tint = contentColor,
                )
            }

            // Mic states: use AnimatedContent for mic icon
            recordingState == RecordingState.PAUSED ||
                (recordingState == RecordingState.INACTIVE && playbackState == PlaybackState.STOPPED) -> {
                AnimatedContent(
                    targetState = recordingState,
                    transitionSpec = {
                        fadeIn(spring(stiffness = Spring.StiffnessLow)) +
                            scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessLow)) togetherWith
                            fadeOut(spring(stiffness = Spring.StiffnessLow)) +
                            scaleOut(targetScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessLow))
                    },
                    label = "MicIconAnimation",
                ) { _ ->
                    Icon(
                        painter = PlatformIcons.mic(),
                        contentDescription = null, // Handled by button semantics
                        modifier = Modifier.size(iconSize),
                    )
                }
            }

            // Playback states: use morphing play/pause icon
            else -> {
                MorphingPlayPauseIcon(
                    isPlaying = playbackState == PlaybackState.PLAYING,
                    size = iconSize,
                    tint = contentColor,
                )
            }
        }
    }
}

/**
 * A button that animates between play and pause icons.
 *
 * Features graceful path-morphing animation where the play triangle
 * smoothly transforms into two pause bars.
 *
 * Also includes a shape animation that transitions between a circle (when paused)
 * and a rounded square (when playing).
 *
 * This simpler version is maintained for backward compatibility.
 *
 * @param isPlaying Current playback state, true for playing (pause icon), false for paused (play icon)
 * @param onClick Callback to be invoked when the button is clicked
 * @param modifier Modifier for the button
 * @param iconSize Size of the icon in dp
 * @param colors Button colors configuration, defaults to filled button with primary color
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AnimatedPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    colors: IconButtonColors =
        IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
) {
    // Animate corner radius based on playing state
    val cornerRadius by animateFloatAsState(
        targetValue = if (isPlaying) 12f else 50f, // 50% = circle, 12 = rounded square
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        label = "ButtonCornerRadiusAnimation",
    )

    val shape = RoundedCornerShape(cornerRadius.dp)
    val description = stringResource(if (isPlaying) Res.string.pause else Res.string.play)

    FilledIconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = shape,
        colors = colors,
    ) {
        MorphingPlayPauseIcon(
            isPlaying = isPlaying,
            size = iconSize,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
