package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlacesBetaSettingsState {
    data object Loading : PlacesBetaSettingsState

    data class Ready(
        val enabled: Boolean,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : PlacesBetaSettingsState
}

/** One opt-in for Places and history browsing, exposed in Labs without developer mode. */
class PlacesBetaSettingsViewModel(
    private val flags: FeatureFlagStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PlacesBetaSettingsState>(PlacesBetaSettingsState.Loading)
    val state: StateFlow<PlacesBetaSettingsState> = mutableState.asStateFlow()
    private var saving = false

    init {
        viewModelScope.launch {
            flags.observe(FeatureFlag.PLACES_BETA).collect { enabled ->
                val previous = mutableState.value as? PlacesBetaSettingsState.Ready
                mutableState.value = PlacesBetaSettingsState.Ready(enabled, saving, previous?.saveFailed == true)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val previous = mutableState.value as? PlacesBetaSettingsState.Ready ?: return
        if (saving) return
        saving = true
        mutableState.value = previous.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            try {
                flags.setEnabled(FeatureFlag.PLACES_BETA, enabled)
                mutableState.value = PlacesBetaSettingsState.Ready(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Could not save Places beta preference", e)
                val current = mutableState.value as? PlacesBetaSettingsState.Ready ?: previous
                mutableState.value = current.copy(saving = false, saveFailed = true)
            } finally {
                saving = false
            }
        }
    }
}
