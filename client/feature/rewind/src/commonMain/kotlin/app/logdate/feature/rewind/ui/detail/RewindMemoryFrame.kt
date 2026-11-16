@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.rewind_media_loading
import logdate.client.feature.rewind.generated.resources.rewind_media_ready
import logdate.client.feature.rewind.generated.resources.rewind_media_unavailable
import org.jetbrains.compose.resources.stringResource

sealed interface RewindMemoryLoadState {
    data object Loading : RewindMemoryLoadState

    data object Ready : RewindMemoryLoadState

    data object Unavailable : RewindMemoryLoadState
}

/** The actual memory container stays mounted while only its status content changes. */
@Composable
fun RewindMemoryFrame(
    memoryId: String,
    loadState: RewindMemoryLoadState,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    backgroundColor: Color = Color(0xFF1A1A1A),
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val description =
        when (loadState) {
            RewindMemoryLoadState.Loading -> stringResource(Res.string.rewind_media_loading)
            RewindMemoryLoadState.Ready -> stringResource(Res.string.rewind_media_ready)
            RewindMemoryLoadState.Unavailable -> errorMessage ?: stringResource(Res.string.rewind_media_unavailable)
        }
    key(memoryId) {
        Box(
            modifier.clip(shape).background(backgroundColor).testTag("rewind_memory_$memoryId").semantics {
                stateDescription =
                    description
            },
            contentAlignment = Alignment.Center,
            propagateMinConstraints = true,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (loadState == RewindMemoryLoadState.Ready) 1f else 0f)
                    .semantics { if (loadState != RewindMemoryLoadState.Ready) hideFromAccessibility() },
                contentAlignment = Alignment.TopStart,
                propagateMinConstraints = true,
                content = content,
            )
            val statusInput =
                if (loadState == RewindMemoryLoadState.Ready) {
                    Modifier
                } else {
                    // Win child hit testing without consuming the story's gestures.
                    Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                }
            Box(Modifier.matchParentSize().then(statusInput), contentAlignment = Alignment.Center) {
                when (loadState) {
                    RewindMemoryLoadState.Loading -> {
                        CircularProgressIndicator(color = Color.White)
                    }

                    RewindMemoryLoadState.Ready -> {
                        Unit
                    }

                    RewindMemoryLoadState.Unavailable -> {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                            modifier =
                                Modifier
                                    .padding(
                                        24.dp,
                                    ).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
