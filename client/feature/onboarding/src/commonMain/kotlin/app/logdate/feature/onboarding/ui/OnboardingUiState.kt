package app.logdate.feature.onboarding.ui

import app.logdate.client.billing.model.LogDateBackupPlanOption

data class OnboardingUiState(
    val inviteData: InviteData? = null,
    val planOption: LogDateBackupPlanOption? = null,
    val billingSucceeded: Boolean = false,
)

data class InviteData(
    val inviteCode: String,
    val inviteMessage: String,
)
