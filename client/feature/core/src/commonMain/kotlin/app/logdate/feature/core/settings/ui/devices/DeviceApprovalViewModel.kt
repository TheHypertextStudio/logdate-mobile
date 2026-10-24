package app.logdate.feature.core.settings.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.DeviceTransferContents
import app.logdate.client.device.crypto.DeviceTransferSealer
import app.logdate.client.device.crypto.DeviceTransferSession
import app.logdate.client.networking.DeviceEnrollmentApiClientContract
import app.logdate.client.networking.DeviceEnrollmentApiException
import app.logdate.client.networking.DeviceEnrollmentRequest
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Why connecting a device stopped. Each reason has its own message telling the person what to do. */
enum class DeviceApprovalFailure {
    NotAConnectionCode,
    ScanFailed,
    CameraDenied,
    SignedOut,
    AccountMismatch,
    WrongAccountOrExpired,
    Expired,
    AlreadyUsed,
    KeysMissing,
    ConnectionFailed,
    ServerError,
}

sealed interface DeviceApprovalUiState {
    data object Idle : DeviceApprovalUiState

    data object LookingUp : DeviceApprovalUiState

    /** Waiting for the person to compare [code] with the new device; [failure] is a retryable error. */
    data class Confirm(
        val deviceName: String,
        val accountName: String,
        val code: String,
        val failure: DeviceApprovalFailure? = null,
    ) : DeviceApprovalUiState

    data class Working(
        val deviceName: String,
        val connecting: Boolean,
    ) : DeviceApprovalUiState

    data class Done(
        val deviceName: String,
        val connected: Boolean,
    ) : DeviceApprovalUiState

    data class Failed(
        val reason: DeviceApprovalFailure,
    ) : DeviceApprovalUiState
}

/**
 * Approves a new device from this signed-in phone.
 *
 * Two connection codes exist. A signed-in device shows `logdate-device-enrollment:v2:<payload>` for a request
 * it already created. A signed-out device shows `logdate-device-connect:<payload>` with its one-time
 * key; this phone creates the request for it and, on approval, mints a separate session for the new
 * device so it signs in without sharing this phone's tokens.
 *
 * A retried approval resends the same envelope, so a session minted for the new device is never
 * minted twice.
 */
