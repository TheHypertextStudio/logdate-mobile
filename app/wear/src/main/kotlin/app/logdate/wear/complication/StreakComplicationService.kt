package app.logdate.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.streak.CalculateStreakUseCase
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
 * Complication showing the user's campfire, or their journaling streak with campfire streaks off.
 *
 * The campfire fills SHORT_TEXT, LONG_TEXT and RANGED_VALUE slots. The streak is a day count, with no
 * range to fill, so it fills SHORT_TEXT ("7d") and LONG_TEXT ("7 day streak") only.
 */
class StreakComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> {
                createShortText(
                    streak = 7,
                    contentDescription = getString(R.string.wear_complication_streak_description),
                )
            }

            ComplicationType.LONG_TEXT -> {
                createLongText(
                    streak = 7,
                    contentDescription = getString(R.string.wear_complication_streak_description),
                )
            }

            ComplicationType.RANGED_VALUE -> {
                campfireComplicationData(this, PREVIEW_CAMPFIRE, type, createOpenAppIntent())
            }

            else -> {
                null
            }
        }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (!isWearStartupReady()) return NoDataComplicationData()
        if (isCampfireEnabled()) {
            val campfire = calculateCampfire() ?: return NoDataComplicationData()
            return campfireComplicationData(
                context = this,
                content = campfire.toComplicationContent(),
                type = request.complicationType,
                tapAction = createOpenAppIntent(),
            )
        }
        if (request.complicationType == ComplicationType.RANGED_VALUE) return NoDataComplicationData()

        val streak = calculateStreak() ?: return NoDataComplicationData()
        val description =
            resources.getQuantityString(
                R.plurals.wear_complication_streak_full,
                streak,
                streak,
            )
        val tapIntent = createOpenAppIntent()
        return when (request.complicationType) {
            ComplicationType.LONG_TEXT -> createLongText(streak, description, tapIntent)
            else -> createShortText(streak, description, tapIntent)
        }
    }

    /**
     * The watch's Koin graph provides the data layer but not the domain use cases, so the
     * calculator is built here from the repository instead of being resolved.
     */
    private suspend fun calculateStreak(): Int? =
        try {
            val repository =
                org.koin.java.KoinJavaComponent
                    .getKoin()
                    .get<JournalNotesRepository>()
            CalculateStreakUseCase(repository)()
        } catch (e: Exception) {
            Napier.e("Failed to calculate streak for the Wear complication", e)
            null
        }

    private suspend fun isCampfireEnabled(): Boolean =
        try {
            org.koin.java.KoinJavaComponent
                .getKoin()
                .get<FeatureFlagStore>()
                .isEnabled(FeatureFlag.CAMPFIRE_STREAKS)
        } catch (e: Exception) {
            Napier.e("Failed to read the campfire streak flag for the Wear complication", e)
            false
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

    private fun createShortText(
        streak: Int,
        contentDescription: String,
        tapIntent: PendingIntent? = null,
    ): ShortTextComplicationData {
        val label = getString(R.string.wear_complication_streak_short, streak)
        val builder =
            ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(label).build(),
                contentDescription = PlainComplicationText.Builder(contentDescription).build(),
            )
        if (tapIntent != null) {
            builder.setTapAction(tapIntent)
        }
        return builder.build()
    }

    private fun createLongText(
        streak: Int,
        contentDescription: String,
        tapIntent: PendingIntent? = null,
    ): LongTextComplicationData {
        val label =
            resources.getQuantityString(
                R.plurals.wear_complication_streak_full,
                streak,
                streak,
            )
        val builder =
            LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(label).build(),
                contentDescription = PlainComplicationText.Builder(contentDescription).build(),
            )
        if (tapIntent != null) {
            builder.setTapAction(tapIntent)
        }
        return builder.build()
    }

    companion object {
        private const val STREAK_REQUEST_CODE = 1002
        private val PREVIEW_CAMPFIRE =
            CampfireComplicationContent(iconRes = R.drawable.ic_campfire_flame, runDays = 7, phase = FirePhase.BURNING)
    }
}
