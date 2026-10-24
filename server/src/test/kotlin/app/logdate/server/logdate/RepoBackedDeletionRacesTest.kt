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
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.RepoRecordId
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

@OptIn(ExperimentalUuidApi::class)
class RepoBackedDeletionRacesTest {
    private val syncCollectionKinds =
        listOf(
            LogDateCollectionKind.ENTRY,
            LogDateCollectionKind.JOURNAL,
            LogDateCollectionKind.ASSOCIATION,
            LogDateCollectionKind.DRAFT,
        )

    @Test
    fun `association rejection restores unaffected existing members of the batch`() =
        runTest {
            verifyBatchCompensation(publishNewerSurvivor = false)
        }

    @Test
    fun `association compensation preserves a newer concurrent edit to an unaffected link`() =
        runTest {
            verifyBatchCompensation(publishNewerSurvivor = true)
        }

    private suspend fun verifyBatchCompensation(publishNewerSurvivor: Boolean) {
        val fixture = fixture()
        val otherInstance = fixture.repository()
        val first = LogDateAssociation("journal", "first", 1, 0, DeviceId("device"))
        val survivor = LogDateAssociation("journal", "survivor", 2, 0, DeviceId("device"))
        otherInstance.upsertAssociations(fixture.user, listOf(first, survivor))
        val repoDid = otherInstance.canonicalRepoDid(fixture.user)
        val survivorId = associationRecordId(repoDid, survivor.journalId, survivor.entryId)
        val previous = assertNotNull(otherInstance.repoEngine.getRecord(survivorId).getOrThrow())
        var expected = previous
        val rejectingMetadata =
            object : LogDateCollectionsMetadataStore by fixture.metadata {
                override suspend fun upsertBatch(
                    userId: UUID,
                    repoDid: AtprotoDid,
                    collection: LogDateCollectionKind,
                    recordKeys: List<String>,
                ): List<LogDateCollectionMetadata> {
                    // A different server deletes just the first link after both canonical writes.
                    otherInstance.deleteAssociations(userId, listOf(LogDateAssociationRef(first.journalId, first.entryId)), 100)
                    if (publishNewerSurvivor) {
                        otherInstance.upsertAssociations(userId, listOf(survivor.copy(createdAt = 5)))
                        expected = assertNotNull(otherInstance.repoEngine.getRecord(survivorId).getOrThrow())
                    }
                    return fixture.metadata.upsertBatch(userId, repoDid, collection, recordKeys)
                }
            }
        val writer = fixture.repository(metadata = rejectingMetadata)
        assertFailsWith<StaleWriteException> {
            writer.upsertAssociations(fixture.user, listOf(first.copy(createdAt = 3), survivor.copy(createdAt = 4)))
        }
        val restored = assertNotNull(writer.repoEngine.getRecord(survivorId).getOrThrow())
        assertEquals(expected.cid, restored.cid)
        assertEquals(expected.value, restored.value)
        assertEquals(if (publishNewerSurvivor) 5L else survivor.createdAt, otherInstance.listAssociations(fixture.user).single().createdAt)
        assertNull(writer.repoEngine.getRecord(associationRecordId(repoDid, first.journalId, first.entryId)).getOrThrow())
    }

    @Test
    fun `another server cannot publish between canonical removal and deletion fencing`() =
        runTest {
            for (kind in syncCollectionKinds) {
                val fixture = fixture()
                val writer = fixture.repository()
                write(writer, fixture.user, kind)
                var attempted = false
                var rejected = false
                var onCommit: (suspend () -> Unit)? = {
                    attempted = true
                    try {
                        write(writer, fixture.user, kind)
                    } catch (_: StaleWriteException) {
                        rejected = true
                    }
                }
                val deletingBlocks =
                    object : RepoBlockStore by fixture.blocks {
                        override suspend fun compareAndSwapHead(
                            head: RepoHead,
                            expectedRevision: Long?,
                        ): Result<Boolean> {
                            val result = fixture.blocks.compareAndSwapHead(head, expectedRevision)
                            if (result.getOrThrow()) {
                                val callback = onCommit
                                onCommit = null
                                callback?.invoke()
                            }
                            return result
                        }
                    }
                val deleter = fixture.repository(blocks = deletingBlocks)
                delete(deleter, fixture.user, kind)
                assertTrue(attempted, "Expected an interleaved write for $kind")
                assertTrue(rejected, "A deletion fence must exist before the canonical removal for $kind")
                val did = writer.canonicalRepoDid(fixture.user)
                val id = recordId(did, kind)
                assertNull(writer.repoEngine.getRecord(id).getOrThrow(), "No canonical body may survive deletion for $kind")
            }
        }

