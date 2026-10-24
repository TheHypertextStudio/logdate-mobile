@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.text.asString
import app.logdate.feature.location.timeline.ui.model.CurrentLocationUiModel
import app.logdate.feature.location.timeline.ui.model.LocationPlaceUiModel
import app.logdate.feature.location.timeline.ui.model.LocationTimelineErrorUiState
import app.logdate.feature.location.timeline.ui.model.LocationTimelineUiState
import app.logdate.ui.platform.PlatformSheet
import kotlinx.coroutines.launch
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.close
import logdate.client.feature.location.timeline.generated.resources.current_location
import logdate.client.feature.location.timeline.generated.resources.location_timeline
import logdate.client.feature.location.timeline.generated.resources.location_timeline_empty_description
import logdate.client.feature.location.timeline.generated.resources.memories_count
import logdate.client.feature.location.timeline.generated.resources.no_location_history_yet
import logdate.client.feature.location.timeline.generated.resources.open_full_location_timeline
import logdate.client.feature.location.timeline.generated.resources.recent_memories
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationTimelineBottomSheet(
    onDismissRequest: () -> Unit,
    onOpenFullTimeline: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationTimelineViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LocationTimelineQuickPeekSheet(
        uiState = uiState,
        onDismissRequest = onDismissRequest,
        onOpenFullTimeline = onOpenFullTimeline,
        onSelectPlace = viewModel::selectPlace,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationTimelineQuickPeekSheet(
    uiState: LocationTimelineUiState,
    onDismissRequest: () -> Unit,
    onOpenFullTimeline: () -> Unit,
    onSelectPlace: (String) -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val hideAndDismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }

    PlatformSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        LocationTimelineQuickPeekSheetContent(
            uiState = uiState,
            onDismissRequest = hideAndDismiss,
            onOpenFullTimeline = onOpenFullTimeline,
            onSelectPlace = onSelectPlace,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun LocationTimelineQuickPeekSheetContent(
    uiState: LocationTimelineUiState,
    onDismissRequest: () -> Unit,
    onOpenFullTimeline: () -> Unit,
    onSelectPlace: (String) -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.location_timeline),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onDismissRequest) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.close),
                )
            }
        }

        when (uiState) {
            is LocationTimelineUiState.Error -> {
                QuickPeekErrorState(error = uiState.error, onRetry = onRetry)
            }

            is LocationTimelineUiState.Content -> {
                QuickPeekContentState(
                    uiState = uiState,
                    onSelectPlace = onSelectPlace,
                )
            }
        }

        FilledTonalButton(
            onClick = onOpenFullTimeline,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.open_full_location_timeline))
        }
    }
}

@Composable
private fun QuickPeekErrorState(
    error: LocationTimelineErrorUiState,
    onRetry: () -> Unit,
) {
    LocationTimelineErrorCard(
        error = error,
        onRetry = onRetry,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun QuickPeekContentState(
    uiState: LocationTimelineUiState.Content,
    onSelectPlace: (String) -> Unit,
) {
    val previewPlaces = uiState.visiblePlaces.take(3)
    val hasNoData = uiState.currentLocation == null && previewPlaces.isEmpty()

    if (hasNoData && !uiState.isLoadingPlaces) {
        Card {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(Res.string.no_location_history_yet),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.location_timeline_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (!hasNoData) {
        LocationTimelineMap(
            places = previewPlaces,
            currentLocation = uiState.currentLocation,
            selectedPlaceId = uiState.selectedPlace?.id,
            onSelectPlace = onSelectPlace,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp),
        )
    }

    uiState.currentLocation?.let { currentLocation ->
        QuickPeekCurrentLocationCard(currentLocation)
    }

    if (previewPlaces.isNotEmpty()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.recent_memories),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )

            previewPlaces.forEach { place ->
                QuickPeekPlaceCard(
                    place = place,
                    selected = place.id == uiState.selectedPlace?.id,
                    onClick = { onSelectPlace(place.id) },
                )
            }
        }
    }
}

@Composable
private fun QuickPeekCurrentLocationCard(currentLocation: CurrentLocationUiModel) {
    Card {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.current_location),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = currentLocation.title.asString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            currentLocation.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuickPeekPlaceCard(
    place: LocationPlaceUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        border =
            if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = place.title.asString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = place.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.memories_count, place.memoryCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
