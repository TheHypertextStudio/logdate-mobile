@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.crypto.KeyBackupPreference
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.ToggleSettingsItem
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.key_backup_choice_failed
import logdate.client.feature.core.generated.resources.key_backup_choice_kept
import logdate.client.feature.core.generated.resources.key_backup_choice_section
import logdate.client.feature.core.generated.resources.key_backup_choice_shared
import logdate.client.feature.core.generated.resources.key_backup_choice_title
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.cancellation.CancellationException

/** The "Keep my key on this device only" row, where the platform can honor it. */
data class KeyBackupChoiceState(
    val keptOnDevice: Boolean,
    val updating: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Offers the journal key opt-out from cloud backup. Emits null, so no row appears, where the
 * platform has no such choice or while [FeatureFlag.PLATFORM_KEY_BACKUP] is off.
 */
class KeyBackupChoiceViewModel(
    private val preference: KeyBackupPreference?,
    flags: FeatureFlagStore?,
) : ViewModel() {
    private val progress = MutableStateFlow(Progress())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<KeyBackupChoiceState?> =
        if (preference == null || flags == null) {
            MutableStateFlow(null)
        } else {
            flags
                .observe(FeatureFlag.PLATFORM_KEY_BACKUP)
                .flatMapLatest { enabled ->
                    if (!enabled) {
                        flowOf(null)
                    } else {
                        combine(preference.cloudBackupAllowed, progress) { allowed, progress ->
                            KeyBackupChoiceState(keptOnDevice = !allowed, updating = progress.updating, failed = progress.failed)
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        }

    fun setKeepOnDevice(keep: Boolean) {
        val preference = preference ?: return
        if (progress.value.updating) return
        progress.value = Progress(updating = true)
        viewModelScope.launch {
            val failed =
                try {
                    preference.setCloudBackupAllowed(!keep)
                    false
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Napier.w("Could not apply the journal key backup choice", error)
                    true
                }
            progress.value = Progress(failed = failed)
        }
    }

    private data class Progress(
        val updating: Boolean = false,
        val failed: Boolean = false,
    )
}

@Composable
internal fun KeyBackupChoiceSection(
    state: KeyBackupChoiceState,
    onKeepOnDeviceChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description =
        when {
            state.failed -> stringResource(Res.string.key_backup_choice_failed)
            state.keptOnDevice -> stringResource(Res.string.key_backup_choice_kept)
            else -> stringResource(Res.string.key_backup_choice_shared)
        }
    SettingsSection(title = stringResource(Res.string.key_backup_choice_section), modifier = modifier) {
        ToggleSettingsItem(
            title = stringResource(Res.string.key_backup_choice_title),
            description = description,
            checked = state.keptOnDevice,
            onCheckedChange = onKeepOnDeviceChange,
            enabled = !state.updating,
        )
    }
}
