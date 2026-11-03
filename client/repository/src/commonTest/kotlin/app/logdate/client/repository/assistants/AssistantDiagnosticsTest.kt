package app.logdate.client.repository.assistants

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AssistantDiagnosticsTest {
    @Test
    fun `permission and source diagnostics omit protected journal and entry identifiers`() {
        val journalId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val excludedId = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val sourceId = Uuid.parse("00000000-0000-0000-0000-000000000003")
        val scope = AssistantContentScope(setOf(journalId), setOf(excludedId))
        val grant =
            AssistantGrant(
                id = Uuid.parse("00000000-0000-0000-0000-000000000004"),
                revision = 1,
                accountId = Uuid.parse("00000000-0000-0000-0000-000000000005"),
                accountServerOrigin = "https://accounts.example",
                clientId = "client",
                deviceId = "device",
                resource = "https://device.example/mcp",
                capabilities = setOf(AssistantCapability.READ_MEMORIES),
                contentScope = scope,
                approvedAt = Instant.parse("2026-10-20T00:00:00Z"),
                expiresAt = Instant.parse("2026-10-21T00:00:00Z"),
            )
        val source = AssistantSourceMetadata(sourceId, setOf(journalId), AssistantSourceKind.TEXT, false)
        listOf(scope, grant, source).forEach { value ->
            listOf(journalId, excludedId, sourceId).forEach { identifier ->
                assertFalse(value.toString().contains(identifier.toString()))
            }
        }
    }
}
