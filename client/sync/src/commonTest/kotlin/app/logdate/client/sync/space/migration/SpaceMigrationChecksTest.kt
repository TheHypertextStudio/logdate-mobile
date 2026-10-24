package app.logdate.client.sync.space.migration

import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.shared.model.sync.LegacyInventory
import app.logdate.shared.model.sync.LegacyKindInventory
import app.logdate.shared.model.sync.LegacyRecordKinds
import app.logdate.shared.model.sync.SpaceMigrationDigests
import app.logdate.shared.model.sync.SpaceMigrationErrorCodes
import app.logdate.shared.model.sync.SpaceMigrationPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class SpaceMigrationChecksTest {
    @Test
    fun `the same entries on both sides pass without listing ids`() =
        runTest {
            val ids = setOf("a", "b")
            var listed = false
            comparison(ids) { listed = true }.compare(inventory(ids))
            assertEquals(false, listed)
        }

    @Test
    fun `entries only the server has stop the move and say how many`() =
        runTest {
            val error =
                assertFailsWith<MigrationMismatchException> {
                    comparison(setOf("a")).compare(inventory(setOf("a", "b", "c")))
                }
            assertEquals(JournalMoveProblem.MissingOnThisDevice(JournalDataKind.ENTRIES, 2), error.problem)
        }

    @Test
    fun `entries waiting to upload don't count as missing from the server`() =
        runTest {
            comparison(setOf("a", "new"), waitingCreates = setOf("new")).compare(inventory(setOf("a")))
        }

    @Test
    fun `entries that never reached the server stop the move`() =
        runTest {
            val error = assertFailsWith<MigrationMismatchException> { comparison(setOf("a", "lost")).compare(inventory(setOf("a"))) }
            assertEquals(JournalMoveProblem.NotOnServer(JournalDataKind.ENTRIES, 1), error.problem)
        }

    @Test
    fun `a kind this device doesn't sync stops the move only when the account has some`() =
        runTest {
            val notSynced = LegacyKindCheck(LegacyRecordKinds.MOMENTS, JournalDataKind.MOMENTS, { emptySet() }, syncedHere = { false })
            LegacyComparison(listOf(notSynced)) { emptyList() }
                .compare(LegacyInventory("w", listOf(LegacyKindInventory(LegacyRecordKinds.MOMENTS, 0, digestOf(emptySet())))))
            val error =
                assertFailsWith<MigrationMismatchException> {
                    LegacyComparison(listOf(notSynced)) { emptyList() }
                        .compare(LegacyInventory("w", listOf(LegacyKindInventory(LegacyRecordKinds.MOMENTS, 1, digestOf(setOf("m"))))))
                }
            assertEquals(JournalMoveProblem.NotSyncedHere(JournalDataKind.MOMENTS), error.problem)
        }

    @Test
    fun `server refusals become problems a person can act on`() {
        assertEquals(JournalMoveProblem.AnotherDeviceMoving, problemOf(CloudApiException(SpaceMigrationErrorCodes.HELD_ELSEWHERE, "held")))
        assertEquals(
            JournalMoveProblem.SwitchBackWindowClosed,
            problemOf(CloudApiException(SpaceMigrationErrorCodes.WINDOW_CLOSED, "closed")),
        )
        assertEquals(JournalMoveProblem.StorageFull, problemOf(CloudApiException("QUOTA_EXCEEDED", "full", statusCode = 402)))
        assertEquals(JournalMoveProblem.Offline, problemOf(CloudApiException("SERVER_BUSY", "busy", statusCode = 503)))
        assertEquals(
            JournalMoveProblem.ServerRefused("SPACE_MIGRATION_LEGACY_CHANGED"),
            problemOf(CloudApiException("SPACE_MIGRATION_LEGACY_CHANGED", "x")),
        )
        val mismatch = JournalMoveProblem.CopyDiffers(JournalDataKind.DRAFTS, 4)
        assertEquals(mismatch, problemOf(MigrationMismatchException(mismatch)))
    }

    @Test
    fun `a move waiting on this device's sync tries again on the next sync`() {
        assertNull(waitAfter(JournalMoveProblem.MissingOnThisDevice(JournalDataKind.ENTRIES, 1)))
        assertNull(waitAfter(JournalMoveProblem.Offline))
        assertEquals(1.hours, waitAfter(JournalMoveProblem.AnotherDeviceMoving))
    }

    @Test
    fun `the move api reads the server's status and keeps a known refusal code`() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/space-migration" -> {
                            respond(
                                """{"phase":"switched","migrationId":"m1","switchedAt":5,"legacyKeptUntil":9,"future":true}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }

                        else -> {
                            respond(
                                """{"code":"${SpaceMigrationErrorCodes.HOLD_NOT_READY}","message":"wait"}""",
                                HttpStatusCode.Conflict,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }
            HttpClient(engine).use { client ->
                val api = HttpSpaceMigrationApi(client) { "https://example.test/api/v1/" }
                val status = api.status("token")
                assertEquals(SpaceMigrationPhase.SWITCHED, status.phase)
                assertEquals(9L, status.legacyKeptUntil)
                val refusal = assertFailsWith<CloudApiException> { api.hold("token", "m1") }
                assertEquals(SpaceMigrationErrorCodes.HOLD_NOT_READY, refusal.errorCode)
                assertEquals(409, refusal.statusCode)
            }
        }

    private fun comparison(
        local: Set<String>,
        waitingCreates: Set<String> = emptySet(),
        onList: () -> Unit = {},
    ): LegacyComparison =
        LegacyComparison(
            listOf(LegacyKindCheck(LegacyRecordKinds.ENTRIES, JournalDataKind.ENTRIES, { local }, waitingCreates = { waitingCreates })),
        ) { kind ->
            onList()
            assertEquals(LegacyRecordKinds.ENTRIES, kind)
            serverIds.toList()
        }

    private var serverIds: Set<String> = emptySet()

    private fun inventory(ids: Set<String>): LegacyInventory {
        serverIds = ids
        return LegacyInventory("watermark", listOf(LegacyKindInventory(LegacyRecordKinds.ENTRIES, ids.size, digestOf(ids))))
    }

    private fun digestOf(ids: Set<String>): String = sha256Hex(SpaceMigrationDigests.idsText(ids))
}
