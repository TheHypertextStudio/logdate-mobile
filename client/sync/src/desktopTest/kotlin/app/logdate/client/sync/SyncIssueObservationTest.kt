package app.logdate.client.sync

import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.QueuedUpload
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncIssueObservationTest {
    private val scope = UploadScope("synthetic-owner", "https://sync.example")
    private val issue =
        SyncDeadLetterRecord(
            id = "synthetic-operation",
            entityType = "NOTE",
            entityId = "synthetic-entry",
            operation = "UPDATE",
            retryCount = 5,
            lastError = "UNKNOWN",
            failedAt = 0,
            scope = scope,
            operationId = "synthetic-operation",
            expectedServerVersion = 41,
        )
    private val queued =
        QueuedUpload(
            EntityType.NOTE,
            issue.entityId,
            PendingOperation.UPDATE,
            5,
            scope,
            issue.operationId,
            expectedServerVersion = 41,
        )

    @Test
    fun `the exact pending repair keeps its actionable issue visible`() =
        runBlocking {
            assertEquals(listOf(issue), visible(queued))
        }

    @Test
    fun `an earlier repair version is not offered as a retry for the current repair`() =
        runBlocking {
            assertEquals(emptyList(), visible(queued.copy(expectedServerVersion = 42)))
        }

    @Test
    fun `a repair failure does not attach to an ordinary write with the same legacy identity`() =
        runBlocking {
            assertEquals(emptyList(), visible(queued.copy(expectedServerVersion = null)))
        }

    @Test
    fun `a different owner or operation does not inherit an old issue`() =
        runBlocking {
            assertEquals(emptyList(), visible(queued.copy(scope = scope.copy(ownerId = "another-owner"))))
            assertEquals(emptyList(), visible(queued.copy(operationId = "another-operation")))
        }

    private suspend fun visible(pending: QueuedUpload): List<SyncDeadLetterRecord> {
        val retained = listOf(issue)
        val store =
            object : SyncDeadLetterStore {
                override fun observe() = flowOf(retained)

                override suspend fun list() = retained

                override suspend fun add(record: SyncDeadLetterRecord) = error("Observation must not write")

                override suspend fun remove(id: String) = error("Observation must not remove custody")

                override suspend fun clear() = error("Observation must not clear custody")
            }
        val metadata =
            Proxy.newProxyInstance(
                SyncMetadataService::class.java.classLoader,
                arrayOf(SyncMetadataService::class.java),
            ) { _, method, _ ->
                check(method.name == "observePendingUploads") { "Unexpected metadata access: ${method.name}" }
                flowOf(listOf(pending))
            } as SyncMetadataService
        return store.observeQueued(metadata).first()
    }
}
