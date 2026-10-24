package app.logdate.client.domain.dayboundary

enum class HealthConnectGateKind {
    CHECKING,
    NEEDS_SETUP,
    NEEDS_PERMISSION,
    PERMISSION_DENIED,
    UNAVAILABLE,
    READY,
    RECOVERY_REQUIRED,
}

enum class HealthConnectMissingRequirement {
    SETUP,
    PERMISSION,
    UNAVAILABLE,
}

data class HealthConnectGateState(
    val kind: HealthConnectGateKind,
    val missingRequirement: HealthConnectMissingRequirement? = null,
)

fun reduceHealthConnectGateState(
    sleepBasedPreferenceEnabled: Boolean,
    healthConnectStatus: HealthConnectStatus,
    hasPermission: Boolean,
    permissionRequested: Boolean,
    previousResolvedGateState: HealthConnectGateState? = null,
): HealthConnectGateState =
    when (healthConnectStatus) {
        HealthConnectStatus.CONNECTED -> {
            HealthConnectGateState(HealthConnectGateKind.READY)
        }

        HealthConnectStatus.PERMISSIONS_NEEDED -> {
            when {
                hasPermission -> {
                    HealthConnectGateState(HealthConnectGateKind.READY)
                }

                sleepBasedPreferenceEnabled -> {
                    HealthConnectGateState(
                        kind = HealthConnectGateKind.RECOVERY_REQUIRED,
                        missingRequirement = HealthConnectMissingRequirement.PERMISSION,
                    )
                }

                permissionRequested -> {
                    HealthConnectGateState(
                        kind = HealthConnectGateKind.PERMISSION_DENIED,
                        missingRequirement = HealthConnectMissingRequirement.PERMISSION,
                    )
                }

                else -> {
                    HealthConnectGateState(
                        kind = HealthConnectGateKind.NEEDS_PERMISSION,
                        missingRequirement = HealthConnectMissingRequirement.PERMISSION,
                    )
                }
            }
        }

        HealthConnectStatus.PROVIDER_UPDATE_REQUIRED -> {
            if (sleepBasedPreferenceEnabled) {
                HealthConnectGateState(
                    kind = HealthConnectGateKind.RECOVERY_REQUIRED,
                    missingRequirement = HealthConnectMissingRequirement.SETUP,
                )
            } else {
                HealthConnectGateState(
                    kind = HealthConnectGateKind.NEEDS_SETUP,
                    missingRequirement = HealthConnectMissingRequirement.SETUP,
                )
            }
        }

        HealthConnectStatus.NOT_AVAILABLE -> {
            if (sleepBasedPreferenceEnabled) {
                HealthConnectGateState(
                    kind = HealthConnectGateKind.RECOVERY_REQUIRED,
                    missingRequirement = HealthConnectMissingRequirement.UNAVAILABLE,
                )
            } else {
                HealthConnectGateState(
                    kind = HealthConnectGateKind.UNAVAILABLE,
                    missingRequirement = HealthConnectMissingRequirement.UNAVAILABLE,
                )
            }
        }

        HealthConnectStatus.CHECKING -> {
            previousResolvedGateState ?: HealthConnectGateState(HealthConnectGateKind.CHECKING)
        }
    }
