package app.logdate.server.database

import app.logdate.server.database.support.withH2Database
import app.logdate.server.devices.DeviceLinkCode
import app.logdate.server.devices.DeviceSession
import app.logdate.server.devices.DeviceSessionLimits
import app.logdate.server.devices.DeviceSessionRepository
import app.logdate.server.devices.InMemoryDeviceSessionRepository
import app.logdate.server.devices.LINK_CODE_RATE_WINDOW_MS
import app.logdate.server.devices.LinkCodeCreation
import app.logdate.server.devices.LinkCodeRedemption
import app.logdate.server.devices.NewDeviceSession
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Runs one contract against the PostgreSQL store (on H2) and the in-memory store. */
@OptIn(ExperimentalUuidApi::class)
class PostgreSQLDeviceSessionRepositoryTest {
    private val now = 1_800_000_000_000L
    private val roomy = DeviceSessionLimits(maxActiveSessions = 5, maxCodesPerHour = 10)

    @Test
    fun `both stores spend each link code once and keep sessions per account`() =
        withDeviceTables { owner, other ->
            assertLinkCodesSpendOnce(PostgreSQLDeviceSessionRepository(), owner, other)
            assertLinkCodesSpendOnce(InMemoryDeviceSessionRepository(), owner, other)
        }

    @Test
    fun `both stores enforce the device and hourly code limits`() =
        withDeviceTables { owner, _ ->
            assertLimits(PostgreSQLDeviceSessionRepository(), owner)
            assertLimits(InMemoryDeviceSessionRepository(), owner)
        }

    @Test
    fun `both stores leave idle sessions out of the device limit without revoking them`() =
        withDeviceTables { owner, _ ->
            assertIdleSessionsDoNotCount(PostgreSQLDeviceSessionRepository(), owner)
            assertIdleSessionsDoNotCount(InMemoryDeviceSessionRepository(), owner)
        }

    @Test
    fun `both stores revoke and extend only the right sessions`() =
        withDeviceTables { owner, other ->
            assertRevokeAndExtend(PostgreSQLDeviceSessionRepository(), owner, other)
            assertRevokeAndExtend(InMemoryDeviceSessionRepository(), owner, other)
        }

    private suspend fun assertLinkCodesSpendOnce(
        repository: DeviceSessionRepository,
        owner: UUID,
        other: UUID,
    ) {
        val hash = randomHash()
        assertEquals(LinkCodeCreation.CREATED, repository.createLinkCode(DeviceLinkCode(hash, owner, now, now + 120_000), roomy))

        val redeemed = repository.redeemLinkCode(hash, newSession("Pixel Watch 3", now + 1_000), roomy)
        val session = assertIs<LinkCodeRedemption.Redeemed>(redeemed).session

        assertEquals(owner, session.accountId)
        assertEquals("Pixel Watch 3", session.deviceName)
        assertEquals(now + 1_000, session.lastRefreshedAt)
        assertEquals(LinkCodeRedemption.Invalid, repository.redeemLinkCode(hash, newSession("Second watch", now + 2_000), roomy))
        assertEquals(LinkCodeRedemption.Invalid, repository.redeemLinkCode(randomHash(), newSession("Unknown", now + 2_000), roomy))

        val expired = randomHash()
        repository.createLinkCode(DeviceLinkCode(expired, owner, now, now + 120_000), roomy)
        assertEquals(LinkCodeRedemption.Invalid, repository.redeemLinkCode(expired, newSession("Late watch", now + 120_000), roomy))

        assertEquals(session, repository.find(session.id))
        assertEquals(listOf(session), repository.listActive(owner, now + 3_000))
        assertTrue(repository.listActive(other, now + 3_000).isEmpty())
    }

    private suspend fun assertLimits(
        repository: DeviceSessionRepository,
        owner: UUID,
    ) {
        val oneDevice = DeviceSessionLimits(maxActiveSessions = 1, maxCodesPerHour = 3)
        val first = randomHash()
        val pending = randomHash()
        repository.createLinkCode(DeviceLinkCode(first, owner, now, now + 120_000), oneDevice)
        assertEquals(LinkCodeCreation.CREATED, repository.createLinkCode(DeviceLinkCode(pending, owner, now + 1, now + 120_001), oneDevice))
        assertIs<LinkCodeRedemption.Redeemed>(repository.redeemLinkCode(first, newSession("Watch", now + 2), oneDevice))

        assertEquals(LinkCodeRedemption.DeviceLimitReached, repository.redeemLinkCode(pending, newSession("Second", now + 3), oneDevice))
        assertEquals(
            LinkCodeCreation.DEVICE_LIMIT_REACHED,
            repository.createLinkCode(DeviceLinkCode(randomHash(), owner, now + 4, now + 120_004), oneDevice),
        )

        val hourly = DeviceSessionLimits(maxActiveSessions = 5, maxCodesPerHour = 3)
        assertEquals(
            LinkCodeCreation.CREATED,
            repository.createLinkCode(DeviceLinkCode(randomHash(), owner, now + 5, now + 120_005), hourly),
        )
        assertEquals(
            LinkCodeCreation.RATE_LIMITED,
            repository.createLinkCode(DeviceLinkCode(randomHash(), owner, now + 6, now + 120_006), hourly),
        )
        val nextHour = now + LINK_CODE_RATE_WINDOW_MS + 200_000
        assertEquals(
            LinkCodeCreation.CREATED,
            repository.createLinkCode(DeviceLinkCode(randomHash(), owner, nextHour, nextHour + 120_000), hourly),
        )
    }

