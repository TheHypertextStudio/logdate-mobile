package app.logdate.feature.core.settings.updates

/**
 * Input used to choose whether Google Play should surface a flexible or immediate update.
 */
data class AppUpdatePromptContext(
    /** Play Console priority for the available release. */
    val updatePriority: Int,
    /** Days the installed version has been stale, or `null` when Play omits it. */
    val stalenessDays: Int?,
    /** Whether Play allows the immediate flow for this update on this device. */
    val immediateAllowed: Boolean,
    /** Whether Play allows the flexible flow for this update on this device. */
    val flexibleAllowed: Boolean,
    /** Whether LogDate is still suppressing automatic flexible prompts for this version. */
    val isFlexiblePromptDeferred: Boolean,
)

/**
 * Hybrid product policy for LogDate's Android in-app updates.
 *
 * Immediate updates are reserved for severe or stale releases. Flexible updates are
 * used for less urgent releases, and automatic flexible prompts are suppressed for a
 * short period after dismissal to avoid nagging users on every app open.
 */
object AppUpdatePromptPolicy {
    /** Priority at or above which updates should interrupt the user immediately. */
    private const val IMMEDIATE_PRIORITY_THRESHOLD = 4

    /** Staleness at or above which updates should interrupt the user immediately. */
    private const val IMMEDIATE_STALENESS_THRESHOLD_DAYS = 7

    /** Priority at or above which automatic checks may offer a flexible update. */
    private const val FLEXIBLE_PRIORITY_THRESHOLD = 2

    /** Staleness at or above which automatic checks may offer a flexible update. */
    private const val FLEXIBLE_STALENESS_THRESHOLD_DAYS = 2

    /**
     * Chooses the update flow that should be launched for the given release context.
     *
     * Returns `null` when the release should not prompt the user right now.
     */
    fun chooseFlow(
        context: AppUpdatePromptContext,
        trigger: AppUpdateCheckTrigger,
    ): AppUpdateFlowType? {
        val stalenessDays = context.stalenessDays ?: 0

        if (context.immediateAllowed &&
            (
                context.updatePriority >= IMMEDIATE_PRIORITY_THRESHOLD ||
                    stalenessDays >= IMMEDIATE_STALENESS_THRESHOLD_DAYS
            )
        ) {
            return AppUpdateFlowType.Immediate
        }

        if (!context.flexibleAllowed) {
            return null
        }

        return when (trigger) {
            AppUpdateCheckTrigger.Manual -> {
                AppUpdateFlowType.Flexible
            }

            AppUpdateCheckTrigger.Automatic -> {
                if (!context.isFlexiblePromptDeferred &&
                    (
                        context.updatePriority >= FLEXIBLE_PRIORITY_THRESHOLD ||
                            stalenessDays >= FLEXIBLE_STALENESS_THRESHOLD_DAYS
                    )
                ) {
                    AppUpdateFlowType.Flexible
                } else {
                    null
                }
            }
        }
    }
}
