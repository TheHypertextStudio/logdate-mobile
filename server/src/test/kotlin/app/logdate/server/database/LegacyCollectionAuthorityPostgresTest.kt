package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.server.migration.SpaceMigration
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoHead
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class LegacyCollectionAuthorityPostgresTest {
    @Test
    fun personalSpaceModeRefusesLegacyUpsertAtEpochZero() =
        withFixture {
            move()
            assertChanged { metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "entry") }
            assertOriginal()
        }

    @Test
    fun personalSpaceModeRefusesLegacyDeleteAtEpochZero() =
        withFixture {
            move()
            assertChanged { metadata.delete(id, repo, LogDateCollectionKind.ENTRY, "entry", 99) }
            assertOriginal()
        }

    @Test
    fun personalSpaceModeRefusesLegacyPreflight() =
        withFixture {
            move()
            assertChanged { metadata.requireWritable(id, LogDateCollectionKind.ENTRY, "entry") }
            assertOriginal()
        }

    @Test
    fun personalSpaceModeRetainsLegacyTombstones() =
        withFixture {
            move()
            assertChanged { metadata.purgeTombstones(id, 99) }
            assertOriginal()
        }

    @Test
    fun globalRetentionSkipsMovedOwnersAndPurgesEligibleLegacyOwners() =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            val otherRepo = AtprotoDid.require("did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
            createTestAccount(other, otherRepo.toString())
            metadata.delete(other, otherRepo, LogDateCollectionKind.MOMENT, "other", 2, allowMissing = true)
            move()
            metadata.purgeAllTombstones(99)
            assertNull(metadata.tombstone(other, LogDateCollectionKind.MOMENT, "other"))
            assertOriginal()
        }

    @Test
    fun activeLegacyMigrationHoldRefusesAnUnversionedWrite() =
        withFixture {
            migration(Clock.System.now())
            assertEquals(
                IdentityFenceOutcome.HELD,
                assertFailsWith<IdentityWriteRefusedException> {
                    metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "entry")
                }.outcome,
            )
            assertOriginal()
        }

    @Test
    fun uploadingWithoutAHoldKeepsLegacySyncAvailable() =
        withFixture {
            migration(null)
            metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "new")
            assertNotNull(metadata.metadata(id, LogDateCollectionKind.ENTRY, "new"))
        }

    @Test
    fun expiredMigrationHoldLetsLegacySyncResume() =
        withFixture {
            migration(Clock.System.now() - 31.minutes)
            metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "new")
            assertNotNull(metadata.metadata(id, LogDateCollectionKind.ENTRY, "new"))
        }

    @Test
    fun unknownMigrationPhaseCannotAuthorizeLegacyWrites() =
        withFixture {
            migration(null)
            source.blueskyTransaction {
                prepareStatement("UPDATE space_migrations SET phase = 'future-phase' WHERE account_id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertChanged { metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "entry") }
            assertOriginal()
            source.blueskyTransaction {
                prepareStatement("SELECT phase FROM space_migrations WHERE account_id = ?").use {
                    it.setObject(1, id)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("future-phase", rows.getString(1))
                    }
                }
            }
        }

    @Test
    fun authorizationBeforeTheStorageSwitchCannotWriteAfterIt() = authorizedBeforeChange(switch = true)

    @Test
    fun authorizationBeforeTheMigrationHoldCannotWriteAfterIt() = authorizedBeforeChange(switch = false)

    @Test
    fun legacyRefusalRollsBackEarlierRepositoryMutation() =
        withFixture {
            move()
            assertChanged {
                blocks.write(id) {
                    blocks.writeHead(head.copy(revision = 2)).getOrThrow()
                    metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "entry")
                }
            }
            assertOriginal()
        }

    @Test
    fun personalSpaceModeKeepsHostedRepositoryAuthorityForPublicRecords() =
        withFixture {
            move()
            blocks.write(id) { blocks.writeHead(head.copy(revision = 2)).getOrThrow() }
            assertEquals(2L, blocks.readHead(repo).getOrThrow()?.revision)
        }

    private fun authorizedBeforeChange(switch: Boolean) =
        withFixture {
            coroutineScope {
                val authorized = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val writer =
                    async(Dispatchers.IO) {
                        assertEquals(AccountStorageMode.LEGACY, PostgreSQLAccountRepository().findById(owner.id)?.storageMode)
                        authorized.complete(Unit)
                        resume.await()
                        runCatching { metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "entry") }
                    }
                try {
                    withTimeout(5_000) { authorized.await() }
                    if (switch) move() else migration(Clock.System.now())
                } finally {
                    withContext(NonCancellable) { resume.complete(Unit) }
                }
                val refusal = withTimeout(5_000) { writer.await() }.exceptionOrNull()
                assertTrue(refusal is IdentityWriteRefusedException)
                assertEquals(if (switch) IdentityFenceOutcome.CHANGED else IdentityFenceOutcome.HELD, refusal.outcome)
                assertOriginal()
            }
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val repo = AtprotoDid.require(requireNotNull(owner.did))
        val metadata = PostgreSQLLogDateCollectionsMetadataStore()
        val blocks = PostgreSQLRepoBlockStore()
        private val cid = Cid.sha256(0x71, "seed".encodeToByteArray())
        val head = RepoHead(repo, cid, cid, 1)
        private var original: app.logdate.server.logdate.LogDateCollectionsState? = null

        suspend fun seed() {
            metadata.upsert(id, repo, LogDateCollectionKind.ENTRY, "entry")
            metadata.delete(id, repo, LogDateCollectionKind.MOMENT, "moment", 2, allowMissing = true)
            original = metadata.state(id)
            blocks.write(id) { blocks.writeHead(head).getOrThrow() }
        }

        suspend fun assertOriginal() {
            assertEquals(original, metadata.state(id))
            assertNotNull(metadata.metadata(id, LogDateCollectionKind.ENTRY, "entry"))
            assertNotNull(metadata.tombstone(id, LogDateCollectionKind.MOMENT, "moment"))
            assertEquals(head, blocks.readHead(repo).getOrThrow())
        }

        suspend fun migration(heldAt: Instant?): SpaceMigration {
            val now = Clock.System.now()
            val claim = SpaceMigration(id, "legacy-move", SpaceMigrationPhase.UPLOADING, now, now, holdStartedAt = heldAt)
            assertTrue(PostgreSQLSpaceMigrationStore().replace(null, claim))
            return claim
        }

        suspend fun move() {
            val claim = migration(null)
            assertTrue(
                PostgreSQLSpaceMigrationStore().replaceAndSwitch(
                    claim,
                    claim.next(Clock.System.now()) { copy(phase = SpaceMigrationPhase.SWITCHED, switchedAt = Clock.System.now()) },
                    AccountStorageMode.LEGACY,
                    AccountStorageMode.PERSONAL_SPACE_V1,
                ),
            )
            assertEquals(0L, PostgreSQLAccountRepository().findById(owner.id)?.identityEpoch)
        }
    }

    private suspend fun assertChanged(action: suspend () -> Unit) {
        assertEquals(IdentityFenceOutcome.CHANGED, assertFailsWith<IdentityWriteRefusedException> { action() }.outcome)
    }

    private fun withFixture(action: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Legacy owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            Fixture(source, owner).apply { seed() }.action()
        }
}
