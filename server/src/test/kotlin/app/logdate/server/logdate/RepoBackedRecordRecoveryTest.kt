package app.logdate.server.logdate

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.database.toJavaUUID
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.sync.StaleWriteException
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Canonical publication, deletion fences, and interrupted index-write recovery. */
@OptIn(ExperimentalUuidApi::class)
class RepoBackedRecordRecoveryTest {
    @Test
    fun `a deletion racing canonical publication removes the rejected repo write`() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val identity = identityService(accounts)
            val account =
                identity.ensureIdentity(
                    accounts.save(
                        Account(id = Uuid.random(), username = "racing", displayName = "Racing", createdAt = Clock.System.now()),
                    ),
                )
            val backing = InMemoryLogDateCollectionsMetadataStore()
            val metadata =
                object : LogDateCollectionsMetadataStore by backing {
                    override suspend fun upsert(
                        userId: UUID,
                        repoDid: AtprotoDid,
                        collection: LogDateCollectionKind,
                        recordKey: String,
                    ): LogDateCollectionMetadata {
                        backing.delete(userId, repoDid, collection, recordKey, 100, allowMissing = true)
                        return backing.upsert(userId, repoDid, collection, recordKey)
                    }
                }
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accounts,
                    identity,
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    InMemoryRepoBlockStore(),
                    metadata,
                )
            val user = account.id.toJavaUUID()
            val entry = LogDateEntry("racing-entry", "TEXT", "edit", null, 0, 1, 1, 0, DeviceId("device"))
            assertFailsWith<StaleWriteException> { repository.upsertEntry(user, entry) }
            assertNull(repository.getEntry(user, entry.id))
            assertNull(repository.repoEngine.getRecord(entryRecordId(repository.canonicalRepoDid(user), entry.id)).getOrThrow())
        }

    @Test
    fun `deleting unseen canonical records rejects future writes before touching the repo`() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val identity = identityService(accounts)
            val account =
                identity.ensureIdentity(
                    accounts.save(
                        Account(
                            id = Uuid.random(),
                            username = "deleted",
                            displayName = "Deleted",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accounts,
                    identity,
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    InMemoryRepoBlockStore(),
                    InMemoryLogDateCollectionsMetadataStore(),
                )
            val user = account.id.toJavaUUID()
            val entry = LogDateEntry("deleted-entry", "TEXT", "edit", null, 0, 1, Long.MAX_VALUE, 0, DeviceId("device"))
            val journal = LogDateJournal("deleted-journal", "edit", "", 1, Long.MAX_VALUE, 0, DeviceId("device"))
            val draft = LogDateDraft("deleted-draft", "edit", emptyList(), emptyList(), 1, Long.MAX_VALUE, 0, DeviceId("device"))
            val link = LogDateAssociation(journal.id, entry.id, Long.MAX_VALUE, 0, DeviceId("device"))
            repository.deleteEntry(user, entry.id, 100)
            repository.deleteJournal(user, journal.id, 100)
            repository.deleteDraft(user, draft.id, 100)
            repository.deleteAssociations(user, listOf(LogDateAssociationRef(link.journalId, link.entryId)), 100)
            assertFailsWith<StaleWriteException> { repository.upsertEntry(user, entry) }
            assertFailsWith<StaleWriteException> { repository.createEntryIfAbsent(user, entry) }
            assertFailsWith<StaleWriteException> { repository.upsertJournal(user, journal) }
            assertFailsWith<StaleWriteException> { repository.createJournalIfAbsent(user, journal) }
            assertFailsWith<StaleWriteException> { repository.upsertDraft(user, draft) }
            assertFailsWith<StaleWriteException> { repository.upsertAssociations(user, listOf(link)) }
            repository.purgeTombstones(user, Long.MAX_VALUE)
            assertFailsWith<StaleWriteException> { repository.upsertEntry(user, entry) }
            assertNull(repository.getEntry(user, entry.id))
            assertNull(repository.getJournal(user, journal.id))
        }

    @Test
    fun `retry indexes a canonical create interrupted before its sync metadata was saved`() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val identity = identityService(accounts)
            val account =
                identity.ensureIdentity(
                    accounts.save(
                        Account(
                            id = Uuid.random(),
                            username = "recovery",
                            displayName = "Recovery",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val backing = InMemoryLogDateCollectionsMetadataStore()
            var fail = true
            val metadata =
                object : LogDateCollectionsMetadataStore by backing {
                    override suspend fun upsert(
                        userId: UUID,
                        repoDid: AtprotoDid,
                        collection: LogDateCollectionKind,
                        recordKey: String,
                    ): LogDateCollectionMetadata {
                        if (fail) {
                            fail = false
                            error("Interrupted index write")
                        }
                        return backing.upsert(userId, repoDid, collection, recordKey)
                    }
                }
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accounts,
                    identity,
                    SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    InMemoryRepoBlockStore(),
                    metadata,
                )
            val userId = account.id.toJavaUUID()
            val entry = LogDateEntry("legacy-entry", "TEXT", "Opaque encrypted entry", null, 0, 10, 20, 0, DeviceId("device"))

            assertFailsWith<IllegalStateException> { repository.createEntryIfAbsent(userId, entry) }
            assertNull(repository.createEntryIfAbsent(userId, entry))
            assertEquals(entry.content, repository.getEntry(userId, entry.id)?.content)
            assertEquals(entry.createdAt, repository.getEntry(userId, entry.id)?.createdAt)
            assertEquals(1, repository.entryChanges(userId, 0, 20).changes.size)
        }

    private fun identityService(accountRepository: InMemoryAccountRepository): AtprotoIdentityService =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )
}
