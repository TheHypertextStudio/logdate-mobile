@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.BlueskySignInRepository
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.koin.compose.koinInject

/** Availability checks never restore an authorization or open a passkey prompt. */
@Composable
internal fun availableBlueskyAction(action: (() -> Unit)?): (() -> Unit)? {
    if (action == null) return null
    val repository: BlueskySignInRepository = koinInject()
    val config: LogDateConfigRepository = koinInject()
    val flags: FeatureFlagStore = koinInject()
    val passkeys: PasskeyManager = koinInject()
    val available by produceState(false, repository, config, flags, passkeys) {
        combine(
            config.backendUrl,
            config.serverDescriptor,
            flags.observe(FeatureFlag.BLUESKY_SIGN_IN),
            passkeys.getAvailabilityStatus(),
        ) { _, _, enabled, capabilities -> enabled && capabilities.isSupported }
            .collectLatest { locallyAllowed ->
                value = false
                value = locallyAllowed &&
                    try {
                        repository.prepare()
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        false
                    }
            }
    }
    return action.takeIf { available }
}
