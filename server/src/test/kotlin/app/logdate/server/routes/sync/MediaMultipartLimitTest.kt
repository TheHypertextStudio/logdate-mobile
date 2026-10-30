package app.logdate.server.routes.sync

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the size limit on the legacy multipart media upload: the file part is never read past the
 * limit, and a request that declares a larger body is refused before it is read.
 */
class MediaMultipartLimitTest {
    private fun upload(size: Int) =
        formData {
            append("contentId", "content-1")
            append("fileName", "photo.jpg")
            append("mimeType", "image/jpeg")
            append("sizeBytes", size.toString())
            append("deviceId", "device-1")
            append(
                "data",
                ByteArray(size),
                Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"")
                },
            )
        }

    private fun io.ktor.server.testing.ApplicationTestBuilder.installUpload(maxDataBytes: Long) {
        application {
            install(ContentNegotiation) { json() }
            routing {
                post("/upload") {
                    val parsed = call.receiveMediaMultipartUpload(maxDataBytes) ?: return@post
                    call.respond(HttpStatusCode.OK, parsed.sizeBytes.toString())
                }
            }
        }
    }

    @Test
    fun `a file at the limit is accepted and one byte more is refused`() =
        testApplication {
            installUpload(maxDataBytes = 1_024)

            assertEquals(HttpStatusCode.OK, client.submitFormWithBinaryData("/upload", upload(1_024)).status)

            val refused = client.submitFormWithBinaryData("/upload", upload(1_025))
            assertEquals(HttpStatusCode.PayloadTooLarge, refused.status)
            assertTrue("MEDIA_TOO_LARGE" in refused.bodyAsText())
        }

    @Test
    fun `a request that declares a body far over the limit is refused before it is read`() =
        testApplication {
            installUpload(maxDataBytes = 1_024)

            val refused =
                client.post("/upload") {
                    header(
                        HttpHeaders.ContentType,
                        ContentType.MultiPart.FormData
                            .withParameter("boundary", "x")
                            .toString(),
                    )
                    setBody(ByteArray(200 * 1024))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, refused.status)
        }
}