    @Test
    fun `failed canonical cleanup preserves a deletion fence for every sync collection`() =
        runTest {
            for (kind in syncCollectionKinds) {
                val fixture = fixture()
                val writer = fixture.repository()
                write(writer, fixture.user, kind)
                val failingBlocks =
                    object : RepoBlockStore by fixture.blocks {
                        override suspend fun compareAndSwapHead(
                            head: RepoHead,
                            expectedRevision: Long?,
                        ): Result<Boolean> = Result.failure(IllegalStateException("Interrupted canonical cleanup"))
                    }
                val deleter = fixture.repository(blocks = failingBlocks)
                assertFailsWith<IllegalStateException> { delete(deleter, fixture.user, kind) }
                assertFailsWith<StaleWriteException> { write(writer, fixture.user, kind) }
                val id = recordId(writer.canonicalRepoDid(fixture.user), kind)
                assertNotNull(fixture.metadata.tombstone(fixture.user, kind, id.recordKey.toString()))
                delete(writer, fixture.user, kind)
                assertNull(writer.repoEngine.getRecord(id).getOrThrow())
            }
        }

    private suspend fun write(
        repository: RepoBackedLogDateCollectionsRepository,
        user: UUID,
        kind: LogDateCollectionKind,
    ) {
        when (kind) {
            LogDateCollectionKind.ENTRY -> {
                repository.upsertEntry(
                    user,
                    LogDateEntry("entry", "TEXT", "edit", null, 0, 1, 2, 0, DeviceId("device")),
                )
            }

            LogDateCollectionKind.JOURNAL -> {
                repository.upsertJournal(
                    user,
                    LogDateJournal("journal", "Edit", "", 1, 2, 0, DeviceId("device")),
                )
            }

            LogDateCollectionKind.ASSOCIATION -> {
                repository.upsertAssociations(
                    user,
                    listOf(LogDateAssociation("journal", "entry", 1, 0, DeviceId("device"))),
                )
            }

            LogDateCollectionKind.MOMENT -> {
                error("Moments use their own repository")
            }

            LogDateCollectionKind.DRAFT -> {
                repository.upsertDraft(
                    user,
                    LogDateDraft("draft", "edit", emptyList(), emptyList(), 1, 2, 0, DeviceId("device")),
                )
            }
        }
    }

    private suspend fun delete(
        repository: RepoBackedLogDateCollectionsRepository,
        user: UUID,
        kind: LogDateCollectionKind,
    ) {
        when (kind) {
            LogDateCollectionKind.ENTRY -> repository.deleteEntry(user, "entry", 100)
            LogDateCollectionKind.JOURNAL -> repository.deleteJournal(user, "journal", 100)
            LogDateCollectionKind.ASSOCIATION -> repository.deleteAssociations(user, listOf(LogDateAssociationRef("journal", "entry")), 100)
            LogDateCollectionKind.DRAFT -> repository.deleteDraft(user, "draft", 100)
            LogDateCollectionKind.MOMENT -> error("Moments use their own repository")
        }
    }

    private fun recordId(
        did: AtprotoDid,
        kind: LogDateCollectionKind,
    ): RepoRecordId =
        when (kind) {
            LogDateCollectionKind.ENTRY -> entryRecordId(did, "entry")
            LogDateCollectionKind.JOURNAL -> journalRecordId(did, "journal")
            LogDateCollectionKind.ASSOCIATION -> associationRecordId(did, "journal", "entry")
            LogDateCollectionKind.DRAFT -> draftRecordId(did, "draft")
            LogDateCollectionKind.MOMENT -> error("Moments use their own repository")
        }

    private suspend fun fixture(): Fixture {
        val accounts = InMemoryAccountRepository()
        val signing = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
        val identity =
            AtprotoIdentityService(
                accounts,
                signing,
                AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
            )
        val account =
            identity.ensureIdentity(
                accounts.save(
                    Account(id = Uuid.random(), username = "deletion-race", displayName = "Race", createdAt = Clock.System.now()),
                ),
            )
        return Fixture(account.id.toJavaUUID(), accounts, signing, identity)
    }

    private class Fixture(
        val user: UUID,
        val accounts: InMemoryAccountRepository,
        val signing: SigningKeyService,
        val identity: AtprotoIdentityService,
    ) {
        val metadata = InMemoryLogDateCollectionsMetadataStore()
        val blocks = InMemoryRepoBlockStore()

        fun repository(
            metadata: LogDateCollectionsMetadataStore = this.metadata,
            blocks: RepoBlockStore = this.blocks,
        ) = RepoBackedLogDateCollectionsRepository(accounts, identity, signing, blocks, metadata)
    }
}
