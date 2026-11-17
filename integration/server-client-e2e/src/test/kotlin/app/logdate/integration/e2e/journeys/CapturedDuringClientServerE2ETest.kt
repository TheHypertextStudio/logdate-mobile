package app.logdate.integration.e2e.journeys

import app.logdate.client.sync.cloud.ContentUpdateRequest
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.withServerClientHarness
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The server keeps the encrypted capture activity it was given beside the capture device, and an update
 * from an app version that does not send it leaves it in place.
 */
class CapturedDuringClientServerE2ETest {
    @Test
    fun `the capture activity survives upload and an update that does not send it`() =
        runTest {
            withServerClientHarness {
                val token =
                    apiClient
                        .createAccountWithSyntheticPasskey("device_${Random.nextInt(100000, 999999)}")
                        .data.tokens.accessToken
                apiClient
                    .uploadContent(
                        token,
                        ContentUploadRequest(
                            id = "walk-capture",
                            type = "AUDIO",
                            content = null,
                            mediaUri = "voice.m4a",
                            durationMs = 4_000,
                            createdAt = 1000,
                            lastUpdated = 1000,
                            capturedOn = SEALED_DEVICE,
                            capturedDuring = SEALED_ACTIVITY,
                        ),
                    ).getOrThrow()
                val uploaded =
                    apiClient
                        .getContentChanges(token, 0)
                        .getOrThrow()
                        .changes
                        .single()
                assertEquals(SEALED_DEVICE, uploaded.capturedOn)
                assertEquals(SEALED_ACTIVITY, uploaded.capturedDuring)

                apiClient
                    .updateContent(token, "walk-capture", ContentUpdateRequest(lastUpdated = 2000, caption = "At the station"))
                    .getOrThrow()

                val updated =
                    apiClient
                        .getContentChanges(token, 0)
                        .getOrThrow()
                        .changes
                        .single()
                assertEquals(SEALED_DEVICE, updated.capturedOn)
                assertEquals(SEALED_ACTIVITY, updated.capturedDuring)
                assertEquals("At the station", updated.caption)
            }
        }

    private companion object {
        /** The server never reads the value, so any opaque string stands in for the app's ciphertext. */
        const val SEALED_DEVICE = "LDSE2:sealed-device"
        const val SEALED_ACTIVITY = "LDSE2:sealed-activity"
    }
}
