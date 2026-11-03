@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.runtime.Composable

object SharedScreenshotCatalog {
    val onboardingMainScenes: List<SharedScreenshotSceneSpec> = catalogOnboardingMainScenes
    val onboardingExtendedScenes: List<SharedScreenshotSceneSpec> = catalogOnboardingExtendedScenes
    val settingsScenes: List<SharedScreenshotSceneSpec> =
        personalSettingsScenes + quietSaveScenes + systemSettingsScenes + nativeBackupScenes + journalStorageScenes +
            settingsRowScenes + connectedAssistantScenes
    val searchScenes: List<SharedScreenshotSceneSpec> = catalogSearchScenes
    val desktopOnlyScenes: List<SharedScreenshotSceneSpec> = catalogDesktopOnlyScenes
    val allScenes: List<SharedScreenshotSceneSpec> =
        onboardingMainScenes +
            onboardingExtendedScenes +
            settingsScenes +
            watchSettingsScenes +
            searchScenes +
            desktopOnlyScenes +
            provenanceScenes

    fun scene(id: SharedScreenshotSceneId): SharedScreenshotSceneSpec = allScenes.first { it.id == id }
}

@Composable
@Suppress("ktlint:standard:function-naming")
fun SharedScreenshotScene(sceneId: SharedScreenshotSceneId) {
    SharedScreenshotCatalog.scene(sceneId).content()
}

fun screenshotBaselineName(
    scene: SharedScreenshotSceneSpec,
    variant: ScreenshotSceneVariant,
): String =
    variant.viewport.baselineSuffix.takeIf { it.isNotBlank() }?.let { suffix ->
        "${scene.id.value}--$suffix"
    } ?: scene.id.value

internal fun sharedScene(
    id: SharedScreenshotSceneId,
    group: ScreenshotSceneGroup,
    variants: List<ScreenshotSceneVariant>,
    content: @Composable () -> Unit,
): SharedScreenshotSceneSpec =
    SharedScreenshotSceneSpec(
        id = id,
        group = group,
        variants = variants,
        content = content,
    )
