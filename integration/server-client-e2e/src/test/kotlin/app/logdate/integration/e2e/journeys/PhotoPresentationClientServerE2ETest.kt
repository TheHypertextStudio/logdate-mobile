package app.logdate.integration.e2e.journeys

import app.logdate.client.sync.cloud.ContentUpdateRequest
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.withServerClientHarness
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoPresentationClientServerE2ETest {
    @Test
    fun `photo presentation survives upload caption update and style change`() =
        runTest {
            withServerClientHarness {
                val token =
                    apiClient
                        .createAccountWithSyntheticPasskey("photo_${Random.nextInt(100000, 999999)}")
                        .data.tokens.accessToken
                apiClient
                    .uploadContent(
                        token,
                        ContentUploadRequest(
                            id = "photo-style",
                            type = "IMAGE",
                            content = null,
                            mediaUri = "photo.jpg",
                            createdAt = 1000,
                            lastUpdated = 1000,
                            caption = "Our campsite",
                            photoPresentation = "Framed",
                        ),
                    ).getOrThrow()
                assertEquals(
                    "Framed",
                    apiClient
                        .getContentChanges(token, 0)
                        .getOrThrow()
                        .changes
                        .single()
                        .photoPresentation,
                )
                apiClient
                    .updateContent(token, "photo-style", ContentUpdateRequest(lastUpdated = 2000, caption = "At sunset"))
                    .getOrThrow()
                val updated =
                    apiClient
                        .getContentChanges(token, 0)
                        .getOrThrow()
                        .changes
                        .single()
                assertEquals("Framed", updated.photoPresentation)
                assertEquals("At sunset", updated.caption)
                apiClient
                    .updateContent(token, "photo-style", ContentUpdateRequest(lastUpdated = 3000, photoPresentation = "EdgeToEdge"))
                    .getOrThrow()
                assertEquals(
                    "EdgeToEdge",
                    apiClient
                        .getContentChanges(token, 0)
                        .getOrThrow()
                        .changes
                        .single()
                        .photoPresentation,
                )
            }
        }
}
