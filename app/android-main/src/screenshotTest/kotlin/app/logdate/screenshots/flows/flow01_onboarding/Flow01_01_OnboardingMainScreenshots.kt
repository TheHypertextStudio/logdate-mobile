package app.logdate.screenshots.flows.flow01_onboarding

import androidx.compose.runtime.Composable
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.SharedScreenshotScene
import app.logdate.screenshots.shared.SharedScreenshotSceneId
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S01_OnboardingStartSplash() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingStartSplash)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S02_OnboardingStartLanding() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingStartLanding)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S03_PersonalIntroNameStep() = SharedOnboardingMainScene(SharedScreenshotSceneId.PersonalIntroName)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S04_PersonalIntroBioStep() = SharedOnboardingMainScene(SharedScreenshotSceneId.PersonalIntroBio)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S05_OnboardingOverview() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingOverview)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S06_MemoriesImportInfo() = SharedOnboardingMainScene(SharedScreenshotSceneId.MemoriesImportInfo)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S07_MemorySelectionPopulated() = SharedOnboardingMainScene(SharedScreenshotSceneId.MemorySelectionPopulated)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S08_CloudAccountSetupCompact() = SharedOnboardingMainScene(SharedScreenshotSceneId.CloudAccountSetupCompact)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S09_OnboardingBirthday() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingBirthday)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S10_OnboardingRecommendations() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingRecommendations)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S11_OnboardingDayBoundariesConnected() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingDayBoundariesConnected)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S12_OnboardingLocation() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingLocation)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S13_OnboardingNotifications() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingNotifications)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S14_OnboardingCompletionStreak() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingCompletionCampfire)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S15_OnboardingCompletionFinal() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingCompletionFinal)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S16_OnboardingWelcomeBack() = SharedOnboardingMainScene(SharedScreenshotSceneId.OnboardingWelcomeBack)

@Composable
private fun SharedOnboardingMainScene(sceneId: SharedScreenshotSceneId) {
    ScreenshotTheme {
        SharedScreenshotScene(sceneId)
    }
}
