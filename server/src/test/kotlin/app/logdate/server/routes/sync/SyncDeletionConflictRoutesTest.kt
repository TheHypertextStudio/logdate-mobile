package app.logdate.server.routes.sync

import app.logdate.server.routes.support.authHeader
import app.logdate.server.routes.support.configureInMemorySyncApp
import app.logdate.server.routes.support.mediaUploadMultipartContent
import app.logdate.shared.model.sync.MediaUploadResponse
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncDeletionConflictRoutesTest {
    @Test
    fun `deleted associations reject batch writes and deleted media rejects a repeated upload`() =
        testApplication {
            val auth = authHeader(configureInMemorySyncApp())
            val associationPath = "/api/v1/associations/journal/note"
            assertEquals(HttpStatusCode.NoContent, client.delete(associationPath) { header(HttpHeaders.Authorization, auth) }.status)
            val batch =
                client.post("/api/v1/associations") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"associations":[
                          {"journalId":"journal","contentId":"note","createdAt":999999999999,"deviceId":"device"}
                        ]}""",
                    )
                }
            assertEquals(HttpStatusCode.Conflict, batch.status, batch.bodyAsText())
            assertTrue(batch.bodyAsText().contains("DELETION_CONFLICT"))

            suspend fun upload() =
                client.post("/api/v1/media") {
                    header(HttpHeaders.Authorization, auth)
                    setBody(mediaUploadMultipartContent("note", "audio.m4a", "audio/mp4", byteArrayOf(1), "device"))
                }
            val first = upload()
            assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
            val media = Json.decodeFromString<MediaUploadResponse>(first.bodyAsText())
            assertEquals(
                HttpStatusCode.NoContent,
                client.delete("/api/v1/media/${media.mediaId}") { header(HttpHeaders.Authorization, auth) }.status,
            )
            val rejected = upload()
            assertEquals(HttpStatusCode.Conflict, rejected.status, rejected.bodyAsText())
            assertTrue(rejected.bodyAsText().contains("DELETION_CONFLICT"))
        }

    @Test
    fun `unversioned put and patch cannot resurrect content or journals`() =
        testApplication {
            val auth = authHeader(configureInMemorySyncApp())
            for (collection in listOf("contents", "journals")) {
                val path = "/api/v1/$collection/deleted"
                assertEquals(HttpStatusCode.NoContent, client.delete(path) { header(HttpHeaders.Authorization, auth) }.status)
                val putBody =
                    if (collection == "contents") {
                        """{"id":"deleted","type":"TEXT","content":"edit","mediaUri":null,
                          "createdAt":1,"lastUpdated":9223372036854775806,"deviceId":"device"}"""
                    } else {
                        """{"id":"deleted","title":"edit","description":"","createdAt":1,
                          "lastUpdated":9223372036854775806,"deviceId":"device"}"""
                    }
                val put =
                    client.put(path) {
                        header(HttpHeaders.Authorization, auth)
                        contentType(ContentType.Application.Json)
                        setBody(putBody)
                    }
                assertEquals(HttpStatusCode.Conflict, put.status, put.bodyAsText())
                assertTrue(put.bodyAsText().contains("DELETION_CONFLICT"))
                val patch =
                    client.patch(path) {
                        header(HttpHeaders.Authorization, auth)
                        contentType(ContentType.Application.Json)
                        setBody("""{"lastUpdated":9223372036854775806,"deviceId":"device"}""")
                    }
                assertEquals(HttpStatusCode.Conflict, patch.status, patch.bodyAsText())
                assertTrue(patch.bodyAsText().contains("DELETION_CONFLICT"))
            }
        }
}
