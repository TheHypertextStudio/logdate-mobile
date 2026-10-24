package app.logdate.client.domain.dayboundary

import app.logdate.client.health.HealthDataAvailability
import app.logdate.client.health.LocalFirstHealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Health Connect connection status from the user's perspective.
 */
enum class HealthConnectStatus {
    /** Health Connect is available and sleep permissions are granted. */
    CONNECTED,

    /** Health Connect is available but sleep permissions have not been granted. */
    PERMISSIONS_NEEDED,

    /** Health Connect can work here, but the provider app must be installed or updated first. */
    PROVIDER_UPDATE_REQUIRED,

    /** Health Connect is not available on this device. */
    NOT_AVAILABLE,

    /** Status is still being determined. */
    CHECKING,
}

/**
 * Checks whether Health Connect is available and sleep permissions are granted.
 */
class ObserveHealthConnectStatusUseCase(
    private val healthRepository: LocalFirstHealthRepository,
) {
    operator fun invoke(): Flow<HealthConnectStatus> =
        flow {
            emit(HealthConnectStatus.CHECKING)

            when (healthRepository.getHealthDataAvailability()) {
                HealthDataAvailability.NOT_AVAILABLE -> {
                    emit(HealthConnectStatus.NOT_AVAILABLE)
                    return@flow
                }

                HealthDataAvailability.PROVIDER_UPDATE_REQUIRED -> {
                    emit(HealthConnectStatus.PROVIDER_UPDATE_REQUIRED)
                    return@flow
                }

                HealthDataAvailability.AVAILABLE -> {
                    Unit
                }
            }

            val hasPermissions = healthRepository.hasSleepPermissions()
            if (hasPermissions) {
                emit(HealthConnectStatus.CONNECTED)
            } else {
                emit(HealthConnectStatus.PERMISSIONS_NEEDED)
            }
        }
}
