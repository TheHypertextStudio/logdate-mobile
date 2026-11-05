package app.logdate.server.migration

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryJournalMergeStore
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.server.space.InMemorySpaceStore
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.sync.InMemoryLocationHistoryRepository
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.LocationHistoryUpload
import app.logdate.shared.model.sync.SpaceMigrationSwitchRequest
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toKotlinUuid

/** A clock a test moves by hand. */
internal class TestClock(
    var now: Instant = Instant.fromEpochMilliseconds(1_790_000_000_000),
) : Clock {
    override fun now(): Instant = now

    fun advance(by: Duration) {
        now += by
    }
}

/** Every in-memory piece the migration touches, wired together for one test. */
@OptIn(ExperimentalUuidApi::class)
internal class MigrationFixture(
    val config: SpaceMigrationConfig = SpaceMigrationConfig(holdGrace = 2.minutes),
) {
    val clock = TestClock()
    val accounts = InMemoryAccountRepository()
    val store = InMemorySpaceMigrationStore(accounts)
    val spaces = InMemorySpaceStore()
    val metadata = InMemoryLogDateCollectionsMetadataStore()
    val history = InMemoryLocationHistoryRepository()
    val mediaRecords = InMemoryLogDateMediaRepository()
    val atprotoBlobs = InMemoryLogDateAtprotoBlobRepository()
    val media = CompositeLogDateMediaBlobRepository(mediaRecords, atprotoBlobs)
    val storage = InMemoryLogDateBlobStorage()
    val repositories = InMemoryRepoBlockStore()
    val merges = InMemoryJournalMergeStore()
    val inventory = LegacyInventoryReader(metadata, history, media)
    val service = SpaceMigrationService(store, accounts, spaces, inventory, config, clock)
    val holds = SpaceMigrationHolds(store, config, clock)
    val eraser = InMemoryLegacyJournalEraser(accounts, store, repositories, metadata, history, mediaRecords, atprotoBlobs, merges, storage)
    val cleanup = LegacyCopyCleanup(store, accounts, eraser, config, clock)
    val copier = LegacyMediaCopier(media, storage, spaces)

    suspend fun legacyAccount(did: String? = randomPlcDid()): UUID {
        val id = UUID.randomUUID()
        accounts.create(
            Account(
                id = id.toKotlinUuid(),
                username = "mover${id.toString().take(8)}",
                displayName = "Mover",
                did = did,
                createdAt = clock.now(),
                storageMode = AccountStorageMode.LEGACY,
            ),
        )
        return id
    }

    /** Puts [accountId] in its personal space the way a new account starts there, with no move. */
    suspend fun moveWithoutMigration(accountId: UUID) {
        accounts.updateStorageModeIfUnchanged(accountId.toKotlinUuid(), AccountStorageMode.LEGACY, AccountStorageMode.PERSONAL_SPACE_V1)
    }

    suspend fun did(accountId: UUID): String = requireNotNull(accounts.findById(accountId.toKotlinUuid())?.did)

    suspend fun mode(accountId: UUID): AccountStorageMode? = accounts.findById(accountId.toKotlinUuid())?.storageMode

    suspend fun addLegacyEntry(
        accountId: UUID,
        id: String,
    ) {
        metadata.upsert(accountId, AtprotoDid.require(did(accountId)), LogDateCollectionKind.ENTRY, id)
    }

    fun addLocationRecord(
        accountId: UUID,
        id: String,
    ) {
        history.upload(accountId, listOf(LocationHistoryUpload(id, "place", "LDSE2:opaque", deviceId = "device", deviceVersion = 1)))
    }

    suspend fun putSpaceRecord(
        accountId: UUID,
        rkey: String,
    ) {
        val did = did(accountId)
        spaces.put(SpaceRecordWrite(accountId, SpaceRef.personal(did).uri, did, rkey, sealed(), emptyList(), null, false))
    }

    /** The proof a device that checked everything correctly sends. */
    suspend fun proof(
        accountId: UUID,
        migrationId: String,
    ): SpaceMigrationSwitchRequest {
        val legacy = inventory.read(accountId)
        val did = did(accountId)
        val index = spaces.index(SpaceRef.personal(did).uri, did)
        return SpaceMigrationSwitchRequest(
            migrationId = migrationId,
            legacyWatermark = legacy.watermark,
            legacyCounts = legacy.kinds.associate { it.kind to it.count },
            spaceSeq = index.lastSeq,
            spaceRecordCount = index.live.size,
            spaceDigest = index.digest,
        )
    }
}

internal fun sealed(): JsonObject =
    buildJsonObject {
        put("\$type", "app.logdate.crypto.sealed")
        put("version", 1)
        put("keyId", "abcdefghijklm")
        putJsonObject("nonce") { put("\$bytes", Base64.encode(ByteArray(12))) }
        putJsonObject("ciphertext") { put("\$bytes", Base64.encode(ByteArray(528))) }
    }

/** A did:plc of random letters, as a new account would have. */
internal fun randomPlcDid(): String = "did:plc:" + (1..24).map { "abcdefghijklmnopqrstuvwxyz234567".random() }.joinToString("")
