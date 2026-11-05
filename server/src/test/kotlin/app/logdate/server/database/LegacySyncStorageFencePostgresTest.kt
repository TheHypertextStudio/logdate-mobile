package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.sync.AssociationKey
import app.logdate.server.sync.AssociationRecord
import app.logdate.server.sync.ContentRecord
import app.logdate.server.sync.DbSyncRepository
import app.logdate.server.sync.JournalRecord
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.SpaceMigrationPhase
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LegacySyncStorageFencePostgresTest {
    @Test
    fun movedStorageContentUpsertCannotMutateSource() = checkMutation(State.MOVED, Mutation.CONTENT_UPSERT)

    @Test
    fun movedStorageContentDeleteCannotMutateSource() = checkMutation(State.MOVED, Mutation.CONTENT_DELETE)

    @Test
    fun movedStorageJournalUpsertCannotMutateSource() = checkMutation(State.MOVED, Mutation.JOURNAL_UPSERT)

    @Test
    fun movedStorageJournalDeleteCannotMutateSource() = checkMutation(State.MOVED, Mutation.JOURNAL_DELETE)

    @Test
    fun movedStorageAssociationUpsertCannotMutateSource() = checkMutation(State.MOVED, Mutation.ASSOCIATION_UPSERT)

    @Test
    fun movedStorageAssociationDeleteCannotMutateSource() = checkMutation(State.MOVED, Mutation.ASSOCIATION_DELETE)

    @Test
    fun movedStorageRetentionCannotMutateSource() = checkMutation(State.MOVED, Mutation.RETENTION)

    @Test
    fun legacyHoldContentUpsertCannotMutateSource() = checkMutation(State.HELD, Mutation.CONTENT_UPSERT)

    @Test
    fun legacyHoldContentDeleteCannotMutateSource() = checkMutation(State.HELD, Mutation.CONTENT_DELETE)

    @Test
    fun legacyHoldJournalUpsertCannotMutateSource() = checkMutation(State.HELD, Mutation.JOURNAL_UPSERT)

    @Test
    fun legacyHoldJournalDeleteCannotMutateSource() = checkMutation(State.HELD, Mutation.JOURNAL_DELETE)

    @Test
    fun legacyHoldAssociationUpsertCannotMutateSource() = checkMutation(State.HELD, Mutation.ASSOCIATION_UPSERT)

    @Test
    fun legacyHoldAssociationDeleteCannotMutateSource() = checkMutation(State.HELD, Mutation.ASSOCIATION_DELETE)

    @Test
    fun legacyHoldRetentionCannotMutateSource() = checkMutation(State.HELD, Mutation.RETENTION)

    @Test
    fun globalRetentionPreservesMovedSourceAndProcessesEligibleOwners() = checkGlobalRetention(State.MOVED)

    @Test
    fun globalRetentionPreservesHeldSourceAndProcessesEligibleOwners() = checkGlobalRetention(State.HELD)

    private fun checkMutation(
        state: State,
        mutation: Mutation,
    ) = withFixture {
        change(state)
        val before = snapshot()
        assertEquals(state.outcome, assertFailsWith<IdentityWriteRefusedException> { mutate(mutation) }.outcome)
        assertEquals(before, snapshot())
    }

    private fun checkGlobalRetention(state: State) =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            repository.upsertContent(other, content.copy(id = "other"))
            repository.deleteContent(other, "other", 2)
            change(state)
            val before = snapshot()
            assertEquals(1, repository.purgeTombstonesOlderThan(99).contentPurged)
            assertEquals(before, snapshot())
            source.blueskyTransaction {
                prepareStatement("SELECT content FROM sync_content WHERE user_id = ? AND id = 'other'").use {
                    it.setObject(1, other)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(null, rows.getString(1))
                    }
                }
            }
        }

    private enum class State(
        val outcome: IdentityFenceOutcome,
    ) {
        MOVED(IdentityFenceOutcome.CHANGED),
        HELD(IdentityFenceOutcome.HELD),
    }

    private enum class Mutation {
        CONTENT_UPSERT,
        CONTENT_DELETE,
        JOURNAL_UPSERT,
        JOURNAL_DELETE,
        ASSOCIATION_UPSERT,
        ASSOCIATION_DELETE,
        RETENTION,
    }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val repository = DbSyncRepository()
        val content = ContentRecord("content", "TEXT", "original", null, null, 1, 1, 0, DeviceId("device"))
        private val journal = JournalRecord("journal", "Original", "Description", 1, 1, 0, DeviceId("device"))
        private val association = AssociationRecord(journal.id, content.id, 1, 0, DeviceId("device"))

        fun seed() {
            repository.upsertContent(id, content)
            repository.upsertJournal(id, journal)
            repository.upsertAssociations(id, listOf(association))
            repository.upsertContent(id, content.copy(id = "retained"))
            repository.deleteContent(id, "retained", 2)
            repository.upsertJournal(id, journal.copy(id = "retained-journal"))
            repository.deleteJournal(id, "retained-journal", 2)
        }

        suspend fun change(state: State) {
            val now = Clock.System.now()
            val migration =
                SpaceMigration(
                    id,
                    "legacy-sync-move",
                    SpaceMigrationPhase.UPLOADING,
                    now,
                    now,
                    holdStartedAt = if (state == State.HELD) now else null,
                )
            val store = PostgreSQLSpaceMigrationStore()
            assertTrue(store.replace(null, migration))
            if (state == State.MOVED) {
                assertTrue(
                    store.replaceAndSwitch(
                        migration,
                        migration.next(now) { copy(phase = SpaceMigrationPhase.SWITCHED) },
                        AccountStorageMode.LEGACY,
                        AccountStorageMode.PERSONAL_SPACE_V1,
                    ),
                )
            }
        }

        fun mutate(mutation: Mutation) {
            when (mutation) {
                Mutation.CONTENT_UPSERT -> repository.upsertContent(id, content.copy(content = "changed"))
                Mutation.CONTENT_DELETE -> repository.deleteContent(id, content.id, 99)
                Mutation.JOURNAL_UPSERT -> repository.upsertJournal(id, journal.copy(title = "Changed"))
                Mutation.JOURNAL_DELETE -> repository.deleteJournal(id, journal.id, 99)
                Mutation.ASSOCIATION_UPSERT -> repository.upsertAssociations(id, listOf(association.copy(createdAt = 99)))
                Mutation.ASSOCIATION_DELETE -> repository.deleteAssociations(id, listOf(AssociationKey(journal.id, content.id)), 99)
                Mutation.RETENTION -> repository.purgeTombstones(id, 99)
            }
        }

        suspend fun snapshot(): List<String> =
            source.blueskyTransaction {
                buildList {
                    for (table in listOf("sync_content", "sync_journals", "sync_associations")) {
                        prepareStatement("SELECT row_to_json(t)::text FROM $table t WHERE user_id = ? ORDER BY 1").use {
                            it.setObject(1, id)
                            it.executeQuery().use { rows -> while (rows.next()) add("$table:${rows.getString(1)}") }
                        }
                    }
                }
            }
    }

    private fun withFixture(action: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner = Account(id, id.toHexString(), "Legacy sync owner", createdAt = Clock.System.now())
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val fixture = Fixture(source, owner)
            fixture.seed()
            fixture.action()
        }
}
