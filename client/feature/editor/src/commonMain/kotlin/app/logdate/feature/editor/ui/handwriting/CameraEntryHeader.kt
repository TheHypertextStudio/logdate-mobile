@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.feature.editor.ui.LocalEditorInputFocusBookmarks
import app.logdate.feature.editor.ui.blockFocusSpring
import app.logdate.feature.editor.ui.blocks.memoryBlockLabel
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.MediaBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.editor.isEmptyCreationBlock
import app.logdate.feature.editor.ui.layout.LocalEntryChromeHeight
import app.logdate.ui.common.IconTooltip
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.util.localTime
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.camera_place_keep
import logdate.client.feature.editor.generated.resources.camera_place_leave_out
import logdate.client.feature.editor.generated.resources.camera_place_not_saved
import logdate.client.feature.editor.generated.resources.camera_place_saved
import logdate.client.feature.editor.generated.resources.entry_page_block_focus
import logdate.client.feature.editor.generated.resources.entry_page_return_to_entry
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The clock the camera header reads its time from; previews fix it so renders stay stable. */
internal val LocalCameraHeaderClock = staticCompositionLocalOf<Clock> { Clock.System }

/**
 * What the camera header's filmstrip shows: the entry's memories in order, leaving out the live camera
 * itself and any other block that has nothing in it yet.
 */
internal fun cameraFilmstripMemories(
    blocks: List<EntryBlockUiState>,
    cameraId: Uuid?,
): List<EntryBlockUiState> = blocks.filter { it.id != cameraId && !it.isEmptyCreationBlock() }

/**
 * The entry's header while the camera is live: the time, and a filmstrip of what this entry holds so far.
 *
 * It answers "where is this going?" without leaving the camera. While a capture is in progress it steps
 * aside, so only the image, the timer and the shutter stay.
 */
@Composable
internal fun CameraEntryHeader(
    camera: CameraBlockUiState,
    entryBlocks: List<EntryBlockUiState>,
    recording: Boolean,
    onDone: () -> Unit,
) {
    val focusBookmarks = LocalEditorInputFocusBookmarks.current
    val returnDescription = stringResource(Res.string.entry_page_return_to_entry)
    val focusTitle = stringResource(Res.string.entry_page_block_focus)
    val memories = cameraFilmstripMemories(entryBlocks, camera.id)
    val busy = !camera.isEmptyCreationBlock()
    val reduceMotion = rememberSystemReduceMotion().value
    val contextAlpha by animateFloatAsState(
        if (busy) 0f else 1f,
        if (reduceMotion) snap() else blockFocusSpring(),
        label = "cameraHeaderContext",
    )
    val now by rememberMinuteClock(LocalCameraHeaderClock.current)
    Row(
        Modifier
            .fillMaxWidth()
            .height(LocalEntryChromeHeight.current)
            .padding(horizontal = 12.dp)
            .testTag("entry_camera_header")
            .semantics { paneTitle = focusTitle },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTooltip(returnDescription) {
            FilledTonalIconButton(
                onClick = {
                    focusBookmarks?.capture(camera.id)
                    onDone()
                },
                enabled = !recording && !busy,
                modifier = Modifier.size(48.dp).semantics { contentDescription = returnDescription },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = StoryChrome),
            ) {
                Icon(Icons.Outlined.Check, null, Modifier.size(24.dp))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .graphicsLayer { alpha = contextAlpha }
                .then(if (busy) Modifier.semantics { hideFromAccessibility() } else Modifier),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalCameraPlace.current?.let { place ->
                    CameraPlaceLine(place, enabled = !busy, modifier = Modifier.weight(1f, fill = false))
                    Text(" · ", style = MaterialTheme.typography.labelMedium, color = CameraHeaderMuted)
                }
                Text(
                    // The time is formatted in Latin script; isolate it so right-to-left text keeps its order.
                    "\u2068${now.localTime}\u2069",
                    Modifier.testTag("entry_camera_header_time"),
                    style = MaterialTheme.typography.labelMedium,
                    color = CameraHeaderMuted,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (memories.isNotEmpty()) CameraFilmstrip(memories)
        }
    }
}

/**
 * The place shown over the camera, and whether shots taken now are saved with it.
 *
 * The host provides it only when location is on and the place has a name, so the header never shows a
 * permission prompt or an error in its place.
 */
internal class CameraPlaceUi(
    val name: String,
    val saving: Boolean,
    val onSavingChange: (Boolean) -> Unit,
)

internal val LocalCameraPlace = staticCompositionLocalOf<CameraPlaceUi?> { null }

/** A quiet pin and the place name; tapping it chooses whether shots are saved with the place. */
@Composable
private fun CameraPlaceLine(
    place: CameraPlaceUi,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val description =
        if (place.saving) {
            stringResource(Res.string.camera_place_saved, place.name)
        } else {
            stringResource(Res.string.camera_place_not_saved)
        }
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = enabled, role = Role.Button) { menuOpen = true }
                .testTag("entry_camera_place")
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                if (place.saving) Icons.Outlined.Place else Icons.Outlined.LocationOff,
                null,
                // Sized in text units so the pin grows with the place name at larger text sizes.
                Modifier.size(with(LocalDensity.current) { 14.sp.toDp() }),
                tint = CameraHeaderMuted,
            )
            Text(
                place.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (place.saving) CameraHeaderMuted else CameraHeaderMuted.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.camera_place_keep)) },
                leadingIcon = { Icon(Icons.Outlined.Place, null) },
                trailingIcon = { if (place.saving) Icon(Icons.Outlined.Check, null) },
                onClick = {
                    place.onSavingChange(true)
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.camera_place_leave_out)) },
                leadingIcon = { Icon(Icons.Outlined.LocationOff, null) },
                trailingIcon = { if (!place.saving) Icon(Icons.Outlined.Check, null) },
                onClick = {
                    place.onSavingChange(false)
                    menuOpen = false
                },
            )
        }
    }
}

