package app.logdate.feature.core.account

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInFailure
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class BlueskySignInRenderTest {
    @Test
    fun `account stages fit phone tablet compact landscape and large text`() {
        val details = AccountCreationRequest("alice", "Alice Writer")
        val profile = BlueskySignInUiState(available = true, details = details, detailsLocked = true)
        val handle = BlueskySignInUiState(available = true, handle = "alice.bsky.social")
        val cases =
            listOf(
                Scene("phone-handle", 412, 915, handle, BlueskySignInState.Idle, "bluesky_start"),
                Scene("tablet-handle", 1024, 768, handle, BlueskySignInState.Idle, "bluesky_start"),
                Scene("phone-profile", 412, 915, profile, BlueskySignInState.AccountDetails(details), CLOUD_ACCOUNT_PASSKEY_CREATE_TAG),
                Scene("tablet-profile", 1024, 768, profile, BlueskySignInState.AccountDetails(details), CLOUD_ACCOUNT_PASSKEY_CREATE_TAG),
                Scene("compact-profile", 320, 640, profile, BlueskySignInState.AccountDetails(details), CLOUD_ACCOUNT_PASSKEY_CREATE_TAG),
                Scene(
                    "large-text-profile",
                    412,
                    915,
                    profile,
                    BlueskySignInState.AccountDetails(details),
                    CLOUD_ACCOUNT_PASSKEY_CREATE_TAG,
                    fontScale = 2f,
                ),
                Scene(
                    "landscape-rtl-dark",
                    840,
                    412,
                    profile,
                    BlueskySignInState.AccountDetails(details),
                    CLOUD_ACCOUNT_PASSKEY_CREATE_TAG,
                    dark = true,
                    rtl = true,
                ),
                Scene("phone-passkey", 412, 915, handle, BlueskySignInState.ConfirmPasskey, "bluesky_confirm"),
                Scene("phone-browser", 412, 915, handle, BlueskySignInState.AwaitingBrowser, "bluesky_restart"),
                Scene("phone-approval", 412, 915, handle, BlueskySignInState.DeviceApproval(true), "bluesky_restart"),
                Scene("phone-expired", 412, 915, handle, BlueskySignInState.Failed(BlueskySignInFailure.EXPIRED), "bluesky_start"),
            )
        for (scene in cases) render(scene)
    }

    private fun render(scene: Scene) =
        runDesktopComposeUiTest(width = scene.width, height = scene.height) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scene.fontScale),
                    LocalLayoutDirection provides if (scene.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    LogDateTheme(dynamicColor = false, darkTheme = scene.dark) {
                        BlueskySignInContent(
                            ui = scene.ui,
                            stage = scene.stage,
                            accountSetupContent = {
                                PasskeyAccountCreationFinalContent(
                                    scene.ui.details.displayName,
                                    scene.ui.details.username,
                                    {},
                                    {},
                                    false,
                                    null,
                                    {},
                                    true,
                                    "LogDate",
                                    1,
                                    1,
                                    detailsEditable = !scene.ui.detailsLocked,
                                )
                            },
                            onHandleChange = {},
                            onStart = {},
                            onConfirm = {},
                            onAdopt = {},
                            onRestart = {},
                            onCancel = {},
                        )
                    }
                }
            }
            onNodeWithTag(scene.action).assertIsDisplayed()
            save(scene.name, onRoot().captureToImage())
            if (scene.stage is BlueskySignInState.AccountDetails) {
                onNodeWithTag(CLOUD_ACCOUNT_PASSKEY_ROOT_TAG).assertIsDisplayed()
                onNodeWithTag(scene.action).assertIsDisplayed()
                if (scene.fontScale > 1f || scene.height < 500) save("${scene.name}-scrolled", onRoot().captureToImage())
            }
        }

    private fun save(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_BLUESKY_UI_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }

    private data class Scene(
        val name: String,
        val width: Int,
        val height: Int,
        val ui: BlueskySignInUiState,
        val stage: BlueskySignInState,
        val action: String,
        val fontScale: Float = 1f,
        val dark: Boolean = false,
        val rtl: Boolean = false,
    )
}
