package app.logdate.client.billing

import app.logdate.client.billing.model.LogDateBackupPlanOption

/**
 * Interface for handling LogDate subscriptions and in-app billing.
 */
interface SubscriptionBiller {
    /**
     * Purchase a backup plan.
     */
    suspend fun purchasePlan(plan: LogDateBackupPlanOption)

    /**
     * Cancels the currently subscribed subscription plan.
     *
     * If there is no subscription plan, this is a no-op.
     */
    suspend fun cancelPlan()
}
