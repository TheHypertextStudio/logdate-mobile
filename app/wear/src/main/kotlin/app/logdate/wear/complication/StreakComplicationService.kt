package app.logdate.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.logdate.client.domain.streak.CampfireCalculator
import app.logdate.client.domain.streak.CampfireState
import app.logdate.client.domain.streak.FirePhase
import app.logdate.client.domain.streak.toStreakDay
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.R
import app.logdate.wear.launch.isWearStartupReady
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Complication showing the user's campfire in SHORT_TEXT, LONG_TEXT and RANGED_VALUE slots.
 */
class StreakComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT, ComplicationType.LONG_TEXT, ComplicationType.RANGED_VALUE -> {
                campfireComplicationData(this, PREVIEW_CAMPFIRE, type, createOpenAppIntent())
            }

            else -> {
                null
            }
        }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (!isWearStartupReady()) return NoDataComplicationData()
        val campfire = calculateCampfire() ?: return NoDataComplicationData()
        return campfireComplicationData(
            context = this,
            content = campfire.toComplicationContent(),
            type = request.complicationType,
            tapAction = createOpenAppIntent(),
        )
    }

    /**
     * Uses the default 4 AM day start: the phone's day-start preference is not synced to the watch.
     */
    private suspend fun calculateCampfire(): CampfireState? =
        try {
            val repository =
                org.koin.java.KoinJavaComponent
                    .getKoin()
                    .get<JournalNotesRepository>()
            val zone = TimeZone.currentSystemDefault()
            val loggedDays = repository.observeEntryTimestamps().first().mapTo(HashSet()) { it.toStreakDay(zone) }
            CampfireCalculator.calculate(loggedDays = loggedDays, today = Clock.System.now().toStreakDay(zone))
        } catch (e: Exception) {
            Napier.e("Failed to calculate the campfire for the Wear complication", e)
            null
        }

    private fun createOpenAppIntent(): PendingIntent {
        val intent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent()
        return PendingIntent.getActivity(
            this,
            STREAK_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val STREAK_REQUEST_CODE = 1002
        private val PREVIEW_CAMPFIRE =
            CampfireComplicationContent(iconRes = R.drawable.ic_campfire_flame, runDays = 7, phase = FirePhase.BURNING)
    }
}
