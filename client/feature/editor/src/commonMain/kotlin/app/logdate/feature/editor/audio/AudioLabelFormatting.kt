package app.logdate.feature.editor.audio

import androidx.compose.runtime.Composable
import app.logdate.client.awareness.daylight.stringRes
import io.github.aakira.napier.Napier
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.audio_label_period_at_location
import logdate.client.feature.editor.generated.resources.audio_label_period_recording
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Formats an [AudioLabelResult] into a localized title string using Compose string resources.
 *
 * Use in composable contexts.
 */
@Composable
fun formatAudioLabel(result: AudioLabelResult): String =
    when (result) {
        is AudioLabelResult.Caption -> {
            result.text
        }

        is AudioLabelResult.Contextual -> {
            val periodName = stringResource(result.period.stringRes)
            if (result.locationName != null) {
                stringResource(Res.string.audio_label_period_at_location, periodName, result.locationName)
            } else {
                stringResource(Res.string.audio_label_period_recording, periodName)
            }
        }
    }

/**
 * Formats an [AudioLabelResult] into a localized title string.
 *
 * Use in non-composable suspend contexts (e.g., ViewModels).
 */
suspend fun formatAudioLabelAsync(result: AudioLabelResult): String =
    runCatching {
        when (result) {
            is AudioLabelResult.Caption -> {
                result.text
            }

            is AudioLabelResult.Contextual -> {
                val periodName = getString(result.period.stringRes)
                if (result.locationName != null) {
                    getString(Res.string.audio_label_period_at_location, periodName, result.locationName)
                } else {
                    getString(Res.string.audio_label_period_recording, periodName)
                }
            }
        }
    }.getOrElse { error ->
        Napier.w("Falling back to non-localized audio label formatting", error)
        fallbackAudioLabel(result)
    }

private fun fallbackAudioLabel(result: AudioLabelResult): String =
    when (result) {
        is AudioLabelResult.Caption -> {
            result.text
        }

        is AudioLabelResult.Contextual -> {
            val periodName =
                result.period.name
                    .lowercase()
                    .replace('_', ' ')
            if (result.locationName != null) {
                "$periodName at ${result.locationName}"
            } else {
                "$periodName recording"
            }
        }
    }
