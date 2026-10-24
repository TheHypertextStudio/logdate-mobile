package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.domain.places.PlaceResolutionResult
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_change_not_saved
import logdate.client.feature.location.timeline.generated.resources.history_conflicts_message
import logdate.client.feature.location.timeline.generated.resources.history_recording_off
import logdate.client.feature.location.timeline.generated.resources.history_recording_paused
import logdate.client.feature.location.timeline.generated.resources.history_sections_failed
import logdate.client.feature.location.timeline.generated.resources.places_near
import logdate.client.feature.location.timeline.generated.resources.places_somewhere_in
import org.jetbrains.compose.resources.getString

internal fun snapshotRecoveryMessage(
    data: LocationHistorySnapshot,
    tracking: LocationTrackingSettings,
    hasFailedAction: Boolean,
): UiText? =
    when {
        hasFailedAction -> {
            Res.string.history_change_not_saved.toUiText()
        }

        data.failedSections.isNotEmpty() -> {
            Res.string.history_sections_failed.toUiText()
        }

        data.conflicts.isNotEmpty() -> {
            Res.string.history_conflicts_message.toUiText()
        }

        !tracking.backgroundTrackingEnabled && tracking.captureMode == LocationCaptureMode.PASSIVE -> {
            Res.string.history_recording_off.toUiText()
        }

        !tracking.backgroundTrackingEnabled -> {
            Res.string.history_recording_paused.toUiText()
        }

        else -> {
            null
        }
    }

internal suspend fun resolvedHistoryPlaceName(result: PlaceResolutionResult): String? =
    when (result) {
        is PlaceResolutionResult.UserDefinedPlace -> {
            result.place.name
        }

        is PlaceResolutionResult.ExternalSuggestion -> {
            getString(Res.string.places_near, result.suggestion.name)
        }

        is PlaceResolutionResult.CoarseLocation -> {
            result.address.thoroughfare?.let { getString(Res.string.places_near, it) }
                ?: result.address.locality?.let { getString(Res.string.places_somewhere_in, it) }
        }

        else -> {
            null
        }
    }
