package app.logdate.client.sync.peer.delivery

import app.logdate.client.database.dao.sync.PeerDeliveryDao
import app.logdate.client.database.entities.sync.PeerDeliveryEntity
import app.logdate.client.sync.metadata.EntityType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/** Tests [PeerDeliveryLedger]: one row per record version that only ever moves forward. */
class PeerDeliveryLedgerTest {
    private var now = 1_000L
    private val dao = MemoryPeerDeliveryDao()
    private val ledger =
        PeerDeliveryLedger(
            dao,
            object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(now)
            },
        )

    @Test
    fun `a record is tracked as new at its version`() =
        runTest {
            val entry = track("a", version = 5)

            assertEquals(DeliveryState.NEW, entry.state)
            assertEquals(5, entry.contentVersion)
            assertEquals(1_000, entry.stateChangedAt)
        }

    @Test
    fun `tracking the same version again leaves its state unchanged`() =
        runTest {
            ledger.moveTo(track("a", version = 5), DeliveryState.SENT)

            assertEquals(DeliveryState.SENT, track("a", version = 5).state)
        }

    @Test
    fun `a newer version starts over`() =
        runTest {
            ledger.moveTo(track("a", version = 5), DeliveryState.ON_PHONE)

            assertEquals(DeliveryState.NEW, track("a", version = 6).state)
        }

    @Test
    fun `a receipt for the current version moves the record forward`() =
        runTest {
            track("a", version = 5)

            assertEquals(DeliveryState.BACKED_UP, receipt("a", version = 5, DeliveryState.BACKED_UP)?.state)
        }

    @Test
    fun `a receipt for an older version is ignored`() =
        runTest {
            track("a", version = 6)

            assertNull(receipt("a", version = 5, DeliveryState.ON_PHONE))
            assertEquals(DeliveryState.NEW, get("a")?.state)
        }

    @Test
    fun `a late receipt never moves a record back`() =
        runTest {
            track("a", version = 5)
            receipt("a", version = 5, DeliveryState.BACKED_UP)

            assertNull(receipt("a", version = 5, DeliveryState.ON_PHONE))
            assertEquals(DeliveryState.BACKED_UP, get("a")?.state)
        }

    @Test
    fun `a record waiting for the cloud moves only to backed up or deleted`() =
        runTest {
            ledger.moveTo(track("a", version = 5), DeliveryState.CLOUD_PENDING)

            assertNull(receipt("a", version = 5, DeliveryState.ON_PHONE))
            assertEquals(DeliveryState.BACKED_UP, receipt("a", version = 5, DeliveryState.BACKED_UP)?.state)
        }

    @Test
    fun `records no longer queued are forgotten`() =
        runTest {
            track("a", version = 1)
            track("b", version = 1)

            assertEquals(1, ledger.forgetAllExcept(PeerScopes.PHONE, DeliveryDirection.TO_PHONE, EntityType.NOTE, keep = setOf("a")))
            assertNull(get("b"))
        }

    @Test
    fun `a wait records its reason and when it is due`() =
        runTest {
            val waiting = ledger.moveTo(track("a", version = 1), DeliveryState.NEW, HoldReason.PHONE_UNREACHABLE, nextAttemptAt = 2_000)

            assertEquals(HoldReason.PHONE_UNREACHABLE, get("a")?.holdReason)
            assertEquals(2_000, ledger.earliestDue(PeerScopes.PHONE, DeliveryDirection.TO_PHONE))
            assertEquals(1, ledger.recordAttempt(waiting, nextAttemptAt = 3_000).attempts)
        }

    @Test
    fun `tracking an older snapshot cannot replace the newer delivery`() =
        runTest {
            ledger.moveTo(track("a", 6), DeliveryState.ON_PHONE)
            assertEquals(6, track("a", 5).contentVersion)
            assertEquals(DeliveryState.ON_PHONE, get("a")?.state)
        }

    @Test
    fun `an old version cannot overwrite a newer delivery when its send finishes`() =
        runTest {
            val old = track("a", 5)
            track("a", 6)
            ledger.moveTo(old, DeliveryState.SENT)
            assertEquals(6, get("a")?.contentVersion)
            assertEquals(DeliveryState.NEW, get("a")?.state)
        }

    @Test
    fun `a receipt received during sending cannot be undone by the sender`() =
        runTest {
            val sending = track("a", 5)
            receipt("a", 5, DeliveryState.BACKED_UP)
            ledger.moveTo(sending, DeliveryState.SENT, nextAttemptAt = 2_000)
            assertEquals(DeliveryState.BACKED_UP, get("a")?.state)
            assertNull(get("a")?.nextAttemptAt)
        }

    @Test
    fun `a late attempt cannot requeue a settled delivery`() =
        runTest {
            val sending = track("a", 5)
            receipt("a", 5, DeliveryState.BACKED_UP)
            ledger.recordAttempt(sending, nextAttemptAt = 2_000)
            assertEquals(DeliveryState.BACKED_UP, get("a")?.state)
            assertNull(get("a")?.nextAttemptAt)
        }

    @Test
    fun `repeated attempts count from durable state rather than a stale snapshot`() =
        runTest {
            val sending = track("a", 5)
            ledger.recordAttempt(sending, nextAttemptAt = 2_000)
            assertEquals(2, ledger.recordAttempt(sending, nextAttemptAt = 3_000).attempts)
        }

    @Test
    fun `ledger updates preserve the version already attempted in the cloud`() =
        runTest {
            track("a", 5)
            val row = checkNotNull(dao.get(PeerScopes.PHONE, DeliveryDirection.TO_PHONE.name, EntityType.NOTE.name, "a"))
            dao.upsert(row.copy(lastCloudAttemptVersion = 5))
            ledger.moveTo(checkNotNull(get("a")), DeliveryState.CLOUD_PENDING)
            assertEquals(5, dao.get(PeerScopes.PHONE, DeliveryDirection.TO_PHONE.name, EntityType.NOTE.name, "a")?.lastCloudAttemptVersion)
        }

    @Test
    fun `concurrent attempts cannot lose increments across suspended database reads`() =
        runTest {
            val backing = MemoryPeerDeliveryDao()
            val suspendedDao =
                object : PeerDeliveryDao by backing {
                    override suspend fun get(
                        peerScope: String,
                        direction: String,
                        entityType: String,
                        entityId: String,
                    ): PeerDeliveryEntity? {
                        val row = backing.get(peerScope, direction, entityType, entityId)
                        yield()
                        return row
                    }
                }
            val concurrent = PeerDeliveryLedger(suspendedDao)
            val entry = concurrent.track(PeerScopes.PHONE, DeliveryDirection.TO_PHONE, EntityType.NOTE, "a", 5)
            List(20) { async { concurrent.recordAttempt(entry, nextAttemptAt = null) } }.awaitAll()
            assertEquals(20, concurrent.get(PeerScopes.PHONE, DeliveryDirection.TO_PHONE, EntityType.NOTE, "a")?.attempts)
        }

    @Test
    fun `a current snapshot still cannot move an acknowledged record backwards`() =
        runTest {
            val acknowledged = ledger.moveTo(track("a", 5), DeliveryState.ON_PHONE)
            ledger.moveTo(acknowledged, DeliveryState.NEW)
            assertEquals(DeliveryState.ON_PHONE, get("a")?.state)
        }

    @Test
    fun `an acknowledged record can be released to cloud fallback`() =
        runTest {
            val acknowledged = ledger.moveTo(track("a", 5), DeliveryState.ON_PHONE)
            assertEquals(DeliveryState.CLOUD_PENDING, ledger.moveTo(acknowledged, DeliveryState.CLOUD_PENDING).state)
        }

    @Test
    fun `a forgotten delivery is not resurrected by a delayed send`() =
        runTest {
            val sending = track("a", 5)
            ledger.forget(PeerScopes.PHONE, DeliveryDirection.TO_PHONE, EntityType.NOTE, "a")
            ledger.recordAttempt(sending, nextAttemptAt = 2_000)
            ledger.moveTo(sending, DeliveryState.SENT)
            assertNull(get("a"))
        }

    @Test
    fun `concurrent repeated rejections report only one forward transition`() =
        runTest {
            track("a", version = 5)
            val applied =
                (1..20)
                    .map {
                        async { receipt("a", Long.MAX_VALUE, DeliveryState.REJECTED) }
                    }.awaitAll()
            assertEquals(1, applied.count { it != null })
            assertEquals(DeliveryState.REJECTED, get("a")?.state)
        }

    private suspend fun track(
        id: String,
        version: Long,
    ) = ledger.track(PeerScopes.PHONE, DeliveryDirection.TO_PHONE, EntityType.NOTE, id, version)

    private suspend fun get(id: String) = ledger.get(PeerScopes.PHONE, DeliveryDirection.TO_PHONE, EntityType.NOTE, id)

    private suspend fun receipt(
        id: String,
        version: Long,
        state: DeliveryState,
    ) = ledger.applyReceipt(PeerScopes.PHONE, DeliveryDirection.TO_PHONE, EntityType.NOTE, id, version, state)
}
