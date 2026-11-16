package app.logdate.client.device.crypto

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

/** Pins the platform-provider sealer to the same Swift fixture `AndroidDeviceTransferTest` uses. */
class CryptographyDeviceTransferTest {
    private val fixedSender =
        CryptographyDeviceTransfer(
            privateKey = ByteArray(32) { 0x22 },
            nonce = { ByteArray(12) { 0x15 } },
        )

    /**
     * The JDK provider pools its GCM cipher and refuses a key and IV pair it just used, which both
     * fixtures share; sealing once under another IV first lets each fixture run in any order.
     */
    private suspend fun sealFixture(session: DeviceTransferSession? = null): String {
        CryptographyDeviceTransfer(privateKey = ByteArray(32) { 0x22 }, nonce = { ByteArray(12) { 0x16 } }).seal(contents())
        return fixedSender.seal(contents(session))
    }

    private fun contents(session: DeviceTransferSession? = null) =
        DeviceTransferContents(
            recipientPublicKey = "e06Qm75__kTEZaIgA31gjuNYl9Me-XLwf3SJLLD3PxM",
            identityKey = ByteArray(32) { 0x31 },
            legacyMediaKey = ByteArray(32) { 0x44 },
            accountId = "61ad68f2-6d4b-42dd-b263-f838195714ad",
            requestId = Uuid.parse("4d90d8dc-49b2-4ba1-8c20-529b80c77542"),
            confirmationCode = "413827",
            session = session,
        )

    private fun field(
        envelope: String,
        name: String,
    ) = Json
        .parseToJsonElement(envelope)
        .jsonObject
        .getValue(name)
        .jsonPrimitive
        .content

    @Test
    fun `sealed approval matches Swift transfer fixture`() =
        runTest {
            val envelope = sealFixture()

            assertEquals("1", field(envelope, "v"))
            assertEquals("D6poTtKIZ7l_Smot7l34zpdOdrcBjj8iocTPJnhXDyA", field(envelope, "pk"))
            assertEquals("FRUVFRUVFRUVFRUV", field(envelope, "iv"))
            assertEquals(
                "o2eUQFsBL6GesSpQuypKWoCB59grVlAF4PamiEuzHgchOKQw4rLmd9weueWLiNwXmH6De2tiDYMHUrqtCwapY7s5cQVNmGHmCHpnAlgAl5LMYQYFrWSLN_FayGkUIKSz8lwmLgPVO-GPxbw7nb7-qBUEiiXCzZ6z3WtR6NI0MhzkvEsUpCC__3INf5w",
                field(envelope, "ct"),
            )
        }

    @Test
    fun `phone first transfer includes the account session for Swift`() =
        runTest {
            val envelope = sealFixture(DeviceTransferSession("Willie", "access", "refresh"))

            assertEquals(
                "o2eUQFsBL6GesSpQuypKWoCB59grVlAF4PamiEuzHgchOKQw4rLmd9weueWLiNwXmH6De2tiDYMHUrqtCwapY7s5cQVNmGHmCHpnAlgAl5LMYQYFrWSLN_FayGkUIKSz8lwmLgPVO-GPxbw7nb7-qBUEiiXCzZ6z3WtRuc1oxN240zwxyCjBkPQ0fPULguXJQKhe253MlipCPmYu6-wpY54ZEO4I2EVNorsOFERj3J1WTGm--erOr6My7mvph4IsMFWA55NRqvx6ETQc1XmW_W02SX7-TtKlJh-ZYqomHkoEB8E-bIE",
                field(envelope, "ct"),
            )
        }

    @Test
    fun `production sealer uses a fresh sender key and nonce for every package`() =
        runTest {
            val sealer = CryptographyDeviceTransfer()

            val first = sealer.seal(contents())
            val second = sealer.seal(contents())

            assertNotEquals(field(first, "pk"), field(second, "pk"))
            assertNotEquals(field(first, "iv"), field(second, "iv"))
        }

    @Test
    fun `session tokens never appear in its string form`() {
        val session = DeviceTransferSession("Alice", "secret-access", "secret-refresh")

        assertEquals(false, "secret-access" in session.toString() || "secret-refresh" in session.toString())
    }
}
