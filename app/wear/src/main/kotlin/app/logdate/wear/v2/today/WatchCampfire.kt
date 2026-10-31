package app.logdate.wear.v2.today

import app.logdate.client.health.util.LogdatePreferencesDataSource
import app.logdate.client.health.util.UserPreferences
import app.logdate.client.repository.streak.StreakSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The streak switch the shared campfire reads, on the watch. The watch always shows the campfire.
 * The streak setting itself belongs to the phone, so the watch never writes it and stores no
 * cached streak.
 */
class WatchStreakSettings : StreakSettingsRepository {
    override fun observeStreakEnabled(): Flow<Boolean> = flowOf(true)

    override suspend fun isStreakEnabled(): Boolean = true

    override suspend fun setStreakEnabled(enabled: Boolean): Unit = throw UnsupportedOperationException(PHONE_OWNED)

    override fun observeCachedStreak(): Flow<Int> = throw UnsupportedOperationException(PHONE_OWNED)

    override suspend fun getCachedStreak(): Int = throw UnsupportedOperationException(PHONE_OWNED)

    override suspend fun setCachedStreak(value: Int): Unit = throw UnsupportedOperationException(PHONE_OWNED)

    private companion object {
        const val PHONE_OWNED = "The streak setting belongs to the phone"
    }
}

/**
 * The day-start hour the shared campfire reads, on the watch: the phone's, from [phoneDayStartHour].
 * Until the phone shares it there is none, and the campfire's 4 AM default applies.
 */
class WatchDayBoundaryPreferences(
    private val phoneDayStartHour: suspend () -> Int?,
) : LogdatePreferencesDataSource {
    override suspend fun getPreferences(): UserPreferences = UserPreferences(dayStartHour = phoneDayStartHour())
}
