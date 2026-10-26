package app.logdate.server.routes.sync

import app.logdate.server.routes.support.authHeader
import app.logdate.server.routes.support.configureInMemorySyncApp
import app.logdate.server.routes.support.contentUpdateBody
import app.logdate.server.routes.support.contentUploadBody
import app.logdate.server.routes.support.journalUpdateBody
import app.logdate.server.routes.support.journalUploadBody
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the lifecycle management for content and journal records in the Sync API.
 *
 * This suite verifies the consistency of resource identifiers, the correct handling of
 * version-based concurrency conflicts during updates, and the proper processing of
 * full and partial (PATCH) updates for both content and journal collections.
 */
class SyncContentAndJournalLifecycleTest {
    @Test
    fun `encrypted transcript survives changes and updates from older clients`() =
        testApplication {
            val tokenService = configureInMemorySyncApp()
            val auth = authHeader(tokenService)
            val path = "/api/v1/contents/audio-transcript"
            val upload =
                """
                {"id":"audio-transcript","type":"AUDIO","content":null,"mediaUri":"audio.m4a","durationMs":4000,
                 "createdAt":1,"lastUpdated":2,"transcript":"LDSE2:opaque-transcript"}
                """.trimIndent()
            assertEquals(
                HttpStatusCode.Created,
                client
                    .put(path) {
                        header(HttpHeaders.Authorization, auth)
                        contentType(ContentType.Application.Json)
                        setBody(upload)
                    }.status,
            )
            assertTrue(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("\"durationMs\":4000"))
            assertTrue(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("LDSE2:opaque-transcript"))
            assertEquals(
                HttpStatusCode.OK,
                client
                    .patch(path) {
                        header(HttpHeaders.Authorization, auth)
                        contentType(ContentType.Application.Json)
                        setBody("""{"lastUpdated":3,"mediaUri":"audio.m4a"}""")
                    }.status,
            )
            assertTrue(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("\"durationMs\":4000"))
            assertEquals(
                HttpStatusCode.OK,
                client
                    .put(path) {
                        header(HttpHeaders.Authorization, auth)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {"id":"audio-transcript","type":"AUDIO","content":null,
                             "mediaUri":"audio.m4a","durationMs":4000,"createdAt":1,"lastUpdated":4}
                            """.trimIndent(),
                        )
                    }.status,
            )
            assertTrue(
                client
                    .get("/api/v1/contents?since=0") {
                        header(HttpHeaders.Authorization, auth)
                    }.bodyAsText()
                    .contains("LDSE2:opaque-transcript"),
            )
            assertEquals(
                HttpStatusCode.OK,
                client
                    .patch(path) {
                        header(HttpHeaders.Authorization, auth)
                        contentType(ContentType.Application.Json)
                        setBody("""{"lastUpdated":5,"mediaUri":"replacement.m4a"}""")
                    }.status,
            )
            assertFalse(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("LDSE2:opaque-transcript"))
            client.put(path) {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody(upload)
            }
            client.patch(path) {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"lastUpdated":7,"durationMs":5000}""")
            }
            assertFalse(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("LDSE2:opaque-transcript"))
            client.put(path) {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody(upload)
            }
            assertEquals(
                HttpStatusCode.OK,
                client
                    .put(path) {
                        header(HttpHeaders.Authorization, auth)
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"id":"audio-transcript","type":"AUDIO","content":null,
                              "mediaUri":"other.m4a","createdAt":1,"lastUpdated":6}""",
                        )
                    }.status,
            )
            assertFalse(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("LDSE2:opaque-transcript"))
        }

    @Test
    fun `conditional journal creation preserves a newer title`() =
        testApplication {
            val tokenService = configureInMemorySyncApp()
            val auth = authHeader(tokenService)
            val path = "/api/v1/journals/conditional-mac-journal"

            val first =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.IfNoneMatch, "*")
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "conditional-mac-journal", title = "Mac title"))
                }
            assertEquals(HttpStatusCode.Created, first.status)

            val newer =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "conditional-mac-journal", title = "Phone title"))
                }
            assertEquals(HttpStatusCode.OK, newer.status)

            val retry =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.IfNoneMatch, "*")
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "conditional-mac-journal", title = "Mac title"))
                }
            assertEquals(HttpStatusCode.PreconditionFailed, retry.status)
            assertTrue(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("\"title\":\"Phone title\""))

            assertEquals(HttpStatusCode.NoContent, client.delete(path) { header(HttpHeaders.Authorization, auth) }.status)
            val recreate =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.IfNoneMatch, "*")
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "conditional-mac-journal", title = "Keep my Mac journal"))
                }
            assertEquals(HttpStatusCode.Conflict, recreate.status)
            assertTrue(recreate.bodyAsText().contains("DELETION_CONFLICT"))
            assertEquals(HttpStatusCode.NotFound, client.get(path) { header(HttpHeaders.Authorization, auth) }.status)
        }

    @Test
    fun `conditional content creation preserves newer edits and rejects deleted identity reuse`() =
        testApplication {
            val tokenService = configureInMemorySyncApp()
            val auth = authHeader(tokenService)
            val path = "/api/v1/contents/conditional-mac-entry"

            val first =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.IfNoneMatch, "*")
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "conditional-mac-entry", content = "Mac text"))
                }
            assertEquals(HttpStatusCode.Created, first.status)

            val newer =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "conditional-mac-entry", content = "Phone edit"))
                }
            assertEquals(HttpStatusCode.OK, newer.status)

            val retry =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.IfNoneMatch, "*")
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "conditional-mac-entry", content = "Mac text"))
                }
            assertEquals(HttpStatusCode.PreconditionFailed, retry.status)
            assertTrue(client.get(path) { header(HttpHeaders.Authorization, auth) }.bodyAsText().contains("\"content\":\"Phone edit\""))

            val deletion = client.delete(path) { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.NoContent, deletion.status)
            val recreate =
                client.put(path) {
                    header(HttpHeaders.Authorization, auth)
                    header(HttpHeaders.IfNoneMatch, "*")
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "conditional-mac-entry", content = "Keep my Mac edit"))
                }
            assertEquals(HttpStatusCode.Conflict, recreate.status)
            assertTrue(recreate.bodyAsText().contains("DELETION_CONFLICT"))
            assertEquals(HttpStatusCode.NotFound, client.get(path) { header(HttpHeaders.Authorization, auth) }.status)
        }

    @Test
    fun `content and journal endpoints enforce id consistency and version conflict behavior`() =
        testApplication {
            val tokenService = configureInMemorySyncApp()
            val auth = authHeader(tokenService)

            val createContent =
                client.put("/api/v1/contents/content-branch-1") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "content-branch-1", content = "one"))
                }
            assertEquals(HttpStatusCode.Created, createContent.status)

            val updateContent =
                client.put("/api/v1/contents/content-branch-1") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "content-branch-1", content = "two"))
                }
            assertEquals(HttpStatusCode.OK, updateContent.status)

            val contentMismatch =
                client.put("/api/v1/contents/content-path") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(contentUploadBody(id = "content-body"))
                }
            assertEquals(HttpStatusCode.BadRequest, contentMismatch.status)

            val patchContent =
                client.patch("/api/v1/contents/content-patch-missing") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(
                        contentUpdateBody(
                            content = "patched",
                            mediaUri = null,
                            lastUpdated = 123L,
                            deviceId = "dev-a",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, patchContent.status)

            val createJournal =
                client.put("/api/v1/journals/journal-branch-1") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "journal-branch-1", title = "A"))
                }
            assertEquals(HttpStatusCode.Created, createJournal.status)

            val updateJournal =
                client.put("/api/v1/journals/journal-branch-1") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "journal-branch-1", title = "B"))
                }
            assertEquals(HttpStatusCode.OK, updateJournal.status)

            val journalMismatch =
                client.put("/api/v1/journals/journal-path") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(journalUploadBody(id = "journal-body"))
                }
            assertEquals(HttpStatusCode.BadRequest, journalMismatch.status)

            val patchJournal =
                client.patch("/api/v1/journals/journal-patch-missing") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(
                        journalUpdateBody(
                            title = "patched",
                            description = "desc",
                            lastUpdated = 222L,
                            deviceId = "dev-a",
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, patchJournal.status)

            val journalPatchConflict =
                client.patch("/api/v1/journals/journal-branch-1") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(
                        journalUpdateBody(
                            title = "conflict",
                            description = "conflict",
                            lastUpdated = 333L,
                            deviceId = "dev-a",
                            knownVersion = 0L,
                        ),
                    )
                }
            assertEquals(HttpStatusCode.Conflict, journalPatchConflict.status)
            assertTrue(journalPatchConflict.bodyAsText().contains("CONFLICT"))

            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .delete("/api/v1/contents/content-branch-1") {
                        header(HttpHeaders.Authorization, auth)
                    }.status,
            )
            assertEquals(
                HttpStatusCode.NoContent,
                client
                    .delete("/api/v1/journals/journal-branch-1") {
                        header(HttpHeaders.Authorization, auth)
                    }.status,
            )

            val staleContentEdit =
                client.patch("/api/v1/contents/content-branch-1") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(contentUpdateBody(content = "offline Mac edit", knownVersion = 1L))
                }
            assertEquals(HttpStatusCode.Conflict, staleContentEdit.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/contents/content-branch-1") { header(HttpHeaders.Authorization, auth) }.status,
            )

            val staleJournalEdit =
                client.patch("/api/v1/journals/journal-branch-1") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(journalUpdateBody(title = "offline title", knownVersion = 1L))
                }
            assertEquals(HttpStatusCode.Conflict, staleJournalEdit.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/journals/journal-branch-1") { header(HttpHeaders.Authorization, auth) }.status,
            )
        }
}
