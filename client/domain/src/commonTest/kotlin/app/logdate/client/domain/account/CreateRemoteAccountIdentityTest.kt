package app.logdate.client.domain.account

import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CreateRemoteAccountIdentityTest {
    @Test
    fun `remote account conversion keeps the verified identity instead of inventing replacement IDs`() =
        runTest {
            val passkey = Uuid.random()
            val account =
                LogDateAccount(
                    id = Uuid.random(),
                    username = "alice",
                    displayName = "Alice Verified",
                    did = "did:plc:alice",
                    handle = "alice.bsky.social",
                    bio = "Introduction",
                    originalBio = "Original introduction",
                    passkeyCredentialIds = listOf(passkey.toString(), "webauthn-credential"),
                    createdAt = Instant.parse("2020-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2021-01-01T00:00:00Z"),
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                    identityKind = AccountIdentityKind.EXTERNAL,
                )
            val repository = Accounts(account)
            val creation = CreateRemoteAccountUseCase(repository)("ALICE", "Entered Name")
            val result = assertIs<CreateRemoteAccountUseCase.Result.Success>(creation)
            val actual = result.account
            assertEquals(account.id, actual.id)
            assertEquals(account.id, actual.userId)
            assertEquals(account.username, actual.username)
            assertEquals(account.displayName, actual.displayName)
            assertEquals(account.did, actual.did)
            assertEquals(account.handle, actual.handle)
            assertEquals(account.bio, actual.bio)
            assertEquals(account.originalBio, actual.originalBio)
            assertEquals(account.createdAt, actual.createdAt)
            assertEquals(account.updatedAt, actual.updatedAt)
            assertEquals(listOf(passkey), actual.passkeyCredentialIds)
            assertEquals(account.storageMode, actual.storageMode)
            assertEquals(account.identityKind, actual.identityKind)
        }

    @Test
    fun `cancelled account creation stays cancelled instead of becoming a user facing error`() =
        runTest {
            val repository = Accounts(LogDateAccount(username = "alice", displayName = "Alice"), cancel = true)
            assertFailsWith<CancellationException> { CreateRemoteAccountUseCase(repository)("alice", "Alice") }
        }

    private class Accounts(
        val account: LogDateAccount,
        val cancel: Boolean = false,
    ) : PasskeyAccountRepository {
        override val currentAccount = MutableStateFlow<LogDateAccount?>(null)
        override val isAuthenticated = MutableStateFlow(false)

        override suspend fun checkUsernameAvailability(username: String) = Result.success(true)

        override suspend fun createAccountWithPasskey(request: AccountCreationRequest): Result<LogDateAccount> {
            if (cancel) throw CancellationException("Canceled")
            return Result.success(account)
        }

        override suspend fun authenticateWithPasskey(
            username: String?,
            adoptLocalData: Boolean,
        ): Result<LogDateAccount> = error("Unused")

        override suspend fun signOut(): Result<Unit> = error("Unused")

        override suspend fun getCurrentAccount(): LogDateAccount? = account

        override suspend fun getAccountInfo(): Result<LogDateAccount> = error("Unused")

        override suspend fun refreshAuthentication(): Result<Unit> = error("Unused")

        override suspend fun deletePasskey(credentialId: String): Result<Unit> = error("Unused")

        override suspend fun listPasskeys(): Result<List<PasskeyInfo>> = error("Unused")

        override suspend fun createRestoreKey(): Result<Unit> = error("Unused")

        override suspend fun signInWithRestoreKey(): Result<LogDateAccount> = error("Unused")

        override suspend fun deleteRestoreKey(): Result<Unit> = error("Unused")
    }
}
