package app.logdate.client.data.notes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import app.logdate.client.data.journals.NativeDraftMediaQuarantine
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.SyncTransactionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class NativeMediaQuarantineTest {
    @Test
    fun `new restore delivery waits for media reconciliation before replacing generation`() =
        runTest {
            val file = Files.createTempFile("native-quarantine-in-flight", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val timestamp = Instant.fromEpochMilliseconds(1)
                val note =
                    ImageNoteEntity(
                        contentUri = "file:///data/user/0/studio.hypertext.logdate/files/media/objects/missing.jpg",
                        created = timestamp,
                        lastUpdated = timestamp,
                    )
                database.imageNoteDao().addNote(note)
                val gate = TestNativeRestoreDeliveryGate()
                val availabilityCheckStarted = CompletableDeferred<Unit>()
                val finishAvailabilityCheck = CompletableDeferred<Unit>()
                val preferences =
                    object : DataStore<Preferences> {
                        override val data = MutableStateFlow(emptyPreferences())

                        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value)
                    }
                val transcripts =
                    Proxy.newProxyInstance(
                        TranscriptionRepository::class.java.classLoader,
                        arrayOf(TranscriptionRepository::class.java),
                    ) { _, _, _ -> error("No audio expected") } as TranscriptionRepository
                val repair =
                    NativeMediaQuarantine(
                        database,
                        NativeDraftMediaQuarantine(preferences, Json),
                        RoomSyncTransactionManager(database),
                        transcripts,
                        gate,
                    ) { reference ->
                        assertEquals(note.contentUri, reference)
                        availabilityCheckStarted.complete(Unit)
                        finishAvailabilityCheck.await()
                        false
                    }
                var currentDelivery = "delivery-1"

                val reconciliation = async { repair.run { currentDelivery == "delivery-1" } }
                availabilityCheckStarted.await()
                val replacementDelivery =
                    async {
                        gate.withLock { currentDelivery = "delivery-2" }
                    }
                runCurrent()

                assertFalse(replacementDelivery.isCompleted)
                finishAvailabilityCheck.complete(Unit)

                assertTrue(reconciliation.await())
                replacementDelivery.await()
                assertEquals("delivery-2", currentDelivery)
                assertEquals(
                    note.copy(contentUri = UnavailableMediaReferences.nativeRestore(note.contentUri)),
                    database.imageNoteDao().getNoteOneOff(note.uid),
                )
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun `new restore generation serializes before stale media reconciliation begins`() =
        runTest {
            val file = Files.createTempFile("native-quarantine-generation", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val timestamp = Instant.fromEpochMilliseconds(1)
                val note =
                    ImageNoteEntity(
                        contentUri = "file:///storage/emulated/0/DCIM/restored.jpg",
                        created = timestamp,
                        lastUpdated = timestamp,
                    )
                database.imageNoteDao().addNote(note)
                val gate = TestNativeRestoreDeliveryGate()
                val preferences =
                    object : DataStore<Preferences> {
                        override val data = MutableStateFlow(emptyPreferences())

                        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value)
                    }
                val transcripts =
                    Proxy.newProxyInstance(
                        TranscriptionRepository::class.java.classLoader,
                        arrayOf(TranscriptionRepository::class.java),
                    ) { _, _, _ -> error("No audio expected") } as TranscriptionRepository
                val repair =
                    NativeMediaQuarantine(
                        database,
                        NativeDraftMediaQuarantine(preferences, Json),
                        RoomSyncTransactionManager(database),
                        transcripts,
                        gate,
                    ) { error("Stale generation must not inspect media") }
                var currentDelivery = "delivery-1"
                val generationLocked = CompletableDeferred<Unit>()
                val releaseGeneration = CompletableDeferred<Unit>()
                val changeGeneration =
                    launch {
                        gate.withLock {
                            currentDelivery = "delivery-2"
                            generationLocked.complete(Unit)
                            releaseGeneration.await()
                        }
                    }
                generationLocked.await()

                val staleReconciliation = async { runCatching { repair.run { currentDelivery == "delivery-1" } } }
                runCurrent()
                assertFalse(staleReconciliation.isCompleted)
                releaseGeneration.complete(Unit)
                changeGeneration.join()

                assertTrue(staleReconciliation.await().exceptionOrNull() is IllegalStateException)
                assertEquals(note, database.imageNoteDao().getNoteOneOff(note.uid))
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun `restored file checks run before the database writer is taken`() =
        runTest {
            val file = Files.createTempFile("native-quarantine", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val timestamp = Instant.fromEpochMilliseconds(1)
                val missing =
                    ImageNoteEntity(
                        contentUri = "file:///data/user/0/studio.hypertext.logdate/files/media/objects/missing.jpg",
                        created = timestamp,
                        lastUpdated = timestamp,
                    )
                val present = missing.copy(uid = Uuid.random(), contentUri = "logdate-media://library/present.jpg")
                database.imageNoteDao().addNote(missing)
                database.imageNoteDao().addNote(present)
                val room = RoomSyncTransactionManager(database)
                var insideTransaction = false
                val transactions =
                    object : SyncTransactionManager {
                        override suspend fun <T> withTransaction(block: suspend () -> T): T =
                            room.withTransaction {
                                insideTransaction = true
                                try {
                                    block()
                                } finally {
                                    insideTransaction = false
                                }
                            }
                    }
                val checksInsideTransaction = mutableListOf<String>()
                val transcripts =
                    Proxy.newProxyInstance(
                        TranscriptionRepository::class.java.classLoader,
                        arrayOf(TranscriptionRepository::class.java),
                    ) { _, _, _ -> error("No audio expected") } as TranscriptionRepository
                val repair =
                    NativeMediaQuarantine(
                        database,
                        NativeDraftMediaQuarantine(InMemoryPreferences(), Json),
                        transactions,
                        transcripts,
                    ) { reference ->
                        if (insideTransaction) checksInsideTransaction += reference
                        reference != missing.contentUri
                    }

                assertTrue(repair.run { true })

                assertEquals(emptyList(), checksInsideTransaction)
                assertEquals(
                    UnavailableMediaReferences.nativeRestore(missing.contentUri),
                    database.imageNoteDao().getNoteOneOff(missing.uid).contentUri,
                )
                assertEquals(present, database.imageNoteDao().getNoteOneOff(present.uid))
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }

    private class InMemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }

    @Test
    fun `restored provider IDs are quarantined while private originals and timestamps survive`() =
        runTest {
            val file = Files.createTempFile("native-quarantine", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val timestamp = Instant.fromEpochMilliseconds(1)
                val stale =
                    ImageNoteEntity(contentUri = "content://media/external/images/media/123", created = timestamp, lastUpdated = timestamp)
                val owned = stale.copy(uid = Uuid.random(), contentUri = "logdate-media://library/original.jpg")
                val shared = stale.copy(uid = Uuid.random(), contentUri = "/storage/emulated/0/DCIM/123.jpg")
                val missingPrivate =
                    stale.copy(
                        uid = Uuid.random(),
                        contentUri = "file:///data/user/0/studio.hypertext.logdate/files/media/objects/missing.jpg",
                    )
                val unknownPrivate =
                    stale.copy(
                        uid = Uuid.random(),
                        contentUri = "file:///data/user/0/studio.hypertext.logdate/files/media/objects/probe-error.jpg",
                    )
                database.imageNoteDao().addNote(stale)
                database.imageNoteDao().addNote(owned)
                database.imageNoteDao().addNote(shared)
                database.imageNoteDao().addNote(missingPrivate)
                database.imageNoteDao().addNote(unknownPrivate)
                val transcripts =
                    Proxy.newProxyInstance(
                        TranscriptionRepository::class.java.classLoader,
                        arrayOf(TranscriptionRepository::class.java),
                    ) { _, _, _ -> error("No audio expected") } as TranscriptionRepository
                val preferences =
                    object : DataStore<Preferences> {
                        override val data =
                            MutableStateFlow(
                                emptyPreferences(),
                            )

                        override suspend fun updateData(
                            transform: suspend (
                                Preferences,
                            ) -> Preferences,
                        ) = transform(data.value).also {
                            data.value =
                                it
                        }
                    }
                val repair =
                    NativeMediaQuarantine(
                        database,
                        NativeDraftMediaQuarantine(preferences, Json),
                        RoomSyncTransactionManager(database),
                        transcripts,
                    ) { reference ->
                        when (reference) {
                            missingPrivate.contentUri -> false
                            unknownPrivate.contentUri -> error("File availability could not be checked")
                            else -> true
                        }
                    }
                assertEquals(false, repair.run { true })
                assertEquals(
                    stale.copy(contentUri = UnavailableMediaReferences.nativeRestore(stale.contentUri)),
                    database.imageNoteDao().getNoteOneOff(stale.uid),
                )
                assertEquals(owned, database.imageNoteDao().getNoteOneOff(owned.uid))
                assertEquals(
                    shared.copy(contentUri = UnavailableMediaReferences.nativeRestore(shared.contentUri)),
                    database.imageNoteDao().getNoteOneOff(shared.uid),
                )
                assertEquals(
                    missingPrivate.copy(contentUri = UnavailableMediaReferences.nativeRestore(missingPrivate.contentUri)),
                    database.imageNoteDao().getNoteOneOff(missingPrivate.uid),
                )
                assertEquals(
                    unknownPrivate.copy(contentUri = UnavailableMediaReferences.nativeRestore(unknownPrivate.contentUri)),
                    database.imageNoteDao().getNoteOneOff(unknownPrivate.uid),
                )
                val rows = database.imageNoteDao().getAll()
                repair.run { true }
                assertEquals(rows.toSet(), database.imageNoteDao().getAll().toSet())
                assertFailsWith<IllegalStateException> { repair.run { false } }
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }
}

private class TestNativeRestoreDeliveryGate : NativeRestoreDeliveryGate {
    private val mutex = Mutex()

    override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
