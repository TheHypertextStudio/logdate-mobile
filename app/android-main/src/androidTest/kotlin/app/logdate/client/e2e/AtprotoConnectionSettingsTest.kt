package app.logdate.client.e2e

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.logdate.client.device.auth.AtprotoConnectionBinding
import app.logdate.client.repository.account.AccountConnectionProblem
import app.logdate.client.repository.account.AccountConnectionState
import app.logdate.client.repository.account.AtprotoConnectionRepository
import app.logdate.feature.core.settings.account.signin.AndroidAccountConnection
import app.logdate.feature.core.settings.account.signin.AtprotoConnectionSection
import app.logdate.feature.core.settings.account.signin.AtprotoConnectionViewModel
import app.logdate.feature.core.settings.account.signin.PasskeyRow
import app.logdate.feature.core.settings.account.signin.SignInMethodsContent
import app.logdate.feature.core.settings.account.signin.SignInMethodsUiState
import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic callbacks prove native Android composition; guarded account/controller tests own session authority. */
@RunWith(AndroidJUnit4::class)
class AtprotoConnectionSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disconnectRequiresConfirmationAndStatusNeverPresentsCredentials() {
        var connection by mutableStateOf(connected())
        var confirmations = 0
        var refreshes = 0
        compose.setContent {
            LogDateTheme(dynamicColor = false) {
                SignInMethodsContent(
                    state = account(),
                    onBack = {},
                    onRetry = {},
                    onAddPasskey = {},
                    onRemovePasskey = {},
                    connectionSection = {
                        AtprotoConnectionSection(
                            connection,
                            onRequestDisconnect = { connection = connection.copy(confirmingDisconnect = true) },
                            onConfirmDisconnect = {
                                confirmations += 1
                                connection =
                                    connection.copy(confirmingDisconnect = false, snapshot = snapshot(AtprotoConnectionState.DISCONNECTED))
                            },
                            onCancelConfirmation = { connection = connection.copy(confirmingDisconnect = false) },
                            onReconnect = {},
                            onRefresh = { refreshes += 1 },
                            onCancelPending = {},
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("Ways to sign in").assertIsDisplayed()
        compose.onNodeWithText("Account connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check status").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0, confirmations)
            assertEquals(1, refreshes)
        }
        compose.onNodeWithText("Disconnect…").performScrollTo().performClick()
        compose.onNodeWithText("Disconnect this account?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, confirmations) }
        compose.onNodeWithText("Disconnect…").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun androidAccountExtensionTracksOwningSessionWithoutPresentingCredentials() {
        val repository = UiConnectionRepository()
        val owners =
            MutableStateFlow<AtprotoConnectionBinding?>(
                AtprotoConnectionBinding(
                    "https://cloud.logdate.app",
                    "00000000-0000-4000-8000-000000000001",
                    "A".repeat(43),
                ),
            )
        val store = ViewModelStore()
        lateinit var model: AtprotoConnectionViewModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            model =
                ViewModelProvider(
                    store,
                    object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            require(modelClass == AtprotoConnectionViewModel::class.java)
                            @Suppress("UNCHECKED_CAST")
                            return AtprotoConnectionViewModel(repository, owners) as T
                        }
                    },
                )[AtprotoConnectionViewModel::class.java]
        }
        try {
            compose.setContent {
                val extension = AndroidAccountConnection(model)
                LogDateTheme(dynamicColor = false) {
                    SignInMethodsContent(
                        state = account(),
                        onBack = {},
                        onRetry = {},
                        onAddPasskey = {},
                        onRemovePasskey = {},
                        connectionSection = extension,
                    )
                }
            }
            compose.onNodeWithText("Ways to sign in").assertIsDisplayed()
            compose.waitUntil(
                5000,
            ) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasText("Account connection"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Account connection").performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, repository.confirmations)
                owners.value = null
            }
            compose.waitUntil(
                5000,
            ) {
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasText("Account connection"),
                    ).fetchSemanticsNodes()
                    .isEmpty()
            }
            compose.onNodeWithText("Account connection").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, repository.confirmations) }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun meaningfulConnectionStatesRemainReadableInDarkLargeTextAndRtl() {
        var variant by mutableStateOf(Variant("connected", connected()))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (variant.largeText) 2f else 1f),
                LocalLayoutDirection provides if (variant.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                LogDateTheme(dynamicColor = false, darkTheme = variant.dark) {
                    SignInMethodsContent(
                        state = account(),
                        onBack = {},
                        onRetry = {},
                        onAddPasskey = {},
                        onRemovePasskey = {},
                        connectionSection = {
                            AtprotoConnectionSection(variant.state, {}, {}, {}, {}, {}, {})
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Ways to sign in").assertIsDisplayed()
        val variants =
            listOf(
                Variant("connected", connected()),
                Variant("expired-dark", connected().copy(snapshot = snapshot(AtprotoConnectionState.EXPIRED)), dark = true),
                Variant("revoked", connected().copy(snapshot = snapshot(AtprotoConnectionState.REVOKED))),
                Variant("reconnect-required", connected().copy(snapshot = snapshot(AtprotoConnectionState.RECONNECT_REQUIRED))),
                Variant("disconnected", connected().copy(snapshot = snapshot(AtprotoConnectionState.DISCONNECTED))),
                Variant("offline", connected().copy(observationFailed = true, problem = AccountConnectionProblem.UNAVAILABLE)),
                Variant("large-text", connected(), largeText = true),
                Variant("rtl", connected(), rtl = true),
                Variant("changed-connection", connected().copy(problem = AccountConnectionProblem.CHANGED)),
                Variant(
                    "remote-unconfirmed",
                    connected().copy(snapshot = snapshot(AtprotoConnectionState.DISCONNECTED), remoteRevocationUnconfirmed = true),
                ),
                Variant("newer-connection-preserved", connected().copy(newerConnectionPreserved = true)),
                Variant("missing-passkey", connected().copy(snapshot = snapshot().copy(canDisconnect = false))),
            )
        for (value in variants) {
            compose.runOnIdle { variant = value }
            compose.onNodeWithText("Account connection").performScrollTo().assertIsDisplayed()
            capture(value.name + "-status")
            compose.onNodeWithText("Check status").performScrollTo().assertIsDisplayed()
            capture(value.name + "-actions")
        }
        compose.onNodeWithText("Disconnect…").assertIsNotEnabled()
        compose.runOnIdle { variant = Variant("unsupported", AccountConnectionState()) }
        compose.onNodeWithText("Account connection").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertFalse(device.hasObject(By.textContains("isn't responding")))
        val configuration = instrumentation.targetContext.resources.configuration
        val folder =
            File(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: error("Use a Gradle Managed Device for visual acceptance"),
            )
        folder.mkdirs()
        assertTrue(device.takeScreenshot(File(folder, "account-connection-${configuration.smallestScreenWidthDp}dp-$name.png")))
    }

    private data class Variant(
        val name: String,
        val state: AccountConnectionState,
        val dark: Boolean = false,
        val largeText: Boolean = false,
        val rtl: Boolean = false,
    )

    private fun account() = SignInMethodsUiState.Loaded(listOf(PasskeyRow("AQ", "Pixel 9", null, null, false)), emptyList(), true)

    private fun connected() = AccountConnectionState(available = true, snapshot = snapshot())

    private fun snapshot(state: AtprotoConnectionState = AtprotoConnectionState.CONNECTED) =
        AtprotoConnectionSnapshot(state, 1, "2026-10-27T00:00:00Z", true, state == AtprotoConnectionState.CONNECTED)

    private class UiConnectionRepository : AtprotoConnectionRepository {
        private val current = MutableStateFlow(AccountConnectionState())
        override val state = current.asStateFlow()
        var confirmations = 0

        override suspend fun refresh() {
            current.value =
                AccountConnectionState(
                    available = true,
                    snapshot = AtprotoConnectionSnapshot(AtprotoConnectionState.CONNECTED, 1, "2026-10-27T00:00:00Z", true, true),
                )
        }

        override suspend fun requestDisconnect() {
            current.value = current.value.copy(confirmingDisconnect = true)
        }

        override suspend fun cancelDisconnectConfirmation() {
            current.value = current.value.copy(confirmingDisconnect = false)
        }

        override suspend fun confirmDisconnect() {
            confirmations += 1
        }

        override suspend fun reconnect(): Result<String> = Result.success("https://pds.example/authorize")

        override suspend fun resume(returnUrl: String): Boolean = false

        override suspend fun cancelPending() = Unit

        override suspend fun invalidate() {
            current.value = AccountConnectionState()
        }
    }
}
