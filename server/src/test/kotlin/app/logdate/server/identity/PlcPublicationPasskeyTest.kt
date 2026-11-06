package app.logdate.server.identity

import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PlcPublicationPasskeyTest {
    private val account = Uuid.random()
    private val review = Uuid.random()
    private val otherReview = Uuid.random()
    private val credential = mockk<PasskeyAuthenticationResponse>()
    private val options = PasskeyAuthenticationOptions("challenge", "logdate.app", emptyList(), 300_000L)
    private val webAuthn = mockk<WebAuthnPasskeyService>()

    @Test
    fun `confirmation is bound to a publication review and its account`() =
        runBlocking<Unit> {
            every { webAuthn.strictVerificationEnabled } returns true
            coEvery { webAuthn.getUserCredentials(account) } returns listOf("account-key")
            coEvery { webAuthn.generateBoundAuthenticationOptions("plc_publish", review) } returns options
            coEvery { webAuthn.verifyBoundAuthentication("challenge", credential, "plc_publish", review) } returns
                WebAuthnPasskeyService.AuthenticationResult(success = true, userId = account)
            coEvery { webAuthn.verifyBoundAuthentication("challenge", credential, "plc_publish", otherReview) } returns
                WebAuthnPasskeyService.AuthenticationResult(success = false)

            val confirmation = PlcPublicationPasskey(webAuthn)

            assertEquals(options.copy(allowCredentials = listOf("account-key")), confirmation.begin(account, review))
            assertFalse(confirmation.verify(account, otherReview, "challenge", credential))
            assertFalse(confirmation.verify(Uuid.random(), review, "challenge", credential))
            assertTrue(confirmation.verify(account, review, "challenge", credential))
            coVerify(exactly = 0) { webAuthn.verifyStepUp(any(), any(), any()) }
            coVerify(exactly = 0) { webAuthn.redeemStepUpGrant(any(), any()) }
        }

    @Test
    fun `development passkey verification cannot authorize publication`() {
        every { webAuthn.strictVerificationEnabled } returns false

        assertFailsWith<IllegalArgumentException> { PlcPublicationPasskey(webAuthn) }
        coVerify(exactly = 0) { webAuthn.generateBoundAuthenticationOptions(any(), any()) }
    }
}
