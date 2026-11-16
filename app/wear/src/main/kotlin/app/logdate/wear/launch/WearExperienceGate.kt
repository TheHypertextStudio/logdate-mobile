package app.logdate.wear.launch

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The two watch apps the activity can show. */
enum class WearExperience {
    /** The watch app that ships today. */
    V1,

    /** The redesigned watch app, behind [FeatureFlag.WATCH_EXPERIENCE_V2]. */
    V2,
    ;

    companion object {
        /** The experience saved under [name], or null when nothing valid was saved. */
        fun fromName(name: String?): WearExperience? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Decides which watch app an activity shows. It is the only reader of
 * [FeatureFlag.WATCH_EXPERIENCE_V2] on the watch, and it reads the flag once per activity.
 */
class WearExperienceGate(
    private val featureFlags: FeatureFlagStore,
) {
    /**
     * Returns [restored] when a recreated activity saved its choice, so a configuration change never
     * swaps the app. Otherwise reads the flag. A read that fails or takes longer than
     * [FLAG_READ_TIMEOUT] opens [WearExperience.V1], so the splash screen always ends.
     */
    suspend fun resolve(restored: WearExperience? = null): WearExperience {
        if (restored != null) return restored
        return if (isNewWatchAppOn()) WearExperience.V2 else WearExperience.V1
    }

    private suspend fun isNewWatchAppOn(): Boolean {
        val enabled =
            try {
                withTimeoutOrNull(FLAG_READ_TIMEOUT) {
                    featureFlags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Couldn't read the watch experience flag; opening the current watch app", e)
                return false
            }
        if (enabled == null) {
            Napier.w("Reading the watch experience flag took over $FLAG_READ_TIMEOUT; opening the current watch app")
            return false
        }
        return enabled
    }

    companion object {
        val FLAG_READ_TIMEOUT: Duration = 2.seconds
    }
}

/** Resolves the presentation choice after storage and removal startup has succeeded. */
internal suspend fun resolveWearExperience(
    restored: WearExperience?,
    gate: () -> WearExperienceGate,
): WearExperience {
    if (restored != null) return restored
    return try {
        gate().resolve()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Napier.e("Could not construct the watch presentation preference; opening local legacy navigation", error)
        WearExperience.V1
    }
}
