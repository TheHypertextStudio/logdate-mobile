@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.runtime.Composable
import app.logdate.client.location.tracking.LocationCaptureStatus
import app.logdate.client.text.UiText
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_access_default_action
import logdate.client.feature.location.timeline.generated.resources.history_access_default_message

internal data class HistoryRecordingAccess(
    val ready: Boolean = false,
    val message: UiText = UiText.Resource(Res.string.history_access_default_message),
    val actionLabel: UiText = UiText.Resource(Res.string.history_access_default_action),
    val reducedMotion: Boolean = true,
    val resolve: () -> Unit = {},
    val openSettings: (() -> Unit)? = null,
    val captureStatus: LocationCaptureStatus = LocationCaptureStatus.Stopped,
)

@Composable
internal expect fun rememberHistoryRecordingAccess(): HistoryRecordingAccess
