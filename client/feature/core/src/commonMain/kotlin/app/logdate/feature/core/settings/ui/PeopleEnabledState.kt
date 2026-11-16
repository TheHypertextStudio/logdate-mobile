package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.koinInject

/**
 * Whether People is turned on in Labs for this installation, read when the app starts and followed
 * for as long as it runs.
 *
 * [enabled] is `null` only until the stored value has been read for the first time. A page opened
 * after that starts from the stored value: someone who has People on sees no empty frame, and a
 * People page is never composed for someone who has it off. If the stored value cannot be read,
 * People counts as off.
 */
class PeopleSwitch(
    featureFlags: FeatureFlagStore,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val enabled: StateFlow<Boolean?> =
        featureFlags
            .observe(FeatureFlag.PEOPLE)
            .map<Boolean, Boolean?> { it }
            .catch { error ->
                Napier.e("Could not read whether People is turned on", error)
                emit(false)
            }.stateIn(scope, SharingStarted.Eagerly, initialValue = null)
}

/**
 * Whether People is turned on in Labs for this installation, or `null` until the stored value has
 * been read.
 *
 * Settings lists its People row and category only for `true`. A People route renders nothing for
 * `null`, so someone who has People on is not sent back while the value loads, and closes for
 * `false`.
 */
@Composable
internal fun rememberPeopleEnabled(peopleSwitch: PeopleSwitch = koinInject()): Boolean? {
    val enabled by peopleSwitch.enabled.collectAsStateWithLifecycle()
    return enabled
}

/**
 * Which Settings rows exist only when their feature is available: People while it is turned on in
 * Labs, Events once it has launched.
 */
internal data class OptionalSettingsRows(
    val people: Boolean,
    val events: Boolean,
)

/** The optional Settings rows for this installation. People stays out until its stored value is read. */
@Composable
internal fun rememberOptionalSettingsRows(peopleSwitch: PeopleSwitch = koinInject()): OptionalSettingsRows {
    val peopleEnabled = rememberPeopleEnabled(peopleSwitch)
    return OptionalSettingsRows(
        people = peopleEnabled == true,
        events = FeatureFlag.EVENTS.availableForLaunch,
    )
}
