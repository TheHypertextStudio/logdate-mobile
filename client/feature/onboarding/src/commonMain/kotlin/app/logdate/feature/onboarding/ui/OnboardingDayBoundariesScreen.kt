@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
)

package app.logdate.feature.onboarding.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.domain.dayboundary.HealthConnectGateKind
import app.logdate.client.domain.dayboundary.HealthConnectGateState
import app.logdate.client.domain.dayboundary.HealthConnectMissingRequirement
import app.logdate.client.domain.dayboundary.HealthConnectStatus
import app.logdate.client.domain.dayboundary.reduceHealthConnectGateState
import app.logdate.client.permissions.rememberHealthConnectPermissionState
import app.logdate.ui.step.StepBusyButton
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.LogDateTheme
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

const val ONBOARDING_DAY_BOUNDARIES_ROOT_TAG = "onboarding_day_boundaries_root"
const val ONBOARDING_DAY_BOUNDARIES_ENABLE_TAG = "onboarding_day_boundaries_enable"
const val ONBOARDING_DAY_BOUNDARIES_SKIP_TAG = "onboarding_day_boundaries_skip"

@Composable
fun OnboardingDayBoundariesScreen(
    onBack: () -> Unit,
    onNext: (Boolean) -> Unit,
    onSetUpHealthConnect: () -> Unit = {},
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val progressSnapshot by viewModel.progressSnapshot.collectAsState()
    val healthConnectStatus = progressSnapshot.healthConnectStatus
    val permissionState = rememberHealthConnectPermissionState()
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingEnableAfterPermission by rememberSaveable { mutableStateOf(false) }
    var previousResolvedGateState by remember { mutableStateOf<HealthConnectGateState?>(null) }
    val gateState =
        reduceHealthConnectGateState(
            sleepBasedPreferenceEnabled = progressSnapshot.sleepBasedDayBoundariesEnabled,
            healthConnectStatus = healthConnectStatus,
            hasPermission = permissionState.hasPermission,
            permissionRequested = permissionState.permissionRequested,
            previousResolvedGateState = previousResolvedGateState,
        )

    fun persistAndContinue(enabled: Boolean) {
        coroutineScope.launch {
            isSaving = true
            errorMessage = null
            viewModel
                .persistSleepBasedDayBoundariesEnabled(enabled = enabled)
                .onSuccess {
                    viewModel
                        .markDayBoundariesHandled()
                        .onSuccess {
                            isSaving = false
                            onNext(enabled)
                        }.onFailure {
                            errorMessage = getString(Res.string.onboarding_error_save_day_boundaries)
                            isSaving = false
                        }
                }.onFailure {
                    errorMessage = getString(Res.string.onboarding_error_save_day_boundaries)
                    isSaving = false
                }
        }
    }

    LaunchedEffect(gateState) {
        if (gateState.kind != HealthConnectGateKind.CHECKING) {
            previousResolvedGateState = gateState
        }
        Napier.i(
            "Onboarding day boundaries gate state: kind=${gateState.kind} requirement=${gateState.missingRequirement}",
        )
    }

    LaunchedEffect(healthConnectStatus) {
        permissionState.refreshPermissionState()
    }

    LaunchedEffect(permissionState.hasPermission, healthConnectStatus) {
        val backendThinksConnected = healthConnectStatus == HealthConnectStatus.CONNECTED
        if (permissionState.hasPermission != backendThinksConnected) {
            viewModel.refreshHealthStatus()
        }
    }

    LaunchedEffect(healthConnectStatus) {
        if (healthConnectStatus == HealthConnectStatus.NOT_AVAILABLE) {
            persistAndContinue(enabled = false)
        }
    }

    LaunchedEffect(
        pendingEnableAfterPermission,
        permissionState.hasPermission,
        permissionState.permissionRequested,
        permissionState.isRequestInFlight,
        gateState,
    ) {
        when (
            resolveDayBoundariesPostPermissionAction(
                pendingEnableAfterPermission = pendingEnableAfterPermission,
                hasPermission = permissionState.hasPermission,
                permissionRequested = permissionState.permissionRequested,
                isRequestInFlight = permissionState.isRequestInFlight,
                gateState = gateState,
            )
        ) {
            DayBoundariesPostPermissionAction.ENABLE_AND_CONTINUE -> {
                pendingEnableAfterPermission = false
                persistAndContinue(enabled = true)
            }

            DayBoundariesPostPermissionAction.RESET_REQUEST_STATE -> {
                pendingEnableAfterPermission = false
            }

            DayBoundariesPostPermissionAction.NONE -> {
                Unit
            }
        }
    }

    OnboardingDayBoundariesContent(
        gateState = gateState,
        onBack = onBack,
        onEnable = {
            when {
                gateState.kind == HealthConnectGateKind.READY -> {
                    persistAndContinue(enabled = true)
                }

                gateState.missingRequirement == HealthConnectMissingRequirement.PERMISSION -> {
                    pendingEnableAfterPermission = true
                    permissionState.requestPermission()
                }

                gateState.missingRequirement == HealthConnectMissingRequirement.SETUP -> {
                    onSetUpHealthConnect()
                }

                else -> {
                    Unit
                }
            }
        },
        onSkip = {
            pendingEnableAfterPermission = false
            persistAndContinue(enabled = false)
        },
        isSaving = isSaving,
        isRequestInFlight = permissionState.isRequestInFlight,
        errorMessage = errorMessage,
    )
}

