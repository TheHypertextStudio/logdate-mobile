package app.logdate.client.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.logdate.client.database.entities.people.InferredPersonClusterEntity
import app.logdate.client.database.entities.people.InferredPersonEvidenceEntity
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.database.entities.people.PersonLinkEntity
import app.logdate.client.database.entities.people.PersonResolutionDecisionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val OPEN = "OPEN"
private const val RESOLVED = "RESOLVED"
private const val REJECTED = "REJECTED"
private const val INFERRED = "INFERRED"
private const val EXPLICIT = "EXPLICIT"

/**
 * The People queries against the real database: what one refresh replaces and what it leaves, that
 * an interrupted refresh changes nothing, and the uniqueness rules a removal and an import rely on.
 */
class PeopleDaoJvmTest {
    private val now = Instant.fromEpochMilliseconds(1_000)

    @Test
    fun `a refresh replaces open suggestions and machine-made links and keeps everything decided`() =
        withDatabase { database ->
            val maya = database.insertPerson("Maya")
            val confirmedEntry = Uuid.random()
            val staleEntry = Uuid.random()
            val open = database.insertCluster("sam", OPEN, evidenceCount = 2)
            val resolved = database.insertCluster("maya", RESOLVED, evidenceCount = 0)
            val rejected = database.insertCluster("costco", REJECTED, evidenceCount = 0)
            database.personLinkDao().insertAll(listOf(link(maya.id, confirmedEntry, EXPLICIT), link(maya.id, staleEntry, INFERRED)))
            val replacement = cluster("priya", OPEN)
            val freshEntry = Uuid.random()

            database.inferredPersonClusterDao().replaceOpenInference(
                openStatus = OPEN,
                replacedLinkProvenance = INFERRED,
                clusters = listOf(replacement),
                evidence = listOf(evidence(replacement.id)),
                links = listOf(link(maya.id, freshEntry, INFERRED)),
            )

            val clusters = database.inferredPersonClusterDao()
            assertEquals(listOf(replacement), clusters.getByStatus(OPEN))
            assertNull(clusters.getById(open.id))
            assertEquals(resolved, clusters.getById(resolved.id))
            assertEquals(rejected, clusters.getById(rejected.id))
            assertEquals(emptyList(), database.inferredPersonEvidenceDao().getForClusters(listOf(open.id)))
            assertEquals(1, database.inferredPersonEvidenceDao().getForClusters(listOf(replacement.id)).size)
            assertEquals(
                mapOf(confirmedEntry to EXPLICIT, freshEntry to INFERRED),
                database.linksOf(maya.id).associate { it.targetId to it.provenance },
            )
        }

    @Test
    fun `a machine-made link never replaces a confirmed link to the same entry`() =
        withDatabase { database ->
            val maya = database.insertPerson("Maya")
            val entry = Uuid.random()
            val confirmed = link(maya.id, entry, EXPLICIT)
            database.personLinkDao().insertAll(listOf(confirmed))

            database.inferredPersonClusterDao().insertLinksIfAbsent(listOf(link(maya.id, entry, INFERRED)))

            assertEquals(listOf(confirmed), database.linksOf(maya.id))
        }

    @Test
    fun `a refresh that fails part way leaves the previous suggestions and links in place`() =
        withDatabase { database ->
            val maya = database.insertPerson("Maya")
            val open = database.insertCluster("sam", OPEN, evidenceCount = 2)
            val machineLink = link(maya.id, Uuid.random(), INFERRED)
            database.personLinkDao().insertAll(listOf(machineLink))
            val evidenceWithoutItsSuggestion = evidence(clusterId = Uuid.random())

            assertFailsWith<Exception> {
                database.inferredPersonClusterDao().replaceOpenInference(
                    openStatus = OPEN,
                    replacedLinkProvenance = INFERRED,
                    clusters = listOf(cluster("priya", OPEN)),
                    evidence = listOf(evidenceWithoutItsSuggestion),
                    links = emptyList(),
                )
            }

            assertEquals(listOf(open), database.inferredPersonClusterDao().getByStatus(OPEN))
            assertEquals(2, database.inferredPersonEvidenceDao().getForClusters(listOf(open.id)).size)
            assertEquals(listOf(machineLink), database.linksOf(maya.id))
        }

