package app.logdate.wear.v2.moments

object MomentTags {
    const val SCREEN = "moment_screen"
    const val LOADING = "moment_loading"
    const val PLAYER = "moment_player"
    const val POSITION = "moment_position"
    const val PLAY_PAUSE = "moment_play_pause"
    const val SPEED = "moment_speed"
    const val SKIP_SILENCE = "moment_skip_silence"
    const val CAPTION = "moment_caption"
    const val DETAILS = "moment_details"
    const val DELETE = "moment_delete"
    const val CONFIRM_DELETE = "moment_confirm_delete"
    const val MISSING = "moment_missing"
    const val PLAY_ON = "moment_play_on"
    const val CHOOSE_OUTPUT = "moment_choose_output"
    const val PLAY_ON_WATCH = "moment_play_on_watch"
    const val PLAY_ON_PHONE = "moment_play_on_phone"
}

/** What the person can do on a moment's screen. Delete and Play on appear only with their handlers. */
data class MomentActions(
    val onPlayPause: () -> Unit = {},
    val onCrown: (Float) -> Unit = {},
    val onCycleSpeed: () -> Unit = {},
    val onSkipSilenceChange: (Boolean) -> Unit = {},
    val onDelete: (() -> Unit)? = null,
    val onPlayOn: (() -> Unit)? = null,
    val onPlayOnPhone: (() -> Unit)? = null,
    val onOpenOnPhone: (() -> Unit)? = null,
    val phoneHandoffMessage: String? = null,
)