/** The camera page is always dark, whatever the theme, so its header text is light. */
private val CameraHeaderMuted = Color.White.copy(alpha = 0.72f)
private val CameraFilmstripTile = Color.White.copy(alpha = 0.16f)
private val CameraFilmstripSize = 28.dp

@Composable
private fun CameraFilmstrip(memories: List<EntryBlockUiState>) {
    val state = rememberLazyListState()
    // New memories land at the trailing end, so keep it in view as the entry grows.
    LaunchedEffect(memories.size) { state.animateScrollToItem(memories.lastIndex) }
    LazyRow(
        Modifier.fillMaxWidth().height(CameraFilmstripSize).testTag("entry_camera_filmstrip"),
        state = state,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        userScrollEnabled = true,
    ) {
        items(memories, key = { it.id.toString() }) { memory ->
            CameraFilmstripThumbnail(memory, Modifier.animateItem())
        }
    }
}

@Composable
private fun CameraFilmstripThumbnail(
    memory: EntryBlockUiState,
    modifier: Modifier = Modifier,
) {
    val label = memoryBlockLabel(memory)
    val image = (memory as? MediaBlockUiState)?.uri?.takeIf { memory !is AudioBlockUiState }
    Box(
        modifier
            .size(CameraFilmstripSize)
            .clip(RoundedCornerShape(6.dp))
            .background(CameraFilmstripTile)
            .testTag("entry_camera_filmstrip_${memory.id}")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(image, null, Modifier.size(CameraFilmstripSize), contentScale = ContentScale.Crop)
        } else {
            Icon(memoryGlyph(memory), null, Modifier.size(16.dp), tint = CameraHeaderMuted)
        }
    }
}

@Composable
private fun memoryGlyph(memory: EntryBlockUiState) =
    when (memory) {
        is TextBlockUiState -> PlatformIcons.text()
        is HandwritingBlockUiState -> PlatformIcons.edit()
        is AudioBlockUiState -> PlatformIcons.mic()
        is VideoBlockUiState -> PlatformIcons.play()
        is CameraBlockUiState -> if (memory.mediaType == CapturedMediaType.VIDEO) PlatformIcons.play() else PlatformIcons.camera()
        is ImageBlockUiState -> PlatformIcons.photoLibrary()
    }

/** The current time, updated on each minute boundary. */
@Composable
private fun rememberMinuteClock(clock: Clock) =
    produceState<Instant>(clock.now(), clock) {
        while (true) {
            val millis = value.toEpochMilliseconds()
            delay(60_000L - millis.mod(60_000L))
            value = clock.now()
        }
    }
