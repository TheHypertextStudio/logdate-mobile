package app.logdate.server.logdate

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.database.toJavaUUID
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for the repository implementation backed by ATProto repositories.
 *
 * This suite verifies that user data (entries, journals, and associations) are
 * correctly persisted as canonical records within a block-stored repository. It
 * ensures that the implementation maintains synchronization capabilities, such as
 * change feeds and tombstone management, while leveraging the ATProto repository
 * model for data integrity and identity linkage.
 */
@OptIn(ExperimentalUuidApi::class)
class RepoBackedLogDateCollectionsRepositoryTest {
    @Test
    fun `entries become canonical repo records while preserving sync change feeds`() =
        runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val identityService = identityService(accountRepository)
            val account =
                identityService.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "alice",
                            displayName = "Alice",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val blockStore = InMemoryRepoBlockStore()
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accountRepository = accountRepository,
                    identityService = identityService,
                    signingKeyService = signingKeyService,
                    blockStore = blockStore,
                    metadataStore = InMemoryLogDateCollectionsMetadataStore(),
                )
            val userId = account.id.toJavaUUID()

            val stored =
                repository.upsertEntry(
                    userId = userId,
                    entry =
                        LogDateEntry(
                            id = "entry-1",
                            type = "TEXT",
                            transcript = "LDSE2:opaque-transcript",
                            content = "hello",
                            mediaUri = null,
                            durationMs = 0L,
                            createdAt = 10L,
                            lastUpdated = 10L,
                            version = 0L,
                            deviceId = DeviceId("device-a"),
                        ),
                )

            assertNull(repository.createEntryIfAbsent(userId, stored.copy(content = "Stale legacy copy")))
            repository.upsertEntry(userId, stored.copy(transcript = null))
            val fetched = repository.getEntry(userId = userId, id = "entry-1")
            val snapshot = repository.listEntries(userId)
            val changes = repository.entryChanges(userId = userId, since = 0L, limit = 20)
            val repoDid = AtprotoDid.require(requireNotNull(account.did))
            val canonicalRecord =
                DefaultRepoEngine(blockStore)
                    .getRecord(entryRecordId(repoDid, "entry-1"))
                    .getOrThrow()

            assertNotNull(fetched)
            assertEquals("hello", fetched.content)
            assertEquals("LDSE2:opaque-transcript", fetched.transcript)
            assertEquals(listOf(fetched), snapshot)
            assertEquals(listOf(fetched), changes.changes)
            assertTrue(stored.version > 0L)
            assertNotNull(canonicalRecord)
            assertEquals("hello", canonicalRecord.value.stringValue("content"))

            assertNull(repository.upsertEntry(userId, fetched.copy(transcript = null, mediaUri = "replacement.m4a")).transcript)
            assertNull(repository.getEntry(userId, fetched.id)?.transcript)

            val deletedAt = changes.lastTimestamp - 1L
            repository.deleteEntry(userId = userId, id = "entry-1", deletedAt = deletedAt)

            val deleted = repository.entryChanges(userId = userId, since = stored.version, limit = 20)
            assertTrue(
                deleted.deletions.single().serverVersion > stored.version,
                "Tombstone order must use its authoritative version, not a wall-clock deletion timestamp",
            )
            val purged = repository.purgeTombstones(userId = userId, olderThan = deletedAt + 1L)

            assertNull(repository.getEntry(userId = userId, id = "entry-1"))
            assertTrue(repository.listEntries(userId).isEmpty())
            assertEquals(
                listOf(LogDateEntryDeletion(id = "entry-1", deletedAt = deletedAt, serverVersion = deleted.lastTimestamp)),
                deleted.deletions,
            )
            assertEquals(0, purged.entryPurged)
        }

    @Test
    fun `journals and associations share the same canonical repo did and status counts`() =
        runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val identityService = identityService(accountRepository)
            val account =
                identityService.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "brie",
                            displayName = "Brie",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val blockStore = InMemoryRepoBlockStore()
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accountRepository = accountRepository,
                    identityService = identityService,
                    signingKeyService = signingKeyService,
                    blockStore = blockStore,
                    metadataStore = InMemoryLogDateCollectionsMetadataStore(),
                )
            val userId = account.id.toJavaUUID()
            val repoDid = AtprotoDid.require(requireNotNull(account.did))

            val journal =
                repository.upsertJournal(
                    userId = userId,
                    journal =
                        LogDateJournal(
                            id = "journal-1",
                            title = "Travel",
                            description = "Trip notes",
                            createdAt = 20L,
                            lastUpdated = 20L,
                            version = 0L,
                            deviceId = DeviceId("device-b"),
                        ),
                )
            val association =
                repository
                    .upsertAssociations(
                        userId = userId,
                        associations =
                            listOf(
                                LogDateAssociation(
                                    journalId = "journal-1",
                                    entryId = "entry-1",
                                    createdAt = 30L,
                                    version = 0L,
                                    deviceId = DeviceId("device-c"),
                                ),
                            ),
                    ).single()

            val status = repository.status(userId)
            val journalRecord =
                DefaultRepoEngine(blockStore)
                    .getRecord(journalRecordId(repoDid, "journal-1"))
                    .getOrThrow()
            val associationRecord =
                DefaultRepoEngine(blockStore)
                    .getRecord(associationRecordId(repoDid, "journal-1", "entry-1"))
                    .getOrThrow()

            assertTrue(journal.version > 0L)
            assertTrue(association.version > journal.version)
            assertEquals(0, status.entryCount)
            assertEquals(1, status.journalCount)
            assertEquals(1, status.associationCount)
            assertNotNull(journalRecord)
            assertNotNull(associationRecord)
            assertEquals("Travel", journalRecord.value.stringValue("title"))
            assertEquals("entry-1", associationRecord.value.stringValue("contentId"))
        }

    @Test
    fun `missing accounts fall back to a stable synthetic repo did`() =
        runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accountRepository = accountRepository,
                    identityService = identityService(accountRepository),
                    signingKeyService = signingKeyService,
                    blockStore = InMemoryRepoBlockStore(),
                    metadataStore = InMemoryLogDateCollectionsMetadataStore(),
                )
            val userId = UUID.randomUUID()

            val stored =
                repository.upsertEntry(
                    userId = userId,
                    entry =
                        LogDateEntry(
                            id = "entry-fallback",
                            type = "TEXT",
                            content = "fallback",
                            mediaUri = null,
                            durationMs = 0L,
                            createdAt = 1L,
                            lastUpdated = 1L,
                            version = 0L,
                            deviceId = DeviceId("device-fallback"),
                        ),
                )
            val changes = repository.entryChanges(userId = userId, since = 0L, limit = 20)

            assertTrue(stored.version > 0L)
            assertEquals(listOf("entry-fallback"), changes.changes.map(LogDateEntry::id))
        }

    @Test
    fun `tenant-wide cleanup retains deletion delivery for long-offline clients`() =
        runTest {
            val accountRepository = InMemoryAccountRepository()
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accountRepository = accountRepository,
                    identityService = identityService(accountRepository),
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    blockStore = InMemoryRepoBlockStore(),
                    metadataStore = InMemoryLogDateCollectionsMetadataStore(),
                )
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()

            listOf(first, second).forEach { userId ->
                repository.upsertEntry(userId = userId, entry = entry("doomed-$userId"))
                repository.upsertEntry(userId = userId, entry = entry("kept-$userId"))
                repository.deleteEntry(userId = userId, id = "doomed-$userId", deletedAt = 1L)
            }

            // The scheduled retention job runs without a user, so it must reach both tenants.
            val purged = repository.purgeTombstones(olderThan = 2L)

            assertEquals(0, purged.entryPurged)
            listOf(first, second).forEach { userId ->
                assertTrue(
                    repository.entryChanges(userId, since = 0, limit = 50).deletions.any { it.id == "doomed-$userId" },
                    "Long-offline clients still need the tombstone for $userId",
                )
                assertNotNull(repository.getEntry(userId, "kept-$userId"))
            }
        }

    @Test
    fun `a metadata failure partway through a batch leaves no phantom repo records`() =
        runTest {
            val accountRepository = InMemoryAccountRepository()
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val identityService = identityService(accountRepository)
            val account =
                identityService.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "dana",
                            displayName = "Dana",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val blockStore = InMemoryRepoBlockStore()
            val failingRecordKey = associationRecordKey(journalId = "journal-1", entryId = "entry-3").toString()
            val metadataStore =
                FailingBatchMetadataStore(
                    delegate = InMemoryLogDateCollectionsMetadataStore(),
                    failingRecordKey = failingRecordKey,
                )
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accountRepository = accountRepository,
                    identityService = identityService,
                    signingKeyService = signingKeyService,
                    blockStore = blockStore,
                    metadataStore = metadataStore,
                )
            val userId = account.id.toJavaUUID()
            val repoDid = AtprotoDid.require(requireNotNull(account.did))
            val associations =
                (1..5).map { i ->
                    LogDateAssociation(
                        journalId = "journal-1",
                        entryId = "entry-$i",
                        createdAt = 10L * i,
                        version = 0L,
                        deviceId = DeviceId("device-batch"),
                    )
                }

            // The third association's metadata upsert fails; before the fix, the repo batch had
            // already committed all five records atomically, so entries 3-5 would survive as
            // "phantom" records - present in commit history but invisible to listAssociations,
            // which reads from metadataStore rather than the tree.
            assertFailsWith<IllegalStateException> {
                repository.upsertAssociations(userId = userId, associations = associations)
            }

            val engine = DefaultRepoEngine(blockStore)
            associations.forEach { association ->
                assertNull(
                    engine.getRecord(associationRecordId(repoDid, association.journalId, association.entryId)).getOrThrow(),
                    "no repo record for ${association.entryId} should survive a failed batch upsert",
                )
            }
            assertTrue(repository.listAssociations(userId).isEmpty(), "no association should be visible after a failed batch upsert")
        }

    @Test
    fun `failed association batch preserves an existing journal link`() =
        runTest {
            val accountRepository = InMemoryAccountRepository()
            val identityService = identityService(accountRepository)
            val account =
                identityService.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "existing-link",
                            displayName = "Existing Link",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val blockStore = InMemoryRepoBlockStore()
            val failingKey = associationRecordKey(journalId = "journal-1", entryId = "entry-2").toString()
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accountRepository = accountRepository,
                    identityService = identityService,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    blockStore = blockStore,
                    metadataStore = FailingBatchMetadataStore(InMemoryLogDateCollectionsMetadataStore(), failingKey),
                )
            val userId = account.id.toJavaUUID()
            val repoDid = AtprotoDid.require(requireNotNull(account.did))
            val existing = LogDateAssociation("journal-1", "entry-1", 10L, 0L, DeviceId("device-a"))
            repository.upsertAssociations(userId, listOf(existing))
            val recordID = associationRecordId(repoDid, existing.journalId, existing.entryId)
            val before = assertNotNull(DefaultRepoEngine(blockStore).getRecord(recordID).getOrThrow())
            val newLink = LogDateAssociation("journal-1", "entry-2", 20L, 0L, DeviceId("device-b"))

            assertFailsWith<IllegalStateException> {
                repository.upsertAssociations(userId, listOf(existing.copy(createdAt = 30L), newLink))
            }

            val after = assertNotNull(DefaultRepoEngine(blockStore).getRecord(recordID).getOrThrow())
            assertEquals(before.value, after.value)
            assertEquals(listOf(existing.entryId), repository.listAssociations(userId).map { it.entryId })
            val newRecordID = associationRecordId(repoDid, newLink.journalId, newLink.entryId)
            assertNull(DefaultRepoEngine(blockStore).getRecord(newRecordID).getOrThrow())
        }

    private fun entry(id: String) =
        LogDateEntry(
            id = id,
            type = "TEXT",
            content = "content",
            mediaUri = null,
            durationMs = 0,
            createdAt = 1L,
            lastUpdated = 1L,
            version = 0L,
            deviceId = DeviceId("device-purge"),
        )

    @Test
    fun `legacy draft update cannot erase an upgraded draft`() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val identity = identityService(accounts)
            val account =
                identity.ensureIdentity(
                    accounts.save(
                        Account(
                            id = Uuid.random(),
                            username = "draft-owner",
                            displayName = "Draft owner",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val repository =
                RepoBackedLogDateCollectionsRepository(
                    accountRepository = accounts,
                    identityService = identity,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    blockStore = InMemoryRepoBlockStore(),
                    metadataStore = InMemoryLogDateCollectionsMetadataStore(),
                )
            val user = account.id.toJavaUUID()
            val rich =
                LogDateDraft(
                    id = "draft-1",
                    content = "legacy text",
                    blockTypes = listOf("TEXT", "IMAGE"),
                    journalIds = listOf("journal-1"),
                    createdAt = 1L,
                    lastUpdated = 2L,
                    version = 0L,
                    deviceId = DeviceId("new-client"),
                    encryptedBlocksVersion = 1,
                    encryptedBlocks = "LDSE2:blocks",
                )
            repository.upsertDraft(user, rich)

            assertFailsWith<DraftFormatUpgradeRequiredException> {
                repository.upsertDraft(user, rich.copy(encryptedBlocks = null, encryptedBlocksVersion = null, content = "old edit"))
            }
            assertEquals(rich.encryptedBlocks, repository.getDraft(user, rich.id)?.encryptedBlocks)
        }

    private fun identityService(accountRepository: InMemoryAccountRepository): AtprotoIdentityService =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )

    /**
     * Metadata store that throws when asked to upsert [failingRecordKey], to exercise
     * [RepoBackedLogDateCollectionsRepository.upsertAssociations]'s handling of a metadata
     * failure partway through a batch.
     *
     * Deliberately does *not* delegate [upsertBatch] to [delegate]'s own (atomic) implementation:
     * it loops through this store's own [upsert] instead, so records before the failing one land
     * for real before the throw - the worst case for a metadata store's own batch write, and the
     * scenario the repository's own repo-side compensation has to hold up against regardless of
     * whether the metadata store it's talking to can roll back its half of the work itself.
     */
    private class FailingBatchMetadataStore(
        private val delegate: LogDateCollectionsMetadataStore,
        private val failingRecordKey: String,
    ) : LogDateCollectionsMetadataStore by delegate {
        override suspend fun upsert(
            userId: UUID,
            repoDid: AtprotoDid,
            collection: LogDateCollectionKind,
            recordKey: String,
        ): LogDateCollectionMetadata {
            check(recordKey != failingRecordKey) { "Simulated metadata upsert failure for $recordKey" }
            return delegate.upsert(userId, repoDid, collection, recordKey)
        }

        override suspend fun upsertBatch(
            userId: UUID,
            repoDid: AtprotoDid,
            collection: LogDateCollectionKind,
            recordKeys: List<String>,
        ): List<LogDateCollectionMetadata> = recordKeys.map { recordKey -> upsert(userId, repoDid, collection, recordKey) }
    }
}