class DeviceApprovalViewModel(
    private val enrollmentApi: DeviceEnrollmentApiClientContract,
    private val sessionStorage: SessionStorage,
    private val access: DeviceApprovalAccess,
    private val sealer: DeviceTransferSealer,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
    private class PendingApproval(
        val request: DeviceEnrollmentRequest,
        val accountName: String,
        val createdByPhone: Boolean,
    )

    private class ApprovalFailure(
        val reason: DeviceApprovalFailure,
    ) : Exception(reason.name)

    private val _uiState = MutableStateFlow<DeviceApprovalUiState>(DeviceApprovalUiState.Idle)
    val uiState: StateFlow<DeviceApprovalUiState> = _uiState.asStateFlow()

    private val envelopeCache = DeviceApprovalEnvelopeCache()
    private var pending: PendingApproval? = null

    fun onCodeScanned(value: String?) {
        val code = DeviceApprovalCode.parse(value)
        if (code == null) {
            _uiState.value = DeviceApprovalUiState.Failed(DeviceApprovalFailure.NotAConnectionCode)
            return
        }
        _uiState.value = DeviceApprovalUiState.LookingUp
        viewModelScope.launch {
            _uiState.value =
                try {
                    val approval = lookUp(code)
                    pending = approval
                    DeviceApprovalUiState.Confirm(
                        deviceName = approval.request.deviceName,
                        accountName = approval.accountName,
                        code = approval.request.confirmationCode,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    DeviceApprovalUiState.Failed(classify(failure, Stage.LookUp))
                }
        }
    }

    fun onScanFailed(reason: DeviceApprovalFailure) {
        _uiState.value = DeviceApprovalUiState.Failed(reason)
    }

    fun approve() {
        val approval = pending ?: return
        _uiState.value = DeviceApprovalUiState.Working(approval.request.deviceName, connecting = true)
        viewModelScope.launch {
            _uiState.value =
                try {
                    val session = requireSession()
                    val envelope = envelopeCache.envelopeFor(approval.request.id) { seal(approval, session) }
                    enrollmentApi.approve(approval.request.id, approval.request.confirmationCode, envelope, session.accessToken)
                    finish(approval, connected = true)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    failedState(approval, classify(failure, Stage.Approve))
                }
        }
    }

    fun reject() {
        val approval = pending ?: return
        _uiState.value = DeviceApprovalUiState.Working(approval.request.deviceName, connecting = false)
        viewModelScope.launch {
            _uiState.value =
                try {
                    enrollmentApi.reject(approval.request.id, requireSession().accessToken)
                    finish(approval, connected = false)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    failedState(approval, classify(failure, Stage.Reject))
                }
        }
    }

    /** Closes the confirmation or clears the last result. A cached envelope stays for a retried scan. */
    fun dismiss() {
        if (_uiState.value is DeviceApprovalUiState.Working) return
        pending = null
        _uiState.value = DeviceApprovalUiState.Idle
    }

    private suspend fun lookUp(code: DeviceApprovalCode): PendingApproval {
        val session = requireSession()
        val account = access.account().getOrThrow()
        if (!account.id.equals(session.accountId, ignoreCase = true)) throw ApprovalFailure(DeviceApprovalFailure.AccountMismatch)
        val request =
            when (code) {
                is DeviceApprovalCode.ExistingRequest -> {
                    enrollmentApi.get(code.code.id, session.accessToken)
                }

                is DeviceApprovalCode.NewDevice -> {
                    enrollmentApi.createFromPhone(
                        deviceName = code.deviceName,
                        publicKey = code.publicKey,
                        claimSecret = code.claimSecret,
                        confirmationCode = code.confirmationCode,
                        accessToken = session.accessToken,
                    )
                }
            }
        if (request.status != PENDING_STATUS || request.expiresAt <= now()) throw ApprovalFailure(DeviceApprovalFailure.Expired)
        if (code is DeviceApprovalCode.ExistingRequest) {
            try {
                code.code.verify(request, session.accountId)
            } catch (_: IllegalArgumentException) {
                throw ApprovalFailure(DeviceApprovalFailure.ServerError)
            }
        }
        if (code is DeviceApprovalCode.NewDevice &&
            (request.publicKey != code.publicKey || request.confirmationCode != code.confirmationCode)
        ) {
            throw ApprovalFailure(DeviceApprovalFailure.ServerError)
        }
        return PendingApproval(request, account.displayName, createdByPhone = code is DeviceApprovalCode.NewDevice)
    }

    private suspend fun seal(
        approval: PendingApproval,
        session: UserSession,
    ): String {
        val identityKey = access.identityKey() ?: throw ApprovalFailure(DeviceApprovalFailure.KeysMissing)
        val newDeviceSession =
            if (approval.createdByPhone) {
                val tokens = enrollmentApi.createDeviceSession(approval.request.id, session.accessToken).getOrThrow()
                DeviceTransferSession(approval.accountName, tokens.accessToken, tokens.refreshToken)
            } else {
                null
            }
        return sealer.seal(
            DeviceTransferContents(
                recipientPublicKey = approval.request.publicKey,
                identityKey = identityKey,
                legacyMediaKey = access.legacyMediaKey(),
                accountId = session.accountId,
                requestId = Uuid.parse(approval.request.id),
                confirmationCode = approval.request.confirmationCode,
                session = newDeviceSession,
            ),
        )
    }

    private suspend fun requireSession(): UserSession {
        if (!sessionStorage.hasValidSession()) throw ApprovalFailure(DeviceApprovalFailure.SignedOut)
        return sessionStorage.getSession() ?: throw ApprovalFailure(DeviceApprovalFailure.SignedOut)
    }

    private fun finish(
        approval: PendingApproval,
        connected: Boolean,
    ): DeviceApprovalUiState {
        envelopeCache.clear()
        pending = null
        return DeviceApprovalUiState.Done(approval.request.deviceName, connected)
    }

    private fun failedState(
        approval: PendingApproval,
        reason: DeviceApprovalFailure,
    ): DeviceApprovalUiState {
        if (reason in RETRYABLE) {
            return DeviceApprovalUiState.Confirm(
                deviceName = approval.request.deviceName,
                accountName = approval.accountName,
                code = approval.request.confirmationCode,
                failure = reason,
            )
        }
        envelopeCache.clear()
        pending = null
        return DeviceApprovalUiState.Failed(reason)
    }

    private enum class Stage { LookUp, Approve, Reject }

    private fun classify(
        failure: Exception,
        stage: Stage,
    ): DeviceApprovalFailure {
        val reason =
            when (failure) {
                is ApprovalFailure -> failure.reason
                is DeviceEnrollmentApiException -> classifyResponse(failure, stage)
                else -> DeviceApprovalFailure.ConnectionFailed
            }
        Napier.w("Device approval stopped during ${stage.name}: $reason (${failure::class.simpleName})")
        return reason
    }

    private fun classifyResponse(
        failure: DeviceEnrollmentApiException,
        stage: Stage,
    ): DeviceApprovalFailure =
        when {
            failure.status == HTTP_UNAUTHORIZED -> DeviceApprovalFailure.SignedOut
            failure.code == DeviceEnrollmentApiException.SESSION_ALREADY_ISSUED -> DeviceApprovalFailure.AlreadyUsed
            failure.status == HTTP_BAD_REQUEST && stage == Stage.LookUp -> DeviceApprovalFailure.NotAConnectionCode
            failure.status == HTTP_NOT_FOUND && stage == Stage.LookUp -> DeviceApprovalFailure.WrongAccountOrExpired
            failure.status == HTTP_CONFLICT && stage == Stage.LookUp -> DeviceApprovalFailure.AlreadyUsed
            failure.status == HTTP_NOT_FOUND || failure.status == HTTP_CONFLICT -> DeviceApprovalFailure.Expired
            else -> DeviceApprovalFailure.ServerError
        }

    private companion object {
        const val PENDING_STATUS = "pending"
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        val RETRYABLE = setOf(DeviceApprovalFailure.ConnectionFailed, DeviceApprovalFailure.ServerError)
    }
}
