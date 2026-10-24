package app.logdate.feature.core

import androidx.biometric.BiometricManager

internal sealed interface AndroidAuthenticationAvailability {
    data class Ready(
        val authenticators: Int,
    ) : AndroidAuthenticationAvailability

    data class Unavailable(
        val state: AppAuthState,
    ) : AndroidAuthenticationAvailability
}

internal fun resolveAndroidAuthenticationAvailability(canAuthenticate: (Int) -> Int): AndroidAuthenticationAvailability {
    val credential = BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val combined = BiometricManager.Authenticators.BIOMETRIC_STRONG or credential
    val combinedResult = canAuthenticate(combined)
    if (combinedResult == BiometricManager.BIOMETRIC_SUCCESS) {
        return AndroidAuthenticationAvailability.Ready(combined)
    }
    val credentialResult = canAuthenticate(credential)
    if (credentialResult == BiometricManager.BIOMETRIC_SUCCESS) {
        return AndroidAuthenticationAvailability.Ready(credential)
    }
    val state =
        when {
            credentialResult == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
                combinedResult == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                AppAuthState.REQUEST_ENROLLMENT
            }

            combinedResult == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                AppAuthState.UPDATE_REQUIRED
            }

            else -> {
                when (combinedResult) {
                    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
                    -> AppAuthState.UNSUPPORTED

                    else -> AppAuthState.UNKNOWN
                }
            }
        }
    return AndroidAuthenticationAvailability.Unavailable(state)
}
