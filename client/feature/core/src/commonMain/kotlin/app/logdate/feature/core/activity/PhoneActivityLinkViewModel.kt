package app.logdate.feature.core.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.activity.PhoneActivityRegistration
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal enum class PhoneActivityChoice {
    Idle,
    Saving,
    LinkFailed,
    UnlinkFailed,
}

internal class PhoneActivityLinkViewModel(
    private val flags: FeatureFlagStore,
    private val registration: PhoneActivityRegistration,
) : ViewModel() {
    private val pendingChoice = MutableStateFlow(PhoneActivityChoice.Idle)
    val choice = pendingChoice.asStateFlow()
    val optedIn =
        flags.observe(FeatureFlag.ACTIVITY_AWARENESS).stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    fun setLinked(linked: Boolean) {
        if (pendingChoice.value == PhoneActivityChoice.Saving) return
        if (linked && !registration.canStartLink()) {
            registration.refresh()
            return
        }
        pendingChoice.value = PhoneActivityChoice.Saving
        viewModelScope.launch {
            try {
                val wasLinked = flags.isEnabled(FeatureFlag.ACTIVITY_AWARENESS)
                flags.setEnabled(FeatureFlag.ACTIVITY_AWARENESS, linked)
                if (wasLinked == linked) registration.refresh()
                pendingChoice.value = PhoneActivityChoice.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingChoice.value = if (linked) PhoneActivityChoice.LinkFailed else PhoneActivityChoice.UnlinkFailed
                Napier.w("Couldn't save the phone activity choice", e)
            }
        }
    }
}
