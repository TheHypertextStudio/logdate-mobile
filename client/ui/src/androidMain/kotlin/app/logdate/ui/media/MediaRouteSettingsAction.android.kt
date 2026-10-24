package app.logdate.ui.media

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.logdate.client.media.device.MediaDeviceKind
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.media_route_open_output_switcher
import logdate.client.ui.generated.resources.media_route_open_sound_settings
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun rememberMediaRouteSettingsAction(kind: MediaDeviceKind): MediaRouteSettingsAction? {
    val context = LocalContext.current
    val soundSettingsLabel = stringResource(Res.string.media_route_open_sound_settings)
    val outputSwitcherLabel = stringResource(Res.string.media_route_open_output_switcher)
    return remember(context, kind, soundSettingsLabel, outputSwitcherLabel) {
        when (kind) {
            MediaDeviceKind.AUDIO_INPUT -> {
                MediaRouteSettingsAction(label = soundSettingsLabel) {
                    context.openSoundSettings()
                }
            }

            MediaDeviceKind.AUDIO_OUTPUT -> {
                MediaRouteSettingsAction(label = outputSwitcherLabel) {
                    val openedOutputSwitcher =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            MediaRouter2
                                .getInstance(context.applicationContext)
                                .showSystemOutputSwitcher()
                        } else {
                            false
                        }

                    if (!openedOutputSwitcher) {
                        context.openSoundSettings()
                    }
                }
            }

            MediaDeviceKind.CAMERA -> {
                null
            }
        }
    }
}

private fun android.content.Context.openSoundSettings() {
    val intent =
        Intent(Settings.ACTION_SOUND_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}
