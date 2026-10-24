package app.logdate.client.domain.watch

/** Background downloads only; tapping Play always remains available. */
enum class WatchAudioDownloadPolicy(
    val stored: String,
) {
    WIFI_OR_CHARGING("wifi_or_charging"),
    WIFI_ONLY("wifi_only"),
    CHARGING_ONLY("charging_only"),
    ON_DEMAND("on_demand"),
    ;

    fun permits(
        validatedUnmetered: Boolean,
        validatedConnected: Boolean,
        charging: Boolean,
    ): Boolean =
        when (this) {
            WIFI_OR_CHARGING -> validatedUnmetered || (charging && validatedConnected)
            WIFI_ONLY -> validatedUnmetered
            CHARGING_ONLY -> charging && validatedConnected
            ON_DEMAND -> false
        }

    companion object {
        fun fromStored(value: String): WatchAudioDownloadPolicy = entries.firstOrNull { it.stored == value } ?: WIFI_OR_CHARGING
    }
}
