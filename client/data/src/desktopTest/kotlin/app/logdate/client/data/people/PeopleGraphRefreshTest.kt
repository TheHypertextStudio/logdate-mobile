package app.logdate.client.data.people

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonLinkTargetType
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PeopleGraphRefreshTest {
    private val database = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(BundledSQLiteDriver()).build()

    private val textNoteReads = AtomicInteger()
    private val firstReadMayFinish = CompletableDeferred<Unit>()

    private val repository =
        OfflineFirstPeopleGraphRepository(
            personDao = database.personDao(),
            inferredPersonClusterDao = database.inferredPersonClusterDao(),
            inferredPersonEvidenceDao = database.inferredPersonEvidenceDao(),
            personLinkDao = database.personLinkDao(),
            personResolutionDecisionDao = database.personResolutionDecisionDao(),
            textNoteDao = database.textNoteDao().countingReads(),
            audioNoteDao = database.audioNoteDao(),
            transcriptionDao = database.transcriptionDao(),
            eventDao = database.eventDao(),
            transactions = RoomSyncTransactionManager(database),
            journalNotesRepository = notUsedByRefresh(),
            eventRepository = notUsedByRefresh(),
            dispatcher = Dispatchers.IO,
        )

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun `screens refreshing together leave one suggestion per name`() =
        runBlocking {
            firstReadMayFinish.complete(Unit)
            note("had coffee with Priya before work.", day = 1)
            note("the photos came from Priya.", day = 1)

            (1..6).map { async(Dispatchers.IO) { repository.refresh() } }.awaitAll()

            val open = repository.observeOpenClusters().first()
            assertEquals(listOf("Priya"), open.map { it.displayNameHint })
            assertEquals(2, repository.observeEvidence(open.single().uid).first().size)
        }

    @Test
    fun `refreshes requested while one runs share a single further run`() =
        runBlocking {
            note("had coffee with Priya before work.", day = 1)
            note("the photos came from Priya.", day = 2)
            note("Priya is visiting next week.", day = 3)

            val running = async(Dispatchers.IO) { repository.refresh() }
            while (textNoteReads.get() == 0) delay(10.milliseconds)
            val waiting = (1..5).map { async(Dispatchers.IO) { repository.refresh() } }
            delay(300.milliseconds)
            firstReadMayFinish.complete(Unit)
            (waiting + running).awaitAll()

            assertEquals(2, textNoteReads.get())
            val open = repository.observeOpenClusters().first()
            assertEquals(listOf("Priya"), open.map { it.displayNameHint })
            assertEquals(3, repository.observeEvidence(open.single().uid).first().size)
            assertEquals(emptyList(), database.personDao().getAll())
        }

    @Test
    fun `a name mentioned once is not suggested and a refresh never adds a person`() =
        runBlocking {
            firstReadMayFinish.complete(Unit)
            note("had coffee with Priya before work.", day = 1)

            repository.refresh()

            assertEquals(emptyList(), repository.observeOpenClusters().first())
            assertEquals(emptyList(), database.personDao().getAll())
        }

    @Test
    fun `a known person and their aliases are linked instead of suggested`() =
        runBlocking {
            firstReadMayFinish.complete(Unit)
            val sam = Uuid.random()
            database.personDao().insert(
                Person(uid = sam, name = "Samuel Ortiz", aliases = listOf("Sam"), origin = PersonOrigin.MANUAL)
                    .toEntity(createdAt = Instant.fromEpochMilliseconds(0)),
            )
            val first = note("hiked with Sam at dawn.", day = 1)
            val second = note("dinner with Samuel Ortiz tonight.", day = 2)

            repository.refresh()
            repository.refresh()

            assertEquals(emptyList(), repository.observeOpenClusters().first().map { it.displayNameHint })
            val linkedEntries =
                repository
                    .observeLinksForPerson(sam)
                    .first()
                    .filter { it.targetType == PersonLinkTargetType.ENTRY }
                    .map { it.targetId }
            assertEquals(setOf(first, second), linkedEntries.toSet())
        }

    private suspend fun note(
        text: String,
        day: Int,
    ): Uuid {
        val created = Instant.fromEpochMilliseconds(day * 86_400_000L)
        val entity = TextNoteEntity(content = text, created = created, lastUpdated = created)
        database.textNoteDao().addNote(entity)
        return entity.uid
    }

    /** Counts reads of every text entry, and holds the first read until [firstReadMayFinish] completes. */
    private fun TextNoteDao.countingReads(): TextNoteDao {
        val target = this
        return Proxy.newProxyInstance(TextNoteDao::class.java.classLoader, arrayOf(TextNoteDao::class.java)) { _, method, args ->
            if (method.name == "getAll" && textNoteReads.incrementAndGet() == 1) {
                runBlocking { firstReadMayFinish.await() }
            }
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        } as TextNoteDao
    }

    private inline fun <reified T : Any> notUsedByRefresh(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${method.name} is not part of a refresh")
        } as T
}
