@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.ToggleSettingsItem
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.feature_preview_app_functions_description
import logdate.client.feature.core.generated.resources.feature_preview_app_functions_title
import logdate.client.feature.core.generated.resources.feature_preview_editor_handoff_description
import logdate.client.feature.core.generated.resources.feature_preview_editor_handoff_title
import logdate.client.feature.core.generated.resources.feature_preview_live_updates_description
import logdate.client.feature.core.generated.resources.feature_preview_live_updates_title
import logdate.client.feature.core.generated.resources.feature_preview_lock_screen_widget_description
import logdate.client.feature.core.generated.resources.feature_preview_lock_screen_widget_title
import logdate.client.feature.core.generated.resources.feature_preview_quick_reply_description
import logdate.client.feature.core.generated.resources.feature_preview_quick_reply_title
import logdate.client.feature.core.generated.resources.feature_preview_quick_settings_tiles_description
import logdate.client.feature.core.generated.resources.feature_preview_quick_settings_tiles_title
import logdate.client.feature.core.generated.resources.feature_preview_recording_bubble_description
import logdate.client.feature.core.generated.resources.feature_preview_recording_bubble_title
import logdate.client.feature.core.generated.resources.feature_previews
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One Android surface that can be turned on before it ships to everyone. */
data class FeaturePreview(
    val flag: FeatureFlag,
    val enabled: Boolean,
)

/** The Android OS integration surfaces that ship behind a flag, in the order they are listed. */
val osIntegrationPreviewFlags: List<FeatureFlag> =
    listOf(
        FeatureFlag.QUICK_SETTINGS_TILES,
        FeatureFlag.LIVE_UPDATES,
        FeatureFlag.NOTIFICATION_QUICK_REPLY,
        FeatureFlag.RECORDING_BUBBLE,
        FeatureFlag.LOCK_SCREEN_CAPTURE_WIDGET,
        FeatureFlag.APP_FUNCTIONS,
        FeatureFlag.EDITOR_HANDOFF,
    )

/** Reads the preview flags from [store] and turns them on or off. */
@Composable
fun FeaturePreviewsSection(
    store: FeatureFlagStore,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val previews =
        osIntegrationPreviewFlags.map { flag ->
            val enabled by store.observe(flag).collectAsState(initial = flag.defaultEnabled)
            FeaturePreview(flag, enabled)
        }
    FeaturePreviewsSection(
        previews = previews,
        onToggle = { flag, enabled -> scope.launch { store.setEnabled(flag, enabled) } },
        modifier = modifier,
    )
}

/** Toggles for Android surfaces that are still being verified on managed devices. */
@Composable
fun FeaturePreviewsSection(
    previews: List<FeaturePreview>,
    onToggle: (FeatureFlag, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSection(title = stringResource(Res.string.feature_previews), modifier = modifier) {
        previews.forEach { preview ->
            val (title, description) = preview.flag.previewText()
            ToggleSettingsItem(
                title = stringResource(title),
                description = stringResource(description),
                checked = preview.enabled,
                onCheckedChange = { onToggle(preview.flag, it) },
            )
        }
    }
}

private fun FeatureFlag.previewText(): Pair<StringResource, StringResource> =
    when (this) {
        FeatureFlag.QUICK_SETTINGS_TILES -> {
            Res.string.feature_preview_quick_settings_tiles_title to Res.string.feature_preview_quick_settings_tiles_description
        }

        FeatureFlag.LIVE_UPDATES -> {
            Res.string.feature_preview_live_updates_title to Res.string.feature_preview_live_updates_description
        }

        FeatureFlag.NOTIFICATION_QUICK_REPLY -> {
            Res.string.feature_preview_quick_reply_title to
                Res.string.feature_preview_quick_reply_description
        }

        FeatureFlag.RECORDING_BUBBLE -> {
            Res.string.feature_preview_recording_bubble_title to Res.string.feature_preview_recording_bubble_description
        }

        FeatureFlag.LOCK_SCREEN_CAPTURE_WIDGET -> {
            Res.string.feature_preview_lock_screen_widget_title to Res.string.feature_preview_lock_screen_widget_description
        }

        FeatureFlag.APP_FUNCTIONS -> {
            Res.string.feature_preview_app_functions_title to Res.string.feature_preview_app_functions_description
        }

        FeatureFlag.EDITOR_HANDOFF -> {
            Res.string.feature_preview_editor_handoff_title to
                Res.string.feature_preview_editor_handoff_description
        }

        else -> {
            error("$this is not an OS integration preview")
        }
    }
