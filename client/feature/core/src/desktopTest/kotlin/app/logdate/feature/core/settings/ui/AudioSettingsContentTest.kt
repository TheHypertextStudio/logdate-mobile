package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AudioSettingsContentTest {
    @Test
    fun overviewOpensCapabilitiesInsteadOfOfferingDownloads() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var transcriptionOpens = 0
            var recognitionOpens = 0
            setContent {
                LogDateTheme {
                    AudioNotesSettingsContent(
                        state = VoiceNotesSettingsViewModel.UiState(),
                        onOpenTranscription = { transcriptionOpens++ },
                        onOpenSoundRecognition = { recognitionOpens++ },
                        onBack = {},
                    )
                }
            }
            onNodeWithText("Audio notes").assertExists()
            onNodeWithText("Download").assertDoesNotExist()
            onNodeWithText("Transcription").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            onNodeWithText("Sound recognition").performClick()
            assertEquals(1, transcriptionOpens)
            assertEquals(1, recognitionOpens)
        }

    @Test
    fun checkingDoesNotOfferDownload() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.Checking),
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Checking…").assertExists()
            onNodeWithText("Download").assertDoesNotExist()
            onNodeWithText("Retry download").assertDoesNotExist()
        }

    @Test
    fun failedDiscoveryContinuesWithoutAskingForManualRepair() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var downloads = 0
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.CheckFailed),
                        onDownload = { downloads++ },
                        onBack = {},
                    )
                }
            }
            onNodeWithText("Still checking. This will update automatically.").assertExists()
            onNodeWithText("Check again").assertDoesNotExist()
            assertEquals(0, downloads)
        }

    @Test
    fun missingModelOffersDownloadAndNonSpeechExplanation() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var downloads = 0
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.Idle),
                        onDownload = { downloads++ },
                        onBack = {},
                    )
                }
            }
            onNodeWithText("Your recording does not need to contain speech.").assertExists()
            onNodeWithText("Download").performClick()
            assertEquals(1, downloads)
        }

    @Test
    fun systemManagedTranscriptionDoesNotPromiseAnOfflineDownload() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.Transcription,
                        VoiceNotesSettingsViewModel.UiState(
                            transcription = VoiceNotesSettingsViewModel.ModelRowState(ModelDownloadStatus.ManagedBySystem),
                        ),
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Built into your device").assertExists()
            onNodeWithText("Ready to use").assertDoesNotExist()
            onNodeWithText("Download").assertDoesNotExist()
        }

    @Test
    fun unsupportedRecognitionIsNeutralAndDoesNotOfferRetry() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.NotSupported),
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Not available on this device").assertExists()
            onNodeWithText("Retry download").assertDoesNotExist()
            onNodeWithText("Download").assertDoesNotExist()
        }

    @Test
    fun downloadShowsTransferredBytesAndKeepsInstallationDistinct() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.Downloading(1_048_576, 4_194_304)),
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("1.0 MB of 4.0 MB").assertExists()
            onNodeWithText("Setting up…").assertDoesNotExist()
            onNodeWithText("Download").assertDoesNotExist()
        }

    @Test
    fun installedModelIsReadyWithoutAnotherDownloadAction() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.Completed),
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Ready to use").assertExists()
            onNodeWithText("Download").assertDoesNotExist()
        }

    @Test
    fun installationDoesNotOfferAnotherDownload() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.Extracting),
                        {},
                        {},
                    )
                }
            }
            onNodeWithText("Setting up…").assertExists()
            onNodeWithText("Download").assertDoesNotExist()
        }

    @Test
    fun interruptedDownloadDoesNotAskForManualRepair() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var retries = 0
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(
                        AudioSettingsCapability.SoundRecognition,
                        state(ModelDownloadStatus.NoNetwork),
                        onDownload = { retries++ },
                        onBack = {},
                    )
                }
            }
            onNodeWithText("Waiting for a connection. The download will continue automatically.").assertExists()
            onNodeWithText("Retry download").assertDoesNotExist()
            onNodeWithText("Download").assertDoesNotExist()
            assertEquals(0, retries)
        }

    @Test
    fun downloadRemainsReachableWithLargeTextOnCompactScreens() =
        runDesktopComposeUiTest(width = 360, height = 720) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    LogDateTheme {
                        AudioModelSettingsContent(
                            AudioSettingsCapability.SoundRecognition,
                            state(ModelDownloadStatus.Idle),
                            {},
                            {},
                        )
                    }
                }
            }
            onNodeWithText("Download").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun availabilityChangesAreAnnouncedWithTheirStatusText() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val status = mutableStateOf<ModelDownloadStatus>(ModelDownloadStatus.Checking)
            setContent {
                LogDateTheme {
                    AudioModelSettingsContent(AudioSettingsCapability.SoundRecognition, state(status.value), {}, {})
                }
            }
            onNodeWithText(
                "Checking…",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            runOnIdle { status.value = ModelDownloadStatus.Completed }
            onNodeWithText(
                "Ready to use",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

    private fun state(status: ModelDownloadStatus) =
        VoiceNotesSettingsViewModel.UiState(
            tagging = VoiceNotesSettingsViewModel.ModelRowState(status),
        )
}
