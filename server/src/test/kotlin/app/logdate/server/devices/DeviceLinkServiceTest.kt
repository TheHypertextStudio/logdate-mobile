package app.logdate.server.devices

import app.logdate.server.auth.AccessTokenClaims
import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.auth.TokenScope
import app.logdate.server.database.toJavaUUID
import app.logdate.server.oauth.MutableClock
import app.logdate.shared.model.devices.DeviceLinkRedeemRequest
import app.logdate.shared.model.devices.DeviceLinkRedeemResponse
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class DeviceLinkServiceTest {
    private val start = Instant.parse("2026-10-03T12:00:00Z")
    private val clock = MutableClock(start)
    private val tokens = JwtTokenService("device-link-service-secret")
    private val accounts = InMemoryAccountRepository()
    private val repository = InMemoryDeviceSessionRepository()
    private val guard = DeviceSessionGuard(repository, cacheTtl = 30.seconds, clock = clock)
    private val service = DeviceLinkService(repository, tokens, accounts, guard, clock)
    private var accountCount = 0

    @Test
    fun `a code links one device and then stops working`() =
        runTest {
            val owner = newAccount()
            val minted = assertIs<DeviceLinkMint.Minted>(service.mint(owner)).code

            assertEquals(43, minted.code.length)
            assertEquals(start.toEpochMilliseconds() + DeviceLinkService.LINK_CODE_LIFETIME_MS, minted.expiresAt)
            assertFalse(minted.code in minted.toString())

            val linked = redeem(minted.code, "Pixel Watch 3")
            assertEquals(owner.toString(), linked.accountId)
            assertEquals(
                AccessTokenClaims(owner.toString(), null, linked.sessionId, TokenScope.SYNC),
                tokens.decodeAccessToken(linked.tokens.accessToken),
            )
            assertEquals(linked.sessionId, tokens.decodeRefreshToken(linked.tokens.refreshToken)?.sessionId)
            assertFalse(linked.tokens.accessToken in linked.toString())
            assertEquals(DeviceLinkRedemption.InvalidCode, service.redeem(DeviceLinkRedeemRequest(minted.code, "Second watch")))
        }

    @Test
    fun `expired codes and malformed requests are refused without spending the code`() =
        runTest {
            val owner = newAccount()
            val stale = assertIs<DeviceLinkMint.Minted>(service.mint(owner)).code
            clock.nowValue = start + 121.seconds
            assertEquals(DeviceLinkRedemption.InvalidCode, service.redeem(DeviceLinkRedeemRequest(stale.code, "Pixel Watch 3")))

            val fresh = assertIs<DeviceLinkMint.Minted>(service.mint(owner)).code
            listOf("not-a-code", fresh.code + "=", fresh.code.dropLast(1)).forEach { code ->
                assertEquals(DeviceLinkRedemption.InvalidCode, service.redeem(DeviceLinkRedeemRequest(code, "Pixel Watch 3")), code)
            }
            assertEquals(DeviceLinkRedemption.InvalidDevice, service.redeem(DeviceLinkRedeemRequest(fresh.code, "   ")))
            assertEquals(DeviceLinkRedemption.InvalidDevice, service.redeem(DeviceLinkRedeemRequest(fresh.code, "Bell\u0007")))
            assertEquals(DeviceLinkRedemption.InvalidDevice, service.redeem(DeviceLinkRedeemRequest(fresh.code, "Watch", "Wear OS")))
            assertIs<DeviceLinkRedemption.Redeemed>(service.redeem(DeviceLinkRedeemRequest(fresh.code, "  Pixel Watch 3 ")))
        }

    @Test
    fun `an account links at most five devices and mints at most ten codes an hour`() =
        runTest {
            val crowded = newAccount()
            val linked = (1..DeviceLinkService.MAX_ACTIVE_DEVICES).map { link(crowded, "Watch $it") }
            assertEquals(DeviceLinkMint.DeviceLimitReached, service.mint(crowded))
            assertTrue(service.revoke(crowded, UUID.fromString(linked.first().sessionId)))
            assertIs<DeviceLinkMint.Minted>(service.mint(crowded))

            val busy = newAccount()
            repeat(DeviceLinkService.MAX_LINK_CODES_PER_HOUR) { assertIs<DeviceLinkMint.Minted>(service.mint(busy)) }
            assertEquals(DeviceLinkMint.RateLimited, service.mint(busy))
            clock.nowValue = start + 61.minutes
            assertIs<DeviceLinkMint.Minted>(service.mint(busy))
        }

    @Test
    fun `a device unused for two weeks stops counting toward the limit but stays linked`() =
        runTest {
            val owner = newAccount()
            val linked = (1..DeviceLinkService.MAX_ACTIVE_DEVICES).map { link(owner, "Watch $it") }
            val idle = linked.first()
            clock.nowValue = start + 13.days
            linked.drop(1).forEach { service.refresh(assertNotNull(tokens.decodeRefreshToken(it.tokens.refreshToken))) }
            assertEquals(DeviceLinkMint.DeviceLimitReached, service.mint(owner), "no device has been idle for two weeks yet")

            clock.nowValue = start + 14.days
            val added = link(owner, "New watch")

            val names = service.list(owner, currentSessionId = null).map { it.deviceName }
            assertEquals(listOf("Watch 1", "Watch 2", "Watch 3", "Watch 4", "Watch 5", "New watch"), names)
            assertTrue(guard.isActive(owner.toString(), idle.sessionId), "the idle device is never signed out to make room")
            assertIs<DeviceSessionRefresh.Refreshed>(service.refresh(assertNotNull(tokens.decodeRefreshToken(idle.tokens.refreshToken))))
            assertTrue(guard.isActive(owner.toString(), added.sessionId))
            assertEquals(DeviceLinkMint.DeviceLimitReached, service.mint(owner), "the idle device counts again once it is used")
        }

    @Test
    fun `a session that ran out unused is expired rather than revoked`() =
        runTest {
            val owner = newAccount()
            val linked = link(owner)
            val claims = assertNotNull(tokens.decodeRefreshToken(linked.tokens.refreshToken))

            clock.nowValue = start + DeviceLinkService.SESSION_LIFETIME_MS.milliseconds + 1.seconds
            assertEquals(DeviceSessionRefresh.Expired, service.refresh(claims))

            assertTrue(service.revoke(owner, UUID.fromString(linked.sessionId)))
            assertEquals(DeviceSessionRefresh.Revoked, service.refresh(claims), "a removed session is revoked even after it ran out")
        }

    @Test
    fun `refresh records that a device is in use at most once an hour`() =
        runTest {
            val owner = newAccount()
            val claims = assertNotNull(tokens.decodeRefreshToken(link(owner).tokens.refreshToken))

            clock.nowValue = start + 59.minutes
            service.refresh(claims)
            assertEquals(start.toEpochMilliseconds(), service.list(owner, currentSessionId = null).single().lastRefreshedAt)

            clock.nowValue = start + 3.days
            assertIs<DeviceSessionRefresh.Refreshed>(service.refresh(claims))
            val device = service.list(owner, currentSessionId = null).single()
            assertEquals((start + 3.days).toEpochMilliseconds(), device.lastRefreshedAt)
            assertEquals(start.toEpochMilliseconds() + DeviceLinkService.SESSION_LIFETIME_MS, device.expiresAt)
        }

    @Test
    fun `refresh renews a session only in its last fifteen days and stops after revocation`() =
        runTest {
            val owner = newAccount()
            val linked = link(owner)
            val claims = assertNotNull(tokens.decodeRefreshToken(linked.tokens.refreshToken))

            val early = assertIs<DeviceSessionRefresh.Refreshed>(service.refresh(claims))
            assertNull(early.refreshToken)
            assertEquals(linked.sessionId, tokens.decodeAccessToken(early.accessToken)?.sessionId)

            clock.nowValue = start + 16.days
            val renewed = assertIs<DeviceSessionRefresh.Refreshed>(service.refresh(claims))
            assertEquals(linked.sessionId, tokens.decodeRefreshToken(assertNotNull(renewed.refreshToken))?.sessionId)
            val device = service.list(owner, currentSessionId = null).single()
            assertEquals((start + 16.days).toEpochMilliseconds() + DeviceLinkService.SESSION_LIFETIME_MS, device.expiresAt)
            assertEquals((start + 16.days).toEpochMilliseconds(), device.lastRefreshedAt)

            assertEquals(DeviceSessionRefresh.Revoked, service.refresh(claims.copy(accountId = newAccount().toString())))
            assertTrue(service.revoke(owner, UUID.fromString(linked.sessionId)))
            assertEquals(DeviceSessionRefresh.Revoked, service.refresh(claims))
        }

    @Test
    fun `the guard caches answers but revoking through the service applies at once`() =
        runTest {
            val owner = newAccount()
            val first = link(owner)
            assertTrue(guard.isActive(owner.toString(), first.sessionId))
            assertFalse(guard.isActive(newAccount().toString(), first.sessionId))

            repository.revoke(owner, UUID.fromString(first.sessionId), start.toEpochMilliseconds())
            assertTrue(guard.isActive(owner.toString(), first.sessionId), "a cached answer lasts up to 30 seconds")
            clock.nowValue = start + 31.seconds
            assertFalse(guard.isActive(owner.toString(), first.sessionId))

            val second = link(owner)
            assertTrue(guard.isActive(owner.toString(), second.sessionId))
            service.revoke(owner, UUID.fromString(second.sessionId))
            assertFalse(guard.isActive(owner.toString(), second.sessionId))
            assertFalse(DeviceSessionGuard.Disabled.isActive(owner.toString(), second.sessionId))
        }

    @Test
    fun `the device list marks the calling session`() =
        runTest {
            val owner = newAccount()
            val watch = link(owner, "Pixel Watch 3")
            link(owner, "Galaxy Watch")

            val devices = service.list(owner, currentSessionId = watch.sessionId)

            assertEquals(listOf("Pixel Watch 3", "Galaxy Watch"), devices.map { it.deviceName })
            assertEquals(listOf(true, false), devices.map { it.current })
            assertTrue(devices.all { it.platform == "wear_os" })
        }

    private suspend fun link(
        accountId: UUID,
        name: String = "Pixel Watch 3",
    ): DeviceLinkRedeemResponse = redeem(assertIs<DeviceLinkMint.Minted>(service.mint(accountId)).code.code, name)

    private suspend fun redeem(
        code: String,
        name: String,
    ): DeviceLinkRedeemResponse = assertIs<DeviceLinkRedemption.Redeemed>(service.redeem(DeviceLinkRedeemRequest(code, name))).response

    private suspend fun newAccount(): UUID {
        val id = Uuid.random()
        accountCount += 1
        accounts.create(Account(id = id, username = "owner_$accountCount", displayName = "Owner", createdAt = Clock.System.now()))
        return id.toJavaUUID()
    }
}
