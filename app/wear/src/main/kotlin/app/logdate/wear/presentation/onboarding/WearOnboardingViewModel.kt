package app.logdate.wear.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.wear.v2.phone.HandoffResult
import app.logdate.wear.v2.phone.PhoneHandoff
import app.logdate.wear.v2.phone.PhoneLink
import app.logdate.wear.v2.phone.PhoneLinkRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Helps first-time watch users finish optional phone setup without blocking local capture. */
class WearOnboardingViewModel(
    private val phoneLinks: PhoneLinkRepository,
    private val phoneHandoff: PhoneHandoff,
) : ViewModel() {
    private val mutablePhoneCheckState = MutableStateFlow<PhoneCheckState>(PhoneCheckState.Checking)
    val phoneCheckState: StateFlow<PhoneCheckState> = mutablePhoneCheckState.asStateFlow()

    private val mutablePhoneActionState = MutableStateFlow(PhoneActionState.IDLE)
    val phoneActionState: StateFlow<PhoneActionState> = mutablePhoneActionState.asStateFlow()

    init {
        checkPhoneConnection()
    }

    fun checkPhoneConnection() {
        mutablePhoneCheckState.value = PhoneCheckState.Checking
        mutablePhoneActionState.value = PhoneActionState.IDLE
        viewModelScope.launch {
            mutablePhoneCheckState.value =
                try {
                    when (val link = phoneLinks.current()) {
                        is PhoneLink.Connected -> PhoneCheckState.Connected(link.nodeId, link.phoneName)
                        is PhoneLink.AppMissing -> PhoneCheckState.AppMissing(link.nodeId)
                        PhoneLink.Unsupported -> PhoneCheckState.Unsupported
                        is PhoneLink.AppUnknown, PhoneLink.Unknown, PhoneLink.NotConnected -> PhoneCheckState.NotConnected
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Phone connection check failed during onboarding", error)
                    PhoneCheckState.NotConnected
                }
        }
    }

    fun openPhoneSetup() {
        val link = phoneCheckState.value
        if (phoneActionState.value == PhoneActionState.SENDING) return
        if (link !is PhoneCheckState.Connected && link !is PhoneCheckState.AppMissing) return
        mutablePhoneActionState.value = PhoneActionState.SENDING
        viewModelScope.launch {
            val result =
                when (link) {
                    is PhoneCheckState.Connected -> phoneHandoff.open(PHONE_WATCH_SETTINGS_LINK, link.nodeId)
                    is PhoneCheckState.AppMissing -> phoneHandoff.openAppListing(link.nodeId)
                    else -> return@launch
                }
            mutablePhoneActionState.value = if (result == HandoffResult.SENT) PhoneActionState.SENT else PhoneActionState.FAILED
        }
    }

    private companion object {
        const val PHONE_WATCH_SETTINGS_LINK = "studio.hypertext.logdate://settings/watch"
    }
}

sealed interface PhoneCheckState {
    data object Checking : PhoneCheckState

    data class Connected(
        val nodeId: String,
        val phoneName: String,
    ) : PhoneCheckState

    data class AppMissing(
        val nodeId: String,
    ) : PhoneCheckState

    data object NotConnected : PhoneCheckState

    data object Unsupported : PhoneCheckState
}

enum class PhoneActionState {
    IDLE,
    SENDING,
    SENT,
    FAILED,
}
