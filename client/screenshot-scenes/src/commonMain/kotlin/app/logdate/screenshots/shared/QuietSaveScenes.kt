package app.logdate.screenshots.shared

import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.feature.core.settings.ui.AudioNotesSettingsContent
import app.logdate.feature.core.settings.ui.VoiceNotesSettingsViewModel

internal val quietSaveScenes =
    listOf(
        sharedScene(SharedScreenshotSceneId.QuietSaveSettings, ScreenshotSceneGroup.SETTINGS, osIntegrationVariants) {
            AudioNotesSettingsContent(
                state =
                    VoiceNotesSettingsViewModel.UiState(
                        quietSaveAvailable = true,
                        transcription = VoiceNotesSettingsViewModel.ModelRowState(ModelDownloadStatus.Completed),
                        tagging = VoiceNotesSettingsViewModel.ModelRowState(ModelDownloadStatus.Idle),
                    ),
                onOpenTranscription = {},
                onOpenSoundRecognition = {},
                onQuietSaveChanged = {},
                onBack = {},
            )
        },
    )
