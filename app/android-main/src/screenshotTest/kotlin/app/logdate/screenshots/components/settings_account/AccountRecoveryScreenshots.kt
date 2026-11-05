package app.logdate.screenshots.components.settings_account

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.repository.account.IdentityRecoveryFailure
import app.logdate.feature.core.settings.account.recovery.AccountRecoveryUiState
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseContent
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseUiState
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.SharedScreenshotScene
import app.logdate.screenshots.shared.SharedScreenshotSceneId
import com.android.tools.screenshot.PreviewTest

// ─── Shared scenes, also rendered by the desktop lane ───────────────────────────

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun RecoveryPhrase_Confirmed() {
    ScreenshotTheme { SharedScreenshotScene(SharedScreenshotSceneId.RecoveryPhraseConfirmed) }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun RecoveryPhrase_FinishSetup() {
    ScreenshotTheme { SharedScreenshotScene(SharedScreenshotSceneId.RecoveryPhraseFinishSetup) }
}

// ─── Other states ───────────────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun RecoveryPhrase_FinishingSetup() {
    ScreenshotTheme { ConfirmedPhrase(AccountRecoveryUiState.Finishing) }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun RecoveryPhrase_SetupUnreachable() {
    ScreenshotTheme { ConfirmedPhrase(AccountRecoveryUiState.SetupFailed(IdentityRecoveryFailure.UNREACHABLE)) }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun RecoveryPhrase_RecoveryCheckFailed() {
    ScreenshotTheme { ConfirmedPhrase(AccountRecoveryUiState.CheckFailed) }
}

@Composable
private fun ConfirmedPhrase(accountRecovery: AccountRecoveryUiState) {
    RecoveryPhraseContent(
        state = RecoveryPhraseUiState.Available(),
        onBack = {},
        onReveal = {},
        onHide = {},
        onEnterPhrase = {},
        accountRecovery = accountRecovery,
    )
}
