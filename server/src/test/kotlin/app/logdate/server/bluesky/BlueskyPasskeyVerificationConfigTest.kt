package app.logdate.server.bluesky

import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.passkeys.WebAuthnPasskeyService
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BlueskyPasskeyVerificationConfigTest {
    @Test
    fun `Bluesky confirmation refuses lightweight passkey verification`() {
        assertFailsWith<IllegalStateException> {
            BlueskyPasskeyAccountService(
                InMemoryAccountRepository(),
                mockk(),
                mockk(),
                WebAuthnPasskeyService(strictVerificationEnabled = false),
            )
        }
    }
}
