package app.logdate.feature.core.settings.ui.devices

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.DeviceTransferContents
import app.logdate.client.device.crypto.DeviceTransferSealer
import app.logdate.client.device.crypto.DeviceTransferSession
import app.logdate.client.networking.DeviceEnrollmentApiClientContract
import app.logdate.client.networking.DeviceEnrollmentApiException
import app.logdate.client.networking.DeviceEnrollmentRequest
import app.logdate.client.networking.DeviceSessionTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceApprovalViewModelTest {
    private val requestId = "4d90d8dc-49b2-4ba1-8c20-529b80c77542"
    private val accountId = "61ad68f2-6d4b-42dd-b263-f838195714ad"
    private val now = 1_000_000L
    private val recipientKey = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(ByteArray(32) { 3 })
    private val macFirstCode =
        "logdate-device-enrollment:v2:" +
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(
                """{"id":"$requestId","accountId":"$accountId","deviceName":"Alice's Mac",
                "publicKey":"$recipientKey","confirmationCode":"413827"}""".encodeToByteArray(),
            )
    private val phoneFirstCode =
        "logdate-device-connect:" +
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(
                """{"deviceName":"Alice's Mac","publicKey":"$recipientKey","claimSecret":"claim-secret","confirmationCode":"413827"}"""
                    .encodeToByteArray(),
            )

    private lateinit var api: FakeEnrollmentApi
    private lateinit var sessions: FakeSessionStorage
    private lateinit var access: FakeAccess
    private lateinit var sealer: RecordingSealer
    private lateinit var viewModel: DeviceApprovalViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakeEnrollmentApi(pendingRequest())
        sessions = FakeSessionStorage(UserSession("phone-access", "phone-refresh", accountId))
        access = FakeAccess(ApprovingAccount(accountId, "Alice"))
        sealer = RecordingSealer()
        viewModel = DeviceApprovalViewModel(api, sessions, access, sealer, now = { now })
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun pendingRequest(
        status: String = "pending",
        expiresAt: Long = now + 60_000,
    ) = DeviceEnrollmentRequest(requestId, "Alice's Mac", recipientKey, "413827", expiresAt, status)

    @Test
    fun `a substituted recipient never reaches confirmation or seals keys`() =
        runTest {
            api.request =
                pendingRequest().copy(publicKey = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(ByteArray(32) { 4 }))
            viewModel.onCodeScanned(macFirstCode)
            assertTrue(viewModel.uiState.value is DeviceApprovalUiState.Failed)
            viewModel.approve()
            assertTrue(sealer.sealed.isEmpty())
        }

    @Test
    fun `existing request is confirmed then approved without minting a session`() =
        runTest {
            viewModel.onCodeScanned(macFirstCode)
            assertEquals(DeviceApprovalUiState.Confirm("Alice's Mac", "Alice", "413827"), viewModel.uiState.value)
            assertEquals(listOf("get:$requestId:phone-access"), api.calls)

            viewModel.approve()

            assertEquals(DeviceApprovalUiState.Done("Alice's Mac", connected = true), viewModel.uiState.value)
            val sealed = sealer.sealed.single()
            assertNull(sealed.session)
            assertEquals(recipientKey, sealed.recipientPublicKey)
            assertEquals(accountId, sealed.accountId)
            assertEquals(requestId, sealed.requestId.toString())
            assertEquals("413827", sealed.confirmationCode)
            assertEquals(listOf("envelope-1"), api.approvedEnvelopes)
            assertTrue(api.calls.none { it.startsWith("session") })
        }

    @Test
    fun `new device gets its own session instead of this phone's tokens`() =
        runTest {
            viewModel.onCodeScanned(phoneFirstCode)
            assertEquals(DeviceApprovalUiState.Confirm("Alice's Mac", "Alice", "413827"), viewModel.uiState.value)
            assertEquals("create:Alice's Mac:$recipientKey:claim-secret:413827:phone-access", api.calls.single())

            viewModel.approve()

            assertEquals(DeviceApprovalUiState.Done("Alice's Mac", connected = true), viewModel.uiState.value)
            assertEquals(listOf("session:$requestId:phone-access"), api.calls.filter { it.startsWith("session") })
            assertEquals(DeviceTransferSession("Alice", "new-device-access", "new-device-refresh"), sealer.sealed.single().session)
            assertEquals(listOf("envelope-1"), api.approvedEnvelopes)
        }

    @Test
    fun `retried approval resends the same envelope and mints one session`() =
        runTest {
            viewModel.onCodeScanned(phoneFirstCode)
            api.approveFailures += IllegalStateException("socket closed")

            viewModel.approve()
            assertEquals(
                DeviceApprovalUiState.Confirm("Alice's Mac", "Alice", "413827", DeviceApprovalFailure.ConnectionFailed),
                viewModel.uiState.value,
            )

            viewModel.approve()

            assertEquals(DeviceApprovalUiState.Done("Alice's Mac", connected = true), viewModel.uiState.value)
            assertEquals(listOf("envelope-1", "envelope-1"), api.approvedEnvelopes)
            assertEquals(1, api.calls.count { it.startsWith("session") })
            assertEquals(1, sealer.sealed.size)
        }

    @Test
    fun `rejecting reports the rejection and seals nothing`() =
        runTest {
            viewModel.onCodeScanned(macFirstCode)

            viewModel.reject()

            assertEquals(DeviceApprovalUiState.Done("Alice's Mac", connected = false), viewModel.uiState.value)
            assertEquals("reject:$requestId:phone-access", api.calls.last())
            assertTrue(sealer.sealed.isEmpty())
        }

    @Test
    fun `expired request asks to start again on the new device`() =
        runTest {
            api.request = pendingRequest(expiresAt = now)

            viewModel.onCodeScanned(macFirstCode)

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.Expired), viewModel.uiState.value)
        }

    @Test
    fun `request that is no longer pending counts as expired`() =
        runTest {
            api.request = pendingRequest(status = "approved")

            viewModel.onCodeScanned(macFirstCode)

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.Expired), viewModel.uiState.value)
        }

    @Test
    fun `request that expires before approval stops instead of offering a retry`() =
        runTest {
            viewModel.onCodeScanned(macFirstCode)
            api.approveFailures += DeviceEnrollmentApiException(409, "ENROLLMENT_UNAVAILABLE")

            viewModel.approve()

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.Expired), viewModel.uiState.value)
        }

    @Test
    fun `request from another account is not found`() =
        runTest {
            api.lookupFailure = DeviceEnrollmentApiException(404, "NOT_FOUND")

            viewModel.onCodeScanned(macFirstCode)

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.WrongAccountOrExpired), viewModel.uiState.value)
        }

    @Test
    fun `phone whose account differs from its session refuses to look up the request`() =
        runTest {
            access.account = ApprovingAccount("00000000-0000-0000-0000-000000000001", "Someone else")

            viewModel.onCodeScanned(macFirstCode)

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.AccountMismatch), viewModel.uiState.value)
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `session already minted for the request stops the approval`() =
        runTest {
            viewModel.onCodeScanned(phoneFirstCode)
            api.sessionFailure = DeviceEnrollmentApiException(409, DeviceEnrollmentApiException.SESSION_ALREADY_ISSUED)

            viewModel.approve()

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.AlreadyUsed), viewModel.uiState.value)
            assertTrue(api.approvedEnvelopes.isEmpty())
        }

    @Test
    fun `something other than a connection code is rejected without a network call`() =
        runTest {
            viewModel.onCodeScanned("https://example.com")

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.NotAConnectionCode), viewModel.uiState.value)
            assertTrue(api.calls.isEmpty())
        }

    @Test
    fun `signed out phone is asked to sign in`() =
        runTest {
            sessions.session.value = null

            viewModel.onCodeScanned(macFirstCode)

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.SignedOut), viewModel.uiState.value)
        }

    @Test
    fun `phone without an identity key does not send anything`() =
        runTest {
            access.identityKey = null
            viewModel.onCodeScanned(macFirstCode)

            viewModel.approve()

            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.KeysMissing), viewModel.uiState.value)
            assertTrue(api.approvedEnvelopes.isEmpty())
        }

    @Test
    fun `dismissing a result returns to idle`() =
        runTest {
            viewModel.onScanFailed(DeviceApprovalFailure.CameraDenied)
            assertEquals(DeviceApprovalUiState.Failed(DeviceApprovalFailure.CameraDenied), viewModel.uiState.value)

            viewModel.dismiss()

            assertEquals(DeviceApprovalUiState.Idle, viewModel.uiState.value)
        }

    private class FakeEnrollmentApi(
        var request: DeviceEnrollmentRequest,
    ) : DeviceEnrollmentApiClientContract {
        val calls = mutableListOf<String>()
        val approvedEnvelopes = mutableListOf<String>()
        val approveFailures = ArrayDeque<Exception>()
        var lookupFailure: Exception? = null
        var sessionFailure: Exception? = null

        override suspend fun createFromPhone(
            deviceName: String,
            publicKey: String,
            claimSecret: String,
            confirmationCode: String,
            accessToken: String,
        ): DeviceEnrollmentRequest {
            calls += "create:$deviceName:$publicKey:$claimSecret:$confirmationCode:$accessToken"
            lookupFailure?.let { throw it }
            return request
        }

        override suspend fun get(
            id: String,
            accessToken: String,
        ): DeviceEnrollmentRequest {
            calls += "get:$id:$accessToken"
            lookupFailure?.let { throw it }
            return request
        }

        override suspend fun createDeviceSession(
            id: String,
            accessToken: String,
        ): Result<DeviceSessionTokens> {
            calls += "session:$id:$accessToken"
            return sessionFailure?.let { Result.failure(it) }
                ?: Result.success(DeviceSessionTokens("new-device-access", "new-device-refresh"))
        }

        override suspend fun approve(
            id: String,
            confirmationCode: String,
            encryptedEnvelope: String,
            accessToken: String,
        ) {
            calls += "approve:$id:$confirmationCode:$accessToken"
            approvedEnvelopes += encryptedEnvelope
            approveFailures.removeFirstOrNull()?.let { throw it }
        }

        override suspend fun reject(
            id: String,
            accessToken: String,
        ) {
            calls += "reject:$id:$accessToken"
        }
    }

    private class FakeSessionStorage(
        initial: UserSession?,
    ) : SessionStorage {
        val session = MutableStateFlow(initial)

        override fun getSession(): UserSession? = session.value

        override fun getSessionFlow(): Flow<UserSession?> = session

        override suspend fun hasValidSession(): Boolean = session.value != null

        override suspend fun saveSession(session: UserSession) {
            this.session.value = session
        }

        override suspend fun clearSession() {
            session.value = null
        }
    }

    private class FakeAccess(
        var account: ApprovingAccount,
    ) : DeviceApprovalAccess {
        var identityKey: ByteArray? = ByteArray(32) { 0x31 }

        override suspend fun account(): Result<ApprovingAccount> = Result.success(account)

        override suspend fun identityKey(): ByteArray? = identityKey

        override suspend fun legacyMediaKey(): ByteArray = ByteArray(32) { 0x44 }
    }

    private class RecordingSealer : DeviceTransferSealer {
        val sealed = mutableListOf<DeviceTransferContents>()

        override suspend fun seal(contents: DeviceTransferContents): String {
            sealed += contents
            return "envelope-${sealed.size}"
        }
    }
}
