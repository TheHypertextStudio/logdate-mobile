@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.client.media.MediaObject
import app.logdate.feature.editor.ui.media.ManagedMediaSelectionState
import app.logdate.ui.platform.PlatformIcons
import coil3.compose.AsyncImage
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.adding_selected_photo
import logdate.client.feature.editor.generated.resources.allow_access
import logdate.client.feature.editor.generated.resources.browse_all_photos
import logdate.client.feature.editor.generated.resources.editor_image_permission_prompt
import logdate.client.feature.editor.generated.resources.loading_recent_photos
import logdate.client.feature.editor.generated.resources.manage_photo_selection
import logdate.client.feature.editor.generated.resources.no_recent_photos_found
import logdate.client.feature.editor.generated.resources.open_app_settings
import logdate.client.feature.editor.generated.resources.partial_photo_access_message
import logdate.client.feature.editor.generated.resources.pick_from_your_library_or_browse_all_your_photos
import logdate.client.feature.editor.generated.resources.recent_photos
import logdate.client.feature.editor.generated.resources.recent_photos_load_failed
import logdate.client.feature.editor.generated.resources.selected_photo_add_failed
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import logdate.client.ui.generated.resources.Res as UiRes

internal const val RECENT_IMAGE_PREVIEW_LIMIT = 24

internal sealed interface ImagePickerLibraryState {
    data object Loading : ImagePickerLibraryState

    data class Loaded(
        val images: List<MediaObject.Image>,
    ) : ImagePickerLibraryState

    /**
     * The user granted access to a subset of their photo library on Android 14+
     * via the `READ_MEDIA_VISUAL_USER_SELECTED` flow. Render the shared photos
     * alongside a "Manage selection" affordance so they can adjust which photos
     * LogDate sees.
     */
    data class PartialAccess(
        val images: List<MediaObject.Image>,
    ) : ImagePickerLibraryState

    data class PermissionRequired(
        val permanentlyDenied: Boolean = false,
    ) : ImagePickerLibraryState

    data object Empty : ImagePickerLibraryState

    data object Error : ImagePickerLibraryState
}

sealed interface ImagePickerPreviewState {
    data object Loading : ImagePickerPreviewState

    data class Loaded(
        val sampleImageUri: String,
        val itemCount: Int = 12,
    ) : ImagePickerPreviewState

    data class PermissionRequired(
        val permanentlyDenied: Boolean = false,
    ) : ImagePickerPreviewState

    data object Empty : ImagePickerPreviewState

    data object Error : ImagePickerPreviewState
}

@Composable
fun ImagePickerPreviewContent(
    state: ImagePickerPreviewState,
    importState: ManagedMediaSelectionState = ManagedMediaSelectionState.Idle,
    previewImagePainter: Painter? = null,
    modifier: Modifier = Modifier,
) {
    ImagePickerBrowser(
        state = state.toLibraryState(),
        importState = importState,
        onImageSelected = {},
        onBrowseLibrary = {},
        onRequestLibraryAccess = {},
        onOpenSettings = {},
        onRetryLoading = {},
        onRetryImport = {},
        previewImagePainter = previewImagePainter,
        modifier = modifier,
    )
}

internal fun recentImagePreviews(
    media: List<MediaObject>,
    limit: Int = RECENT_IMAGE_PREVIEW_LIMIT,
): List<MediaObject.Image> =
    media
        .filterIsInstance<MediaObject.Image>()
        .sortedByDescending { it.timestamp }
        .take(limit)

