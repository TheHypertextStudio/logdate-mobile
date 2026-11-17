@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.handwritingDelegator
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.rememberSystemReduceMotion
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.camera
import logdate.client.feature.editor.generated.resources.entry_creation_handwrite
import logdate.client.feature.editor.generated.resources.entry_creation_photos
import logdate.client.feature.editor.generated.resources.entry_creation_voice
import logdate.client.feature.editor.generated.resources.memory_write
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StoriesCaptureModes(
    mode: StoryCaptureMode?,
    onMode: (StoryCaptureMode) -> Unit,
    compact: Boolean = false,
    horizontalInset: Dp = 16.dp,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val reduceMotion = rememberSystemReduceMotion().value
    val activeLabel = mode?.let { captureChoiceLabel(it) }.orEmpty()
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    MaterialTheme(motionScheme = MotionScheme.expressive()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = horizontalInset)) {
            val activeLabelWidth =
                with(density) {
                    textMeasurer
                        .measure(activeLabel, labelStyle)
                        .size.width
                        .toDp()
                }
            val minimumLabeledWidth = maxOf(activeLabelWidth + 42.dp + 48.dp * 4 + 16.dp, 48.dp * 6.25f + 16.dp)
            val showLabel = density.fontScale < 1.5f && maxWidth >= minimumLabeledWidth
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StoryCaptureMode.entries.forEachIndexed { index, item ->
                    val active = mode == item
                    val itemLabel = captureChoiceLabel(item)
                    val widthShare by animateFloatAsState(
                        if (active && showLabel) 2.25f else 1f,
                        if (reduceMotion) snap() else spring(dampingRatio = 0.8f, stiffness = 450f),
                        label = "captureModeWidth",
                    )
                    val outline =
                        RoundedCornerShape(
                            topStart = if (index == 0) 28.dp else 8.dp,
                            bottomStart = if (index == 0) 28.dp else 8.dp,
                            topEnd = if (index == StoryCaptureMode.entries.lastIndex) 28.dp else 8.dp,
                            bottomEnd = if (index == StoryCaptureMode.entries.lastIndex) 28.dp else 8.dp,
                        )
                    ToggleButton(
                        checked = active,
                        enabled = enabled,
                        onCheckedChange = { onMode(item) },
                        modifier =
                            Modifier
                                .weight(widthShare)
                                .height(if (compact) 48.dp else 56.dp)
                                // A pen that starts writing on Write opens the writing and keeps going in it.
                                .then(
                                    if (item == StoryCaptureMode.Write && enabled) {
                                        Modifier.handwritingDelegator { onMode(item) }
                                    } else {
                                        Modifier
                                    },
                                ).semantics {
                                    contentDescription = itemLabel
                                    selected = active
                                    role = Role.RadioButton
                                },
                        shapes =
                            ToggleButtonDefaults.shapes(
                                shape = outline,
                                pressedShape = RoundedCornerShape(16.dp),
                                checkedShape = RoundedCornerShape(24.dp),
                            ),
                        colors =
                            ToggleButtonDefaults.toggleButtonColors(
                                containerColor = StoryChrome,
                                contentColor = StoryMuted,
                                checkedContainerColor = StoryAccent,
                                checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val labelWidth =
                                with(density) {
                                    textMeasurer
                                        .measure(itemLabel, labelStyle)
                                        .size.width
                                        .toDp()
                                }
                            val labelFits = maxWidth >= labelWidth + 42.dp
                            Row(
                                Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    when (item) {
                                        StoryCaptureMode.Write -> PlatformIcons.text()
                                        StoryCaptureMode.Handwrite -> PlatformIcons.edit()
                                        StoryCaptureMode.Camera -> PlatformIcons.camera()
                                        StoryCaptureMode.Voice -> PlatformIcons.mic()
                                        StoryCaptureMode.Photos -> PlatformIcons.photoLibrary()
                                    },
                                    null,
                                    Modifier.size(20.dp),
                                )
                                if (active && showLabel && labelFits) {
                                    Text(itemLabel, style = labelStyle, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun captureChoiceLabel(mode: StoryCaptureMode): String =
    stringResource(
        when (mode) {
            StoryCaptureMode.Write -> Res.string.memory_write
            StoryCaptureMode.Handwrite -> Res.string.entry_creation_handwrite
            StoryCaptureMode.Camera -> Res.string.camera
            StoryCaptureMode.Voice -> Res.string.entry_creation_voice
            StoryCaptureMode.Photos -> Res.string.entry_creation_photos
        },
    )
