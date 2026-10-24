package app.logdate.client.rewind

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.rewind.GenerateAnnualRewindUseCase
import app.logdate.client.domain.rewind.GenerateBasicRewindResult
import io.github.aakira.napier.Napier
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

/**
 * Background worker that produces the annual "Year in Review" Rewind once per year.
 *
 * Targets the year that just ended: when this worker runs in January 2027 it generates
 * the 2026 Year in Review. The underlying use case is idempotent — calling it twice in
 * the same year is a no-op after the first success.
 */
class AnnualRewindGenerationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    private val generateAnnualRewind: GenerateAnnualRewindUseCase by inject()
    private val preferences: LogdatePreferencesDataSource by inject()
    private val notificationCoordinator: RewindNotificationCoordinator by inject()

    override suspend fun doWork(): Result {
        Napier.d("AnnualRewindGenerationWorker: checking if annual rewind needs generation")

        if (!preferences.isRewindAutoGenerationEnabled()) {
            Napier.d("AnnualRewindGenerationWorker: auto-generation disabled by user, skipping")
            return Result.success()
        }

        val timezone = TimeZone.currentSystemDefault()
        val priorYear = Clock.System.todayIn(timezone).year - 1
        Napier.d("AnnualRewindGenerationWorker: targeting year $priorYear")

        return when (val annualResult = generateAnnualRewind(priorYear)) {
            is GenerateBasicRewindResult.Success -> {
                Napier.d("AnnualRewindGenerationWorker: generated annual rewind for $priorYear")
                if (preferences.isRewindNotificationsEnabled()) {
                    notificationCoordinator.postRewindReady(annualResult.rewind)
                }
                Result.success()
            }

            is GenerateBasicRewindResult.AlreadyInProgress -> {
                Napier.d("AnnualRewindGenerationWorker: generation already in progress")
                Result.success()
            }

            is GenerateBasicRewindResult.NoContent -> {
                Napier.d("AnnualRewindGenerationWorker: no annual content for $priorYear, skipping")
                Result.success()
            }

            is GenerateBasicRewindResult.Error -> {
                Napier.w("AnnualRewindGenerationWorker: generation failed — ${annualResult.error}")
                Result.retry()
            }
        }
    }

    companion object {
        const val WORK_NAME = "logdate:rewind:annual_generation"
    }
}