@Composable
internal fun ImagePickerBrowser(
    state: ImagePickerLibraryState,
    importState: ManagedMediaSelectionState = ManagedMediaSelectionState.Idle,
    onImageSelected: (String) -> Unit,
    onBrowseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestLibraryAccess: (() -> Unit)? = null,
    onManageSelection: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onRetryLoading: (() -> Unit)? = null,
    onRetryImport: (() -> Unit)? = null,
    previewImagePainter: Painter? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ImagePickerHeader(
                onBrowseLibrary = onBrowseLibrary,
                enabled = importState != ManagedMediaSelectionState.Importing,
            )
        }

        when (importState) {
            ManagedMediaSelectionState.Idle -> {
                Unit
            }

            ManagedMediaSelectionState.Importing -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImagePickerMessageCard(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                            )
                        },
                        message = stringResource(Res.string.adding_selected_photo),
                    )
                }
            }

            ManagedMediaSelectionState.Failed -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImagePickerMessageCard(
                        icon = {
                            Icon(
                                painter = PlatformIcons.addPhoto(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        message = stringResource(Res.string.selected_photo_add_failed),
                        action =
                            onRetryImport?.let { retry ->
                                {
                                    OutlinedButton(onClick = retry) {
                                        Text(stringResource(UiRes.string.common_try_again))
                                    }
                                }
                            },
                    )
                }
            }
        }

        when (state) {
            ImagePickerLibraryState.Loading -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImagePickerMessageCard(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                            )
                        },
                        message = stringResource(Res.string.loading_recent_photos),
                    )
                }
            }

            is ImagePickerLibraryState.Loaded -> {
                items(
                    items = state.images,
                    key = { "${it.uri}_${it.timestamp.toEpochMilliseconds()}" },
                ) { image ->
                    RecentImageTile(
                        image = image,
                        onClick = { onImageSelected(image.uri) },
                        enabled = importState != ManagedMediaSelectionState.Importing,
                        previewImagePainter = previewImagePainter,
                    )
                }
            }

            is ImagePickerLibraryState.PartialAccess -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PartialAccessBanner(onManageSelection = onManageSelection)
                }
                items(
                    items = state.images,
                    key = { "${it.uri}_${it.timestamp.toEpochMilliseconds()}" },
                ) { image ->
                    RecentImageTile(
                        image = image,
                        onClick = { onImageSelected(image.uri) },
                        enabled = importState != ManagedMediaSelectionState.Importing,
                        previewImagePainter = previewImagePainter,
                    )
                }
            }

            is ImagePickerLibraryState.PermissionRequired -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImagePickerMessageCard(
                        icon = {
                            Icon(
                                painter = PlatformIcons.photoLibrary(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        message = stringResource(Res.string.editor_image_permission_prompt),
                        action =
                            when {
                                state.permanentlyDenied && onOpenSettings != null -> {
                                    {
                                        OutlinedButton(onClick = onOpenSettings) {
                                            Text(stringResource(Res.string.open_app_settings))
                                        }
                                    }
                                }

                                onRequestLibraryAccess != null -> {
                                    {
                                        Button(onClick = onRequestLibraryAccess) {
                                            Text(stringResource(Res.string.allow_access))
                                        }
                                    }
                                }

                                else -> {
                                    null
                                }
                            },
                    )
                }
            }

            ImagePickerLibraryState.Empty -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImagePickerMessageCard(
                        icon = {
                            Icon(
                                painter = PlatformIcons.addPhoto(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        message = stringResource(Res.string.no_recent_photos_found),
                    )
                }
            }

            ImagePickerLibraryState.Error -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImagePickerMessageCard(
                        icon = {
                            Icon(
                                painter = PlatformIcons.photoLibrary(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                        },
                        message = stringResource(Res.string.recent_photos_load_failed),
                        action =
                            onRetryLoading?.let { retry ->
                                {
                                    OutlinedButton(onClick = retry) {
                                        Text(stringResource(UiRes.string.common_try_again))
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

private fun ImagePickerPreviewState.toLibraryState(): ImagePickerLibraryState =
    when (this) {
        ImagePickerPreviewState.Loading -> {
            ImagePickerLibraryState.Loading
        }

        is ImagePickerPreviewState.Loaded -> {
            ImagePickerLibraryState.Loaded(
                images =
                    List(itemCount.coerceAtLeast(1)) { index ->
                        MediaObject.Image(
                            uri = sampleImageUri,
                            size = 0,
                            name = "Preview image ${index + 1}",
                            timestamp = Clock.System.now() - index.minutes,
                        )
                    },
            )
        }

        is ImagePickerPreviewState.PermissionRequired -> {
            ImagePickerLibraryState.PermissionRequired(permanentlyDenied = permanentlyDenied)
        }

        ImagePickerPreviewState.Empty -> {
            ImagePickerLibraryState.Empty
        }

        ImagePickerPreviewState.Error -> {
            ImagePickerLibraryState.Error
        }
    }

@Composable
private fun PartialAccessBanner(
    onManageSelection: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.partial_photo_access_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onManageSelection != null) {
                OutlinedButton(onClick = onManageSelection) {
                    Text(stringResource(Res.string.manage_photo_selection))
                }
            }
        }
    }
}

@Composable
private fun ImagePickerHeader(
    onBrowseLibrary: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.recent_photos),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(Res.string.pick_from_your_library_or_browse_all_your_photos),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            onClick = onBrowseLibrary,
            enabled = enabled,
        ) {
            Icon(
                painter = PlatformIcons.addPhoto(),
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(stringResource(Res.string.browse_all_photos))
        }
    }
}

@Composable
private fun RecentImageTile(
    image: MediaObject.Image,
    onClick: () -> Unit,
    enabled: Boolean,
    previewImagePainter: Painter?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(
                    enabled = enabled,
                    onClick = onClick,
                ),
    ) {
        if (previewImagePainter != null) {
            Image(
                painter = previewImagePainter,
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = image.uri,
                contentDescription = image.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ImagePickerMessageCard(
    icon: @Composable () -> Unit,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            action?.invoke()
        }
    }
}
