@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.logdate.client.datastore.EntryStartMode
import app.logdate.ui.common.LocalSettingsEnabled
import app.logdate.ui.common.SettingsChoiceItem
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.capture_default_audio
import logdate.client.feature.core.generated.resources.capture_default_camera
import logdate.client.feature.core.generated.resources.capture_default_description
import logdate.client.feature.core.generated.resources.capture_default_gallery
import logdate.client.feature.core.generated.resources.capture_default_handwriting
import logdate.client.feature.core.generated.resources.capture_default_loading
import logdate.client.feature.core.generated.resources.capture_default_save_failed
import logdate.client.feature.core.generated.resources.capture_default_text
import logdate.client.feature.core.generated.resources.capture_default_title
import logdate.client.feature.core.generated.resources.capture_default_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DefaultCaptureModeSettingsSection(
    state: DefaultCaptureModeState,
    onSelect: (EntryStartMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SettingsSection(title = stringResource(Res.string.capture_default_title)) {
            when (state) {
                DefaultCaptureModeState.Loading -> {
                    SimpleSettingsItem(
                        title =
                            stringResource(
                                Res.string.capture_default_loading,
                            ),
                        description = null,
                        action = {
                        },
                    )
                }

                DefaultCaptureModeState.Unavailable -> {
                    SimpleSettingsItem(
                        title =
                            stringResource(
                                Res.string.capture_default_unavailable,
                            ),
                        description = null,
                        action = {
                        },
                    )
                }

                is DefaultCaptureModeState.Ready -> {
                    EntryStartMode.entries.forEach { mode ->
                        SettingsChoiceItem(
                            title =
                                stringResource(
                                    when (mode) {
                                        EntryStartMode.TEXT -> Res.string.capture_default_text
                                        EntryStartMode.HANDWRITING -> Res.string.capture_default_handwriting
                                        EntryStartMode.CAMERA -> Res.string.capture_default_camera
                                        EntryStartMode.AUDIO -> Res.string.capture_default_audio
                                        EntryStartMode.GALLERY -> Res.string.capture_default_gallery
                                    },
                                ),
                            selected = mode == state.mode,
                            onClick = { onSelect(mode) },
                            enabled = LocalSettingsEnabled.current && !state.saving,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(Res.string.capture_default_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state is DefaultCaptureModeState.Ready && state.saveFailed) {
            Text(
                stringResource(Res.string.capture_default_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
