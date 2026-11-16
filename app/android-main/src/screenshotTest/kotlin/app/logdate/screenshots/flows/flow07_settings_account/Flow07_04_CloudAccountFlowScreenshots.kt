package app.logdate.screenshots.flows.flow07_settings_account

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.feature.core.account.CloudAccountHostingContent
import app.logdate.feature.core.account.CloudAccountPlansContent
import app.logdate.feature.core.account.CloudAccountSignInContent
import app.logdate.feature.core.account.CloudAccountWelcomeContent
import app.logdate.feature.core.account.PasskeyAccountCreationFinalContent
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest

// ─── Welcome ────────────────────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S01_CloudAccountWelcome() {
    ScreenshotTheme {
        CloudAccountWelcomeContent(
            onContinue = {},
            onSignIn = {},
            onBack = {},
            serverSelectionState = ServerSelectionState(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun S02_CloudAccountWelcomeDark() {
    ScreenshotTheme(darkTheme = true) {
        CloudAccountWelcomeContent(
            onContinue = {},
            onSignIn = {},
            onBack = {},
            serverSelectionState = ServerSelectionState(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S02b_CloudAccountChooseServer() {
    ScreenshotTheme {
        CloudAccountHostingContent(
            serverSelectionState =
                ServerSelectionState(selectedPreset = ServerPreset.CUSTOM, customServerUrl = "https://notes.example.org"),
            onSelectServerPreset = {},
            onCustomServerUrlChange = {},
            onShowCustomServerInfo = {},
            onDone = {},
            onBack = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S02c_CloudAccountPlans() {
    ScreenshotTheme {
        CloudAccountPlansContent(onDone = {}, onBack = {})
    }
}

// ─── Sign In ────────────────────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S03_CloudAccountSignIn() {
    ScreenshotTheme {
        CloudAccountSignInContent(
            username = "",
            onUsernameChange = {},
            onSignIn = {},
            onAccountRecovery = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onBack = {},
            serverDisplayName = "LogDate Cloud",
            serverHandleDomain = "logdate.app",
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S04_CloudAccountSignInFilled() {
    ScreenshotTheme {
        CloudAccountSignInContent(
            username = "alex_j",
            onUsernameChange = {},
            onSignIn = {},
            onAccountRecovery = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onBack = {},
            serverDisplayName = "LogDate Cloud",
            serverHandleDomain = "logdate.app",
            isSigningIn = false,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S05_CloudAccountSignInLoading() {
    ScreenshotTheme {
        CloudAccountSignInContent(
            username = "alex_j",
            onUsernameChange = {},
            onSignIn = {},
            onAccountRecovery = {},
            onPrivacyPolicy = {},
            onTermsOfService = {},
            onBack = {},
            serverDisplayName = "LogDate Cloud",
            serverHandleDomain = "logdate.app",
            isSigningIn = true,
        )
    }
}

// ─── Account Creation Final ─────────────────────────────────────────────────────

@PreviewTest
@ScreenshotPreviewMatrix
@Preview(name = "Phone 200% text", showBackground = true, device = PHONE, fontScale = 2f)
@Preview(name = "Phone RTL", showBackground = true, device = PHONE, locale = "ar")
@Composable
fun S06_CloudAccountCreationFinal() {
    ScreenshotTheme {
        PasskeyAccountCreationFinalContent(
            displayName = "Alex Johnson",
            username = "alex_j",
            stepNumber = 2,
            stepCount = 2,
            onCreateAccount = {},
            onBack = {},
            isCreatingAccount = false,
            errorMessage = null,
            onClearError = {},
            isPasskeySupported = true,
            serverDisplayName = "LogDate Cloud",
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S07_CloudAccountCreationFinalCreating() {
    ScreenshotTheme {
        PasskeyAccountCreationFinalContent(
            displayName = "Alex Johnson",
            username = "alex_j",
            stepNumber = 2,
            stepCount = 2,
            onCreateAccount = {},
            onBack = {},
            isCreatingAccount = true,
            errorMessage = null,
            onClearError = {},
            isPasskeySupported = true,
            serverDisplayName = "LogDate Cloud",
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S08_CloudAccountCreationFinalError() {
    ScreenshotTheme {
        PasskeyAccountCreationFinalContent(
            displayName = "Alex Johnson",
            username = "alex_j",
            stepNumber = 2,
            stepCount = 2,
            onCreateAccount = {},
            onBack = {},
            isCreatingAccount = false,
            errorMessage = "Failed to create passkey. Please try again.",
            onClearError = {},
            isPasskeySupported = true,
            serverDisplayName = "LogDate Cloud",
        )
    }
}
