package app.logdate.wear.v2.navigation

import app.logdate.wear.launch.WearLaunchRequest

/**
 * The back stack the new watch app opens for this request: the screen the request names above
 * Home, so going back lands on Today. A request whose screen does not exist yet opens Today.
 */
internal fun WearLaunchRequest?.toV2BackStack(): List<WearRoute> =
    when (this) {
        null,
        WearLaunchRequest.Home,
        WearLaunchRequest.Record,
        WearLaunchRequest.MoodCheckIn,
        WearLaunchRequest.QuickText,
        WearLaunchRequest.RemoteCamera,
        WearLaunchRequest.Mark,
        -> listOf(WearRoute.Home)

        WearLaunchRequest.Timeline -> listOf(WearRoute.Home, WearRoute.Flow)

        WearLaunchRequest.Memories,
        WearLaunchRequest.OnThisDay,
        -> listOf(WearRoute.Home, WearRoute.Memories)

        WearLaunchRequest.Settings -> listOf(WearRoute.Home, WearRoute.Settings)

        is WearLaunchRequest.Moment -> listOf(WearRoute.Home, WearRoute.Moment(noteId.toString()))
    }
