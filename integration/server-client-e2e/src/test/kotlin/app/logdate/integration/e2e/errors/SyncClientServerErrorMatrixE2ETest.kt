package app.logdate.integration.e2e.errors

import app.logdate.client.sync.cloud.ContentUpdateRequest
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.DeviceId
import app.logdate.integration.e2e.fixtures.assertCloudError
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.fixtures.uploadMediaDeclaringSize
import app.logdate.integration.e2e.harness.withServerClientHarness
import app.logdate.shared.model.sync.VersionConstraint
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test matrix for verifying client-side resilience against server-side sync errors.
 *
 * This suite executes end-to-end scenarios to ensure the sync client correctly interprets
 * and surfaces various HTTP error states from the cloud backend. It covers critical
 * failure modes including authentication expiration (401), concurrent update conflicts (409),
 * payload validation failures (400), and missing resource lookups (404).
 */
class SyncClientServerErrorMatrixE2ETest {
    @Test
    fun `sync endpoints map auth validation conflict and not-found errors`() =
        kotlinx.coroutines.test.runTest {
            withServerClientHarness {
                val username = "sync_err_${Random.nextInt(1000, 9999)}"
                val complete = apiClient.createAccountWithSyntheticPasskey(username)
                val accessToken = complete.data.tokens.accessToken
                val now = 1_700_000_100_000L

                val uploadWithInvalidToken =
                    apiClient.uploadContent(
                        accessToken = "bad",
                        content =
                            ContentUploadRequest(
                                id = "content-err",
                                type = "TEXT",
                                content = "x",
                                mediaUri = null,
                                createdAt = now,
                                lastUpdated = now,
                                deviceId = DeviceId("device-a"),
                            ),
                    )
                assertCloudError(uploadWithInvalidToken, expectedCode = "UNAUTHORIZED", expectedStatus = 401)

                val uploadContent =
                    apiClient.uploadContent(
                        accessToken = accessToken,
                        content =
                            ContentUploadRequest(
                                id = "content-err",
                                type = "TEXT",
                                content = "x",
                                mediaUri = null,
                                createdAt = now,
                                lastUpdated = now,
                                deviceId = DeviceId("device-a"),
                            ),
                    )
                require(uploadContent.isSuccess) { "Expected content upload success before conflict scenario" }

                val updateConflict =
                    apiClient.updateContent(
                        accessToken = accessToken,
                        contentId = "content-err",
                        content =
                            ContentUpdateRequest(
                                content = "conflict",
                                lastUpdated = now + 100,
                                deviceId = DeviceId("device-a"),
                                versionConstraint = VersionConstraint.Known(serverVersion = 0),
                            ),
                    )
                assertCloudError(updateConflict, expectedCode = "CONFLICT", expectedStatus = 409)

                val mediaSizeMismatch =
                    uploadMediaDeclaringSize(accessToken = accessToken, declaredSizeBytes = 99, data = byteArrayOf(1, 2, 3))
                assertEquals(HttpStatusCode.BadRequest, mediaSizeMismatch.status)
                assertTrue(mediaSizeMismatch.bodyAsText().contains("VALIDATION_ERROR"))

                val missingMedia = apiClient.downloadMedia(accessToken = accessToken, mediaId = "missing-media")
                assertCloudError(missingMedia, expectedCode = "NOT_FOUND", expectedStatus = 404)
            }
        }
}
