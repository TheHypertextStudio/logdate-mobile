@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.feature.rewind.ui.overview.RewindSceneMemory
import app.logdate.ui.platform.PlatformIcons
import coil3.compose.AsyncImage
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.cd_rewind_journal_photo
import logdate.client.feature.rewind.generated.resources.cd_rewind_video_frame
import logdate.client.feature.rewind.generated.resources.rewind_media_unavailable
import logdate.client.feature.rewind.generated.resources.rewind_recording_memory
import org.jetbrains.compose.resources.stringResource

/** A memory's own material, without a cover title, metadata row, or caption panel. */
@Composable
internal fun RewindSceneObject(
    memory: RewindSceneMemory,
    foreground: Boolean,
    dateLabel: String,
    writingStyle: TextStyle,
    onImageAspect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (memory) {
        is RewindSceneMemory.Visual -> {
            var failed by remember(memory.uri) { mutableStateOf(false) }
            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = memory.uri,
                    contentDescription =
                        memory.caption ?: stringResource(
                            if (memory.isVideo) Res.string.cd_rewind_video_frame else Res.string.cd_rewind_journal_photo,
                            dateLabel,
                        ),
                    contentScale = if (foreground) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = {
                        failed = false
                        val image = it.result.image
                        if (image.height > 0) onImageAspect(image.width.toFloat() / image.height)
                    },
                    onError = { failed = true },
                )
                if (failed) {
                    Text(
                        stringResource(Res.string.rewind_media_unavailable),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                if (memory.isVideo) {
                    Icon(
                        PlatformIcons.play(),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(24.dp),
                    )
                }
            }
        }
        is RewindSceneMemory.Writing -> {
            val density = LocalDensity.current
            BoxWithConstraints(
                modifier
                    .background(
                        lerp(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.tertiaryContainer, .2f),
                    ).padding(28.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val lineHeight = with(density) { writingStyle.lineHeight.toDp() }
                val lines = (maxHeight / lineHeight).toInt().coerceIn(1, 10)
                Text(
                    memory.text,
                    style = writingStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        is RewindSceneMemory.Recording -> {
            Column(
                modifier.background(MaterialTheme.colorScheme.tertiaryContainer).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        PlatformIcons.mic(),
                        stringResource(Res.string.rewind_recording_memory),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    if (memory.durationMs > 0) {
                        val seconds = memory.durationMs / 1000
                        Text(
                            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                if (!memory.transcript.isNullOrBlank() && LocalDensity.current.fontScale < 1.5f) {
                    Text(
                        memory.transcript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