internal enum class DayBoundariesPostPermissionAction {
    ENABLE_AND_CONTINUE,
    RESET_REQUEST_STATE,
    NONE,
}

internal fun resolveDayBoundariesPostPermissionAction(
    pendingEnableAfterPermission: Boolean,
    hasPermission: Boolean,
    permissionRequested: Boolean,
    isRequestInFlight: Boolean,
    gateState: HealthConnectGateState,
): DayBoundariesPostPermissionAction {
    if (!pendingEnableAfterPermission) {
        return DayBoundariesPostPermissionAction.NONE
    }

    return when {
        hasPermission && gateState.kind == HealthConnectGateKind.READY -> {
            DayBoundariesPostPermissionAction.ENABLE_AND_CONTINUE
        }

        permissionRequested && !hasPermission && !isRequestInFlight -> {
            DayBoundariesPostPermissionAction.RESET_REQUEST_STATE
        }

        else -> {
            DayBoundariesPostPermissionAction.NONE
        }
    }
}

@Composable
fun OnboardingDayBoundariesContent(
    gateState: HealthConnectGateState,
    onBack: () -> Unit,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    isSaving: Boolean = false,
    isRequestInFlight: Boolean = false,
    errorMessage: String? = null,
) {
    val status = dayBoundariesStatus(gateState)

    StepScaffold(
        title = stringResource(Res.string.onboarding_day_boundaries_title),
        onBack = onBack,
        modifier = Modifier.testTag(ONBOARDING_DAY_BOUNDARIES_ROOT_TAG),
        supportingText = stringResource(Res.string.onboarding_day_boundaries_body),
        hero = { StepHeroIcon(Icons.Rounded.Bedtime) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            StepBusyButton(
                text = stringResource(status.actionLabel),
                onClick = onEnable,
                busy = isSaving || isRequestInFlight,
                enabled = gateState.kind != HealthConnectGateKind.CHECKING,
                modifier = Modifier.testTag(ONBOARDING_DAY_BOUNDARIES_ENABLE_TAG),
            )
            TextButton(
                onClick = onSkip,
                enabled = !isSaving,
                modifier = Modifier.testTag(ONBOARDING_DAY_BOUNDARIES_SKIP_TAG),
            ) {
                Text(stringResource(Res.string.onboarding_day_boundaries_not_now))
            }
            OnboardingActionError(errorMessage)
        },
    ) {
        OverviewItem(
            title = stringResource(Res.string.onboarding_day_boundaries_card_title),
            description = stringResource(Res.string.onboarding_day_boundaries_card_description),
            icon = { Icon(Icons.Rounded.WbTwilight, contentDescription = null) },
        )
        OnboardingNote(
            icon = status.icon,
            title = stringResource(status.title),
            body = stringResource(status.description),
        )
        PrivacyNote(body = stringResource(Res.string.onboarding_day_boundaries_privacy))
    }
}

private class DayBoundariesStatus(
    val icon: ImageVector,
    val title: StringResource,
    val description: StringResource,
    val actionLabel: StringResource,
)

private fun dayBoundariesStatus(gateState: HealthConnectGateState): DayBoundariesStatus =
    when {
        gateState.kind == HealthConnectGateKind.READY -> {
            DayBoundariesStatus(
                icon = Icons.Rounded.CheckCircle,
                title = Res.string.onboarding_day_boundaries_status_connected_title,
                description = Res.string.onboarding_day_boundaries_status_connected_description,
                actionLabel = Res.string.onboarding_day_boundaries_enable,
            )
        }

        gateState.missingRequirement == HealthConnectMissingRequirement.PERMISSION -> {
            DayBoundariesStatus(
                icon = Icons.Rounded.VpnKey,
                title = Res.string.onboarding_day_boundaries_status_permissions_title,
                description = Res.string.onboarding_day_boundaries_status_permissions_description,
                actionLabel = Res.string.onboarding_day_boundaries_grant_access,
            )
        }

        gateState.missingRequirement == HealthConnectMissingRequirement.SETUP -> {
            DayBoundariesStatus(
                icon = Icons.Rounded.Download,
                title = Res.string.onboarding_day_boundaries_status_setup_required_title,
                description = Res.string.onboarding_day_boundaries_status_setup_required_description,
                actionLabel = Res.string.onboarding_day_boundaries_set_up_health_connect,
            )
        }

        else -> {
            DayBoundariesStatus(
                icon = Icons.Rounded.Sync,
                title = Res.string.onboarding_day_boundaries_status_checking_title,
                description = Res.string.onboarding_day_boundaries_status_checking_description,
                actionLabel = Res.string.onboarding_day_boundaries_status_checking_title,
            )
        }
    }

@Preview
@Composable
private fun OnboardingDayBoundariesScreenPreview_PermissionsNeeded() {
    LogDateTheme {
        OnboardingDayBoundariesContent(
            gateState =
                HealthConnectGateState(
                    kind = HealthConnectGateKind.NEEDS_PERMISSION,
                    missingRequirement = HealthConnectMissingRequirement.PERMISSION,
                ),
            onBack = {},
            onEnable = {},
            onSkip = {},
        )
    }
}

@Preview
@Composable
private fun OnboardingDayBoundariesScreenPreview_Connected() {
    LogDateTheme {
        OnboardingDayBoundariesContent(
            gateState =
                HealthConnectGateState(
                    kind = HealthConnectGateKind.READY,
                ),
            onBack = {},
            onEnable = {},
            onSkip = {},
        )
    }
}
