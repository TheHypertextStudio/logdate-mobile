@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.integration.e2e.journeys

import app.logdate.client.networking.DeviceEnrollmentApiClient
import app.logdate.client.networking.DeviceEnrollmentApiException
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.harness.withServerClientHarness
import app.logdate.shared.config.DefaultLogDateConfigRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceEnrollmentClientServerE2ETest {
    @Test
    fun `phone rejection stays visible until the Mac claims it`() =
        runTest {
            withServerClientHarness {
                val config = DefaultLogDateConfigRepository(initialBackendUrl = baseUrl.removeSuffix("/api/v1"))
                val enrollmentClient = DeviceEnrollmentApiClient(httpClient, config)
                val owner = apiClient.createAccountWithSyntheticPasskey("reject_${Random.nextInt(100000, 999999)}").data
                val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
                val claimSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 40).toByte() })
                val request =
                    enrollmentClient.createFromPhone(
                        deviceName = "Alice's Mac",
                        publicKey = publicKey,
                        claimSecret = claimSecret,
                        confirmationCode = "413827",
                        accessToken = owner.tokens.accessToken,
                    )

                enrollmentClient.reject(request.id, owner.tokens.accessToken)

                val status = enrollmentClient.get(request.id, owner.tokens.accessToken)
                assertEquals("rejected", status.status)

                suspend fun claim() =
                    httpClient.post("$baseUrl/device-enrollments/claim") {
                        header("X-LogDate-Claim-Secret", claimSecret)
                    }
                val rejected = claim()
                assertEquals(HttpStatusCode.OK, rejected.status)
                assertEquals(
                    "rejected",
                    Json
                        .parseToJsonElement(rejected.bodyAsText())
                        .jsonObject
                        .getValue("status")
                        .jsonPrimitive.content,
                )
                assertEquals(HttpStatusCode.OK, claim().status)
            }
        }

    @Test
    fun `Android approval makes one encrypted Mac transfer available to its claim`() =
        runTest {
            withServerClientHarness {
                val config = DefaultLogDateConfigRepository(initialBackendUrl = baseUrl.removeSuffix("/api/v1"))
                val enrollmentClient = DeviceEnrollmentApiClient(httpClient, config)
                val owner = apiClient.createAccountWithSyntheticPasskey("mac_${Random.nextInt(100000, 999999)}").data
                val other = apiClient.createAccountWithSyntheticPasskey("other_${Random.nextInt(100000, 999999)}").data
                val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
                val claimSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 40).toByte() })
                val request =
                    enrollmentClient.createFromPhone(
                        deviceName = "Alice's Mac",
                        publicKey = publicKey,
                        claimSecret = claimSecret,
                        confirmationCode = "413827",
                        accessToken = owner.tokens.accessToken,
                    )
                assertEquals(publicKey, request.publicKey)
                assertEquals("413827", request.confirmationCode)
                assertEquals("pending", request.status)
                val retried =
                    enrollmentClient.createFromPhone(
                        deviceName = "Alice's Mac",
                        publicKey = publicKey,
                        claimSecret = claimSecret,
                        confirmationCode = "413827",
                        accessToken = owner.tokens.accessToken,
                    )
                assertEquals(request.id, retried.id)

                val otherAccountFailure =
                    assertFailsWith<DeviceEnrollmentApiException> {
                        enrollmentClient.get(request.id, other.tokens.accessToken)
                    }
                assertEquals(404, otherAccountFailure.status)
                assertEquals(request, enrollmentClient.get(request.id, owner.tokens.accessToken))

                suspend fun claim() =
                    httpClient.post("$baseUrl/device-enrollments/claim") {
                        header("X-LogDate-Claim-Secret", claimSecret)
                    }

                val pending = claim()
                assertEquals(HttpStatusCode.OK, pending.status)
                assertEquals(
                    "pending",
                    Json
                        .parseToJsonElement(pending.bodyAsText())
                        .jsonObject
                        .getValue("status")
                        .jsonPrimitive.content,
                )
                enrollmentClient.approve(request.id, request.confirmationCode, "opaque-encrypted-key-and-session", owner.tokens.accessToken)
                val approved = claim()
                assertEquals(HttpStatusCode.OK, approved.status)
                val payload = Json.parseToJsonElement(approved.bodyAsText()).jsonObject
                assertEquals(owner.account.id.toString(), payload.getValue("accountId").jsonPrimitive.content)
                assertEquals("approved", payload.getValue("status").jsonPrimitive.content)
                assertEquals("opaque-encrypted-key-and-session", payload.getValue("encryptedEnvelope").jsonPrimitive.content)
                assertEquals(payload, Json.parseToJsonElement(claim().bodyAsText()).jsonObject)
                val consumed =
                    httpClient.post("$baseUrl/device-enrollments/${request.id}/consume") {
                        header("Authorization", "Bearer ${owner.tokens.accessToken}")
                    }
                assertEquals(HttpStatusCode.OK, consumed.status)
                assertEquals(HttpStatusCode.NotFound, claim().status)
            }
        }
}
