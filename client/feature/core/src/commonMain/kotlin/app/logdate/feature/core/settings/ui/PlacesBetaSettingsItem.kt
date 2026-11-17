@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import app.logdate.ui.common.ToggleSettingsItem
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.places_beta_description
import logdate.client.feature.core.generated.resources.places_beta_save_failed
import logdate.client.feature.core.generated.resources.places_beta_title
import org.jetbrains.compose.resources.stringResource

/** The Labs row that turns the Places and location history beta on or off. */
@Composable
fun PlacesBetaSettingsItem(
    state: PlacesBetaSettingsState,
    onSetEnabled: (Boolean) -> Unit,
) {
    val ready = state as? PlacesBetaSettingsState.Ready
    ToggleSettingsItem(
        title = stringResource(Res.string.places_beta_title),
        description =
            stringResource(
                if (ready?.saveFailed ==
                    true
                ) {
                    Res.string.places_beta_save_failed
                } else {
                    Res.string.places_beta_description
                },
            ),
        checked = ready?.enabled == true,
        onCheckedChange = onSetEnabled,
        enabled = ready != null && !ready.saving,
    )
}
