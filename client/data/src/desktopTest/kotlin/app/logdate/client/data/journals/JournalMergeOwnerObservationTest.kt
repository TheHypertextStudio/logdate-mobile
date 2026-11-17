package app.logdate.client.data.journals

import androidx.room.Room
import app.logdate.client.data.account.HistoryAdoptionTestStorage
import app.logdate.client.data.account.HistoryOwnerAdoption
import app.logdate.client.data.di.dataModule
import app.logdate.client.data.fakes.FakeDraftRepository
import app.logdate.client.data.fakes.FakeRemoteJournalDataSource
import app.logdate.client.data.fakes.FakeSyncMetadataService
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.dao.JournalDao
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.client.repository.journals.JournalMergeResult
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class JournalMergeOwnerObservationTest {
    @Test
    fun `published account session restores recovery observation after rows move before owner binding`() =
        runBlocking {
            val file = Files.createTempFile("journal-owner-observation", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            val owner = AdoptionObservationOwner()
            val sessions = AdoptionObservationSessions()
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://server.test")
            val app =
                koinApplication {
                    allowOverride(true)
                    modules(
                        dataModule,
                        module {
                            single<LogDateDatabase> { database }
                            single<JournalDao> { database.journalDao() }
                            single<CanonicalOwnerProvider> { owner }
                            single<SessionStorage> { sessions }
                            single<LogDateConfigRepository> { config }
                            single<RemoteJournalDataSource> { FakeRemoteJournalDataSource() }
                            single<DraftRepository> { FakeDraftRepository() }
                            single<SyncMetadataService> { FakeSyncMetadataService() }
                            single<SyncTransactionManager> { RoomSyncTransactionManager(database) }
                        },
                    )
                }
            val emissions = Channel<List<JournalMergeOperation>>(Channel.UNLIMITED)
            var observer: kotlinx.coroutines.Job? = null
            try {
                val source = journal("Source")
                val destination = journal("Destination")
                listOf(source, destination).forEach { database.journalDao().create(it) }
                val repository = app.koin.get<JournalRepository>()
                val preview = requireNotNull(repository.previewMerge(source.id, destination.id))
                val operation = assertIs<JournalMergeResult.Merged>(repository.merge(preview, Uuid.random())).operation
                repository.markJournalMergeNeedsDestination(operation)
                observer = launch { repository.observeJournalMergeIssues().collect { emissions.send(it) } }
                assertEquals(operation.operationId, withTimeout(3000) { emissions.receive() }.single().operationId)

                val adoption =
                    HistoryOwnerAdoption(
                        storage = HistoryAdoptionTestStorage(),
                        owner = owner,
                        config = config,
                        deviceId = { "device" },
                        moveRows = { old, new, origin, device ->
                            database.historyOwnerAdoptionDao().adopt(old, new, origin, device) { it }
                            // Force the Room invalidation to be consumed while the old owner is still bound.
                            withTimeout(3000) {
                                while (emissions.receive().isNotEmpty()) continue
                            }
                            assertEquals("offline", owner.getCanonicalOwnerId())
                        },
                    )
                assertTrue(adoption.adopt("account"))
                assertEquals("account", owner.getCanonicalOwnerId())
                assertEquals(
                    "account",
                    database
                        .journalMergeDao()
                        .all("account", config.getCurrentBackendUrl())
                        .single()
                        .ownerId,
                )
                sessions.saveSession(UserSession("access", "refresh", "account"))

                val restored =
                    withTimeout(3000) {
                        var issues = emissions.receive()
                        while (issues.isEmpty()) issues = emissions.receive()
                        issues.single()
                    }
                assertEquals(operation.operationId, restored.operationId)
                assertEquals("account", restored.scope.ownerId)
                assertTrue(restored.needsDestination)
            } finally {
                observer?.cancel()
                observer?.join()
                app.close()
                database.close()
                Files.deleteIfExists(file)
                Files.deleteIfExists(file.resolveSibling("${file.fileName}-wal"))
                Files.deleteIfExists(file.resolveSibling("${file.fileName}-shm"))
            }
        }

    private fun journal(title: String) =
        JournalEntity(
            title = title,
            description = "",
            created = Instant.fromEpochMilliseconds(1000),
            lastUpdated = Instant.fromEpochMilliseconds(2000),
        )
}

private class AdoptionObservationOwner : CanonicalOwnerProvider {
    private var id = "offline"
    private var bound = false

    override suspend fun getCanonicalOwnerId() = id

    override suspend fun hasBoundOwner() = bound

    override suspend fun adoptRemoteOwnerIfUninitialized(remoteOwnerId: String): Boolean {
        if (bound) return id == remoteOwnerId
        id = remoteOwnerId
        bound = true
        return true
    }
}

private class AdoptionObservationSessions : SessionStorage {
    private val state = MutableStateFlow<UserSession?>(null)

    override fun getSession() = state.value

    override fun getSessionFlow() = state

    override suspend fun hasValidSession() = state.value != null

    override suspend fun saveSession(session: UserSession) {
        state.value = session
    }

    override suspend fun clearSession() {
        state.value = null
    }
}
