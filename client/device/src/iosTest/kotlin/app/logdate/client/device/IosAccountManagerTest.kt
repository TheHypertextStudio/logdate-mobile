package app.logdate.client.device

import app.logdate.shared.model.LogDateAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class IosAccountManagerTest {
    @Test
    fun `pending authorization rejects account creation`() =
        runTest {
            val result =
                IosAccountManager().addAccount(
                    account = LogDateAccount(username = "new-user", displayName = "New User"),
                    accessToken = "new-access-token",
                    refreshToken = "new-refresh-token",
                    backendUrl = "https://cloud.logdate.app",
                )

            assertTrue(result.isFailure, "IdentityFoundationPending must reject platform account writes")
        }

    @Test
    fun `pending authorization rejects account enumeration`() =
        runTest {
            val result = IosAccountManager().getStoredAccounts()

            assertTrue(result.isFailure, "Pending authorization is not an authoritative empty account list")
        }
}
