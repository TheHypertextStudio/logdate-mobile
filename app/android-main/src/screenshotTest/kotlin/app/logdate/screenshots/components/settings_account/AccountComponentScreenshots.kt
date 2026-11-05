@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:package-name")

package app.logdate.screenshots.components.settings_account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.feature.core.settings.account.AccountContent
import app.logdate.feature.core.settings.account.AccountDestinations
import app.logdate.feature.core.settings.account.AccountHeader
import app.logdate.feature.core.settings.account.AccountUiState
import app.logdate.feature.core.settings.account.ConnectedServerInfo
import app.logdate.feature.core.settings.account.EmailRow
import app.logdate.feature.core.settings.account.ServerHealth
import app.logdate.feature.core.settings.account.ServerRow
import app.logdate.feature.core.settings.account.SignInSummary
import app.logdate.feature.core.settings.account.delete.DeleteAccountContent
import app.logdate.feature.core.settings.account.delete.DeleteAccountUiState
import app.logdate.feature.core.settings.account.hosting.HostingContent
import app.logdate.feature.core.settings.account.hosting.HostingUiState
import app.logdate.feature.core.settings.account.recovery.PhraseRevealState
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseContent
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseUiState
import app.logdate.feature.core.settings.account.signin.LinkedProviderRow
import app.logdate.feature.core.settings.account.signin.PasskeyRow
import app.logdate.feature.core.settings.account.signin.SignInMethodsContent
import app.logdate.feature.core.settings.account.signin.SignInMethodsUiState
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.common.formatting.LocalToday
import com.android.tools.screenshot.PreviewTest
import kotlinx.datetime.LocalDate

// ─── Account Settings ───────────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun AccountSettings_Default() {
    ScreenshotTheme {
        AccountContent(
            state =
                AccountUiState.SignedIn(
                    header = AccountHeader(displayName = "Alex Rivera", username = "alex"),
                    signIn = SignInSummary.Known(passkeyCount = 2, linkedProviders = listOf(LinkedSignInProvider.Kind.GOOGLE)),
                    hasRecoveryPhrase = true,
                    email = EmailRow(address = "alex@example.com", isVerified = true, canVerify = false),
                    server = ServerRow(name = "LogDate Cloud", host = "cloud.logdate.app", isLogDateCloud = true),
                    isSigningOut = false,
                ),
            destinations = AccountDestinations({}, {}, {}, {}, {}, {}, {}, {}),
            onOpenEmailVerification = {},
            onSignOut = {},
        )
    }
}

// ─── Account subpages ───────────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun SignInMethods() {
    ScreenshotTheme {
        CompositionLocalProvider(LocalToday provides LocalDate(2026, 9, 23)) {
            SignInMethodsContent(
                state =
                    SignInMethodsUiState.Loaded(
                        passkeys =
                            listOf(
                                PasskeyRow("passkey-pixel", "Pixel 9", LocalDate(2026, 3, 12), LocalDate(2026, 9, 23), canRemove = true),
                                PasskeyRow("passkey-mac", "MacBook Pro", LocalDate(2026, 9, 22), null, canRemove = true),
                                PasskeyRow("passkey-old", null, LocalDate(2025, 11, 2), LocalDate(2026, 1, 4), canRemove = true),
                            ),
                        linkedProviders =
                            listOf(LinkedProviderRow(LinkedSignInProvider.Kind.GOOGLE, "alex@example.com", LocalDate(2026, 9, 1))),
                        canAddPasskey = true,
                    ),
                onBack = {},
                onRetry = {},
                onAddPasskey = {},
                onRemovePasskey = {},
            )
        }
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun SignInMethods_OnlyMethod() {
    ScreenshotTheme {
        CompositionLocalProvider(LocalToday provides LocalDate(2026, 9, 23)) {
            SignInMethodsContent(
                state =
                    SignInMethodsUiState.Loaded(
                        passkeys = listOf(PasskeyRow("passkey-pixel", "Pixel 9", LocalDate(2026, 9, 23), null, canRemove = false)),
                        linkedProviders = emptyList(),
                        canAddPasskey = true,
                    ),
                onBack = {},
                onRetry = {},
                onAddPasskey = {},
                onRemovePasskey = {},
            )
        }
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun RecoveryPhrase_Revealed() {
    ScreenshotTheme {
        RecoveryPhraseContent(
            state =
                RecoveryPhraseUiState.Available(
                    reveal =
                        PhraseRevealState.Revealed(
                            listOf(
                                "orbit",
                                "canvas",
                                "meadow",
                                "lantern",
                                "harbor",
                                "velvet",
                                "summit",
                                "pepper",
                                "gravel",
                                "whisper",
                                "copper",
                                "tundra",
                            ),
                        ),
                ),
            onBack = {},
            onReveal = {},
            onHide = {},
            onEnterPhrase = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun RecoveryPhrase_NotOnThisDevice() {
    ScreenshotTheme {
        RecoveryPhraseContent(
            state = RecoveryPhraseUiState.NotOnThisDevice,
            onBack = {},
            onReveal = {},
            onHide = {},
            onEnterPhrase = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Hosting() {
    ScreenshotTheme {
        HostingContent(
            state =
                HostingUiState(
                    server =
                        ConnectedServerInfo(
                            origin = "https://cloud.logdate.app",
                            displayName = "LogDate Cloud",
                            isLogDateCloud = true,
                            publishesIdentityChanges = false,
                        ),
                    health = ServerHealth.Reachable("1.4.0"),
                ),
            onBack = {},
            onCheckAgain = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun DeleteAccount() {
    ScreenshotTheme {
        DeleteAccountContent(
            state = DeleteAccountUiState(serverName = "LogDate Cloud"),
            onBack = {},
            onEraseThisDeviceChange = {},
            onDelete = {},
        )
    }
}