    private suspend fun assertIdleSessionsDoNotCount(
        repository: DeviceSessionRepository,
        owner: UUID,
    ) {
        val twoDevices = DeviceSessionLimits(maxActiveSessions = 2, maxCodesPerHour = 10, idleAfterMs = 1_000)
        val oldest = redeemOne(repository, owner, now, twoDevices)
        val newer = redeemOne(repository, owner, now + 100, twoDevices)
        assertEquals(
            LinkCodeCreation.DEVICE_LIMIT_REACHED,
            repository.createLinkCode(DeviceLinkCode(randomHash(), owner, now + 500, now + 120_500), twoDevices),
        )

        val hash = randomHash()
        val later = now + 1_050
        assertEquals(LinkCodeCreation.CREATED, repository.createLinkCode(DeviceLinkCode(hash, owner, later, later + 120_000), twoDevices))
        val redeemed = assertIs<LinkCodeRedemption.Redeemed>(repository.redeemLinkCode(hash, newSession("Third", later), twoDevices))

        assertNull(repository.find(oldest.id)?.revokedAt, "an idle session is never revoked to make room")
        assertEquals(
            listOf(oldest.id, newer.id, redeemed.session.id),
            repository.listActive(owner, later).map(DeviceSession::id),
        )

        assertTrue(repository.extend(oldest.id, oldest.expiresAt, later + 10))
        val pending = randomHash()
        repository.createLinkCode(DeviceLinkCode(pending, owner, later, later + 120_000), roomy)
        assertEquals(
            LinkCodeRedemption.DeviceLimitReached,
            repository.redeemLinkCode(pending, newSession("Fourth", later + 20), twoDevices),
            "a session that is used again takes its place back",
        )
        repository.listActive(owner, later).forEach { repository.revoke(owner, it.id, later) }
    }

    private suspend fun assertRevokeAndExtend(
        repository: DeviceSessionRepository,
        owner: UUID,
        other: UUID,
    ) {
        val session = redeemOne(repository, owner, now)
        assertFalse(repository.revoke(other, session.id, now + 10))
        assertTrue(repository.revoke(owner, session.id, now + 10))
        assertTrue(repository.revoke(owner, session.id, now + 20))
        assertEquals(now + 10, repository.find(session.id)?.revokedAt)
        assertTrue(repository.listActive(owner, now + 30).isEmpty())
        assertFalse(repository.extend(session.id, now + 999_999, now + 40))

        val live = redeemOne(repository, owner, now + 50)
        assertTrue(repository.extend(live.id, now + 9_999_999, now + 60))
        val extended = repository.find(live.id)
        assertEquals(now + 9_999_999, extended?.expiresAt)
        assertEquals(now + 60, extended?.lastRefreshedAt)
        assertEquals(listOf(live.id), repository.listActive(owner, now + 9_999_998).map(DeviceSession::id))
        assertTrue(repository.listActive(owner, now + 9_999_999).isEmpty())
    }

    private suspend fun redeemOne(
        repository: DeviceSessionRepository,
        owner: UUID,
        at: Long,
        limits: DeviceSessionLimits = roomy,
    ): DeviceSession {
        val hash = randomHash()
        repository.createLinkCode(DeviceLinkCode(hash, owner, at, at + 120_000), limits)
        return assertIs<LinkCodeRedemption.Redeemed>(repository.redeemLinkCode(hash, newSession("Pixel Watch 3", at + 1), limits)).session
    }

    private fun newSession(
        name: String,
        at: Long,
    ): NewDeviceSession = NewDeviceSession(Uuid.random().toJavaUUID(), name, "wear_os", at, at + 30L * 24 * 60 * 60 * 1_000)

    private fun randomHash(): String = Uuid.random().toHexString() + Uuid.random().toHexString()

    private fun withDeviceTables(block: suspend (owner: UUID, other: UUID) -> Unit) =
        withH2Database(AccountsTable, DeviceSessionsTable, DeviceLinkCodesTable) {
            val owner = Uuid.random().toJavaUUID()
            val other = Uuid.random().toJavaUUID()
            transaction {
                listOf(owner, other).forEachIndexed { index, id ->
                    AccountsTable.insert {
                        it[AccountsTable.id] = id
                        it[username] = "device-session-$index"
                        it[displayName] = "Test account $index"
                        it[createdAt] = Clock.System.now()
                        it[isActive] = true
                        it[preferences] = "{}"
                    }
                }
            }
            runBlocking { block(owner, other) }
        }
}
