package app.logdate.wear.presentation.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.wear.launch.WearLaunchRequest

/**
 * The screen the current watch app opens for this request. It has no screen for starting a
 * recording on launch, marking, a single moment or On this day, so those open Home, which is the
 * recorder.
 */
internal fun WearLaunchRequest?.toV1Route(): NavKey =
    when (this) {
        WearLaunchRequest.Memories -> WearVoiceMemoriesRoute

        WearLaunchRequest.MoodCheckIn -> WearMoodCheckInRoute

        WearLaunchRequest.QuickText -> WearQuickTextRoute

        WearLaunchRequest.Timeline -> WearTimelineRoute

        WearLaunchRequest.RemoteCamera -> WearRemoteCameraRoute

        WearLaunchRequest.Settings -> WearSettingsRoute

        null,
        WearLaunchRequest.Home,
        WearLaunchRequest.Record,
        WearLaunchRequest.Mark,
        WearLaunchRequest.OnThisDay,
        is WearLaunchRequest.Moment,
        -> WearHomeRoute
    }