    @Test
    fun `removing one person's links leaves everyone else's`() =
        withDatabase { database ->
            val maya = database.insertPerson("Maya")
            val sam = database.insertPerson("Sam")
            val samLink = link(sam.id, Uuid.random(), EXPLICIT)
            val mayaLinks = listOf(link(maya.id, Uuid.random(), EXPLICIT), link(maya.id, Uuid.random(), INFERRED))
            database.personLinkDao().insertAll(mayaLinks + samLink)

            database.personLinkDao().deleteForPerson(maya.id)

            assertEquals(emptyList(), database.linksOf(maya.id))
            assertEquals(listOf(samLink), database.linksOf(sam.id))
        }

    @Test
    fun `a later decision about a name replaces the earlier one`() =
        withDatabase { database ->
            val maya = database.insertPerson("Maya")
            val decisions = database.personResolutionDecisionDao()
            decisions.insert(decision("maya", action = "CONFIRM", personId = maya.id))
            val suppress = decision("maya", action = "SUPPRESS", personId = null)

            decisions.insert(suppress)

            assertEquals(listOf(suppress), decisions.getAll())
        }

    @Test
    fun `a removed contact keeps its lookup key and is no longer found by it`() =
        withDatabase { database ->
            val people = database.personDao()
            val patel = database.insertPerson("Dr. Patel", contactLookupKey = "patel-key")
            database.insertPerson("Ava", contactLookupKey = "ava-key")
            val inferred = database.insertPerson("Maya")

            people.softDelete(patel.id, now)
            people.softDelete(inferred.id, now)

            assertEquals(listOf("patel-key"), people.getRemovedContactLookupKeys())
            assertNull(people.getByContactLookupKey("patel-key"))
            assertEquals(listOf("Ava"), people.getAll().map { it.name })
        }

    @Test
    fun `a person is stored with their other names or with none`() =
        withDatabase { database ->
            val people = database.personDao()
            val withNames = database.insertPerson("Samantha Lee", aliases = listOf("Sam", "Sammy"))
            val withoutNames = database.insertPerson("Maya")

            assertEquals(listOf("Sam", "Sammy"), people.getById(withNames.id)?.aliases)
            assertEquals(emptyList(), people.getById(withoutNames.id)?.aliases)
        }

    private fun withDatabase(block: suspend (LogDateDatabase) -> Unit) =
        runTest {
            val database = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(BundledSQLiteDriver()).build()
            try {
                block(database)
            } finally {
                database.close()
            }
        }

    private suspend fun LogDateDatabase.insertPerson(
        name: String,
        contactLookupKey: String? = null,
        aliases: List<String> = emptyList(),
    ): PersonEntity {
        val person =
            PersonEntity(
                id = Uuid.random(),
                name = name,
                aliases = aliases,
                origin = "MANUAL",
                contactLookupKey = contactLookupKey,
                created = now,
                lastUpdated = now,
            )
        personDao().insert(person)
        return person
    }

    private suspend fun LogDateDatabase.insertCluster(
        normalizedName: String,
        status: String,
        evidenceCount: Int,
    ): InferredPersonClusterEntity {
        val cluster = cluster(normalizedName, status)
        inferredPersonClusterDao().insertAll(listOf(cluster))
        inferredPersonClusterDao().insertEvidence(List(evidenceCount) { evidence(cluster.id) })
        return cluster
    }

    private suspend fun LogDateDatabase.linksOf(personId: Uuid): List<PersonLinkEntity> = personLinkDao().observeForPerson(personId).first()

    private fun cluster(
        normalizedName: String,
        status: String,
    ) = InferredPersonClusterEntity(
        id = Uuid.random(),
        displayNameHint = normalizedName,
        normalizedName = normalizedName,
        status = status,
        created = now,
        lastUpdated = now,
    )

    private fun evidence(clusterId: Uuid) =
        InferredPersonEvidenceEntity(
            id = Uuid.random(),
            clusterId = clusterId,
            sourceType = "ENTRY_TEXT",
            sourceId = Uuid.random(),
            confidence = 0.72,
            created = now,
        )

    private fun link(
        personId: Uuid,
        targetId: Uuid,
        provenance: String,
    ) = PersonLinkEntity(
        id = Uuid.random(),
        personId = personId,
        targetType = "ENTRY",
        targetId = targetId,
        provenance = provenance,
        confidence = 1.0,
        status = "ACTIVE",
        created = now,
        lastUpdated = now,
    )

    private fun decision(
        normalizedName: String,
        action: String,
        personId: Uuid?,
    ) = PersonResolutionDecisionEntity(
        id = Uuid.random(),
        normalizedName = normalizedName,
        action = action,
        personId = personId,
        created = now,
        lastUpdated = now,
    )
}
