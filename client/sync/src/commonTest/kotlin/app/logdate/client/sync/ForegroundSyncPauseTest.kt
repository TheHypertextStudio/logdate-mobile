package app.logdate.client.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.ContentUploadResponse
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.test.FakeCloudApiClient
import app.logdate.client.sync.test.fakeDataUsagePolicy
import app.logdate.client.sync.test.fakeJournalNotesRepository
import app.logdate.client.sync.test.fakeSessionStorage
import app.logdate.client.sync.test.fakeSyncMetadataService
import app.logdate.client.sync.test.testDefaultSyncManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncPauseTest {
    @Test
    fun `foreground pause prevents an underlying pending upload from publishing until released`() =
        runTest {
            val fixture = fixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pause =
                launch {
                    fixture.foreground.whilePaused {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val upload = async { fixture.engine.uploadPendingChanges() }
            try {
                runCurrent()
                assertFalse(upload.isCompleted, "The real upload engine must share the foreground wrapper's pause lock")
                assertTrue(fixture.api.uploadContentCalls.isEmpty())
            } finally {
                release.complete(Unit)
            }
            pause.join()
            assertTrue(upload.await().success)
            assertEquals(1, fixture.api.uploadContentCalls.size)
        }

    @Test
    fun `foreground pause waits for an in flight underlying upload before entering the merge block`() =
        runTest {
            val uploadStarted = CompletableDeferred<Unit>()
            val releaseUpload = CompletableDeferred<Unit>()
            val api =
                object : FakeCloudApiClient() {
                    override suspend fun uploadContent(
                        accessToken: String,
                        content: ContentUploadRequest,
                    ): Result<ContentUploadResponse> {
                        uploadStarted.complete(Unit)
                        releaseUpload.await()
                        return super.uploadContent(accessToken, content)
                    }
                }
            val fixture = fixture(api)
            val upload = async { fixture.engine.uploadPendingChanges() }
            uploadStarted.await()
            val mergeEntered = CompletableDeferred<Unit>()
            val pause = async { fixture.foreground.whilePaused { mergeEntered.complete(Unit) } }
            try {
                runCurrent()
                assertFalse(mergeEntered.isCompleted, "A merge must wait for the active uploader to release the shared lock")
            } finally {
                releaseUpload.complete(Unit)
            }
            assertTrue(upload.await().success)
            pause.await()
            assertTrue(mergeEntered.isCompleted)
            assertEquals(1, api.uploadContentCalls.size)
        }

    @Test
    fun `an empty signed in client still discovers remote changes`() =
        runTest {
            val fixture = fixture(empty = true, periodic = true)
            runCurrent()
            assertTrue(fixture.api.wasMethodCalled("getContentChanges"), "Remote discovery must not depend on local writes")
        }

    private suspend fun TestScope.fixture(
        api: FakeCloudApiClient = FakeCloudApiClient(),
        empty: Boolean = false,
        periodic: Boolean = false,
    ): PauseFixture {
        val session = fakeSessionStorage()
        val metadata = fakeSyncMetadataService(session)
        val notes = if (empty) fakeJournalNotesRepository() else fakeJournalNotesRepository("Retained note")
        if (!empty) {
            val note = notes.allNotesObserved.first().single()
            metadata.enqueuePending(note.uid.toString(), EntityType.NOTE, PendingOperation.CREATE)
        }
        val engine =
            testDefaultSyncManager(
                cloudContentDataSource = DefaultCloudContentDataSource(api),
                sessionStorage = session,
                journalNotesRepository = notes,
                syncMetadataService = metadata,
                syncScope = backgroundScope,
            )
        val foreground =
            ForegroundSyncManager(
                defaultSyncManager = engine,
                sessionStorage = session,
                preferencesDataSource = LogdatePreferencesDataSource(PausePreferences()),
                networkMonitor =
                    object : NetworkAvailabilityMonitor {
                        override fun isNetworkAvailable() = true

                        override fun observeNetwork() = MutableSharedFlow<NetworkState>()
                    },
                dataUsagePolicy = fakeDataUsagePolicy(),
                // Keep the periodic scheduler out of this explicit foreground/engine concurrency test.
                syncScope = if (periodic) backgroundScope else CoroutineScope(backgroundScope.coroutineContext + Job().apply { cancel() }),
            )
        return PauseFixture(engine, foreground, api)
    }
}

private data class PauseFixture(
    val engine: DefaultSyncManager,
    val foreground: ForegroundSyncManager,
    val api: FakeCloudApiClient,
)

private class PausePreferences : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}
