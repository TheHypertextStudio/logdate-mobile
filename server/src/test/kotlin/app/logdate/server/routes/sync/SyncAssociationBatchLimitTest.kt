package app.logdate.server.routes.sync

import app.logdate.server.routes.support.authHeader
import app.logdate.server.routes.support.configureInMemorySyncApp
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the size limit on bulk association uploads and deletes: a request with more links than a
 * batch may carry is refused before any of them are stored.
 */
class SyncAssociationBatchLimitTest {
    private fun links(count: Int): String =
        (1..count).joinToString(prefix = """{"associations":[""", postfix = "]}") {
            """{"journalId":"journal-$it","contentId":"content-$it","createdAt":1,"deviceId":"dev-1"}"""
        }

    private fun deletes(count: Int): String =
        (1..count).joinToString(prefix = """{"associations":[""", postfix = "]}") {
            """{"journalId":"journal-$it","contentId":"content-$it"}"""
        }

    @Test
    fun `an upload of more links than a batch may carry is refused and smaller ones are stored`() =
        testApplication {
            val auth = authHeader(configureInMemorySyncApp())

            val over =
                client.post("/api/v1/associations") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(links(MAX_ASSOCIATION_BATCH + 1))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, over.status)
            assertTrue("BATCH_TOO_LARGE" in over.bodyAsText())

            val small =
                client.post("/api/v1/associations") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(links(3))
                }
            assertEquals(HttpStatusCode.OK, small.status, small.bodyAsText())
        }

    @Test
    fun `a delete of more than a full batch is refused`() =
        testApplication {
            val auth = authHeader(configureInMemorySyncApp())

            val over =
                client.delete("/api/v1/associations") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(deletes(MAX_ASSOCIATION_BATCH + 1))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, over.status)
        }

    @Test
    fun `a body larger than any full batch is refused before it is read`() =
        testApplication {
            val auth = authHeader(configureInMemorySyncApp())

            val over =
                client.post("/api/v1/associations") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(" ".repeat(9 * 1024 * 1024))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, over.status)
        }
}
