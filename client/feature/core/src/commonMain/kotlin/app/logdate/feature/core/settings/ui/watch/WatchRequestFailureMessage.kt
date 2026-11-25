@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_discovery_unavailable
import logdate.client.feature.core.generated.resources.watch_request_failed_installed
import logdate.client.feature.core.generated.resources.watch_request_failed_no_response
import logdate.client.feature.core.generated.resources.watch_request_failed_no_watch
import logdate.client.feature.core.generated.resources.watch_request_failed_not_installed
import logdate.client.feature.core.generated.resources.watch_request_failed_open
import logdate.client.feature.core.generated.resources.watch_request_failed_unknown
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Says why the last request to the watch didn't go through; screen readers announce it as it appears. */
@Composable
internal fun WatchRequestFailureMessage(
    failure: WatchRequestFailure,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
) {
    Text(
        text = stringResource(failure.message),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private val WatchRequestFailure.message: StringResource
    get() =
        when (this) {
            WatchRequestFailure.LOOKUP_UNAVAILABLE -> Res.string.watch_discovery_unavailable
            WatchRequestFailure.ALREADY_INSTALLED -> Res.string.watch_request_failed_installed
            WatchRequestFailure.INSTALLATION_UNKNOWN -> Res.string.watch_request_failed_unknown
            WatchRequestFailure.OPEN_FAILED -> Res.string.watch_request_failed_open
            WatchRequestFailure.NO_WATCH_CONNECTED -> Res.string.watch_request_failed_no_watch
            WatchRequestFailure.NOT_INSTALLED_ON_WATCH -> Res.string.watch_request_failed_not_installed
            WatchRequestFailure.WATCH_DID_NOT_RESPOND -> Res.string.watch_request_failed_no_response
        }
