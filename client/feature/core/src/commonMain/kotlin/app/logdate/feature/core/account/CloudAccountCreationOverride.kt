package app.logdate.feature.core.account

import app.logdate.client.repository.account.AccountCreationRequest
import org.jetbrains.compose.resources.StringResource

/** Alternate verified identity context; the account setup steps and presentation stay shared. */
class CloudAccountCreationOverride(
    val busy: Boolean,
    val error: StringResource?,
    val detailsEditable: Boolean,
    val create: (AccountCreationRequest) -> Unit,
    val clearError: () -> Unit,
)
