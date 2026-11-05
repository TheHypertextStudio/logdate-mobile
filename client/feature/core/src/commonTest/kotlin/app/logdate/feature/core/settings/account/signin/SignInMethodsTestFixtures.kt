package app.logdate.feature.core.settings.account.signin

import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.feature.core.settings.account.StubPasskeyAccountRepository
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyCapabilities
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun signInViewModel(
    repository: SignInFakeAccountRepository,
    passkeyManager: PasskeyManager = SignInFakePasskeyManager(),
    defaultPasskeyName: String? = "LogDate",
    connectedRpId: String? = null,
    passkeysWorkWith: (String) -> Boolean = { true },
) = SignInMethodsViewModel(
    accountRepository = repository,
    passkeyManager = passkeyManager,
    defaultPasskeyName = { defaultPasskeyName },
    connectedRpId = { connectedRpId },
    passkeysWorkWith = passkeysWorkWith,
    timeZone = TimeZone.UTC,
)

internal fun passkey(
    credentialId: String = "cred-${Uuid.random()}",
    nickname: String? = "Pixel 9",
    createdAt: String = "2026-09-01T10:00:00Z",
    lastUsedAt: String? = null,
) = PasskeyInfo(
    id = Uuid.random(),
    credentialId = credentialId,
    nickname = nickname,
    deviceType = "platform",
    createdAt = Instant.parse(createdAt),
    lastUsedAt = lastUsedAt?.let { Instant.parse(it) },
    isActive = true,
)

internal fun googleProvider() =
    LinkedSignInProvider(
        kind = LinkedSignInProvider.Kind.GOOGLE,
        email = "alice@example.com",
        linkedAt = Instant.parse("2026-09-01T10:00:00Z"),
        lastSignInAt = null,
    )

internal class SignInFakeAccountRepository(
    var passkeys: List<PasskeyInfo> = emptyList(),
    var linkedProviders: List<LinkedSignInProvider> = emptyList(),
    var listError: Throwable? = null,
    var providersError: Throwable? = null,
    var addPasskeyResult: Result<PasskeyInfo> = Result.failure(NotImplementedError()),
    var deleteResult: Result<Unit> = Result.success(Unit),
) : StubPasskeyAccountRepository() {
    val deletedCredentialIds = mutableListOf<String>()
    var listGate: CompletableDeferred<Unit>? = null
    var addGate: CompletableDeferred<Unit>? = null
    var addThrows: Exception? = null
    var deleteThrows: Exception? = null
    var listThrows: Exception? = null
    var listCallCount = 0
    var addCallCount = 0
    var listFailureAfterMutation: Throwable? = null

    override suspend fun listPasskeys(): Result<List<PasskeyInfo>> {
        listCallCount++
        listGate?.await()
        listThrows?.let { throw it }
        return listError?.let { Result.failure(it) } ?: Result.success(passkeys)
    }

    override suspend fun listLinkedSignInProviders(): Result<List<LinkedSignInProvider>> =
        (listError ?: providersError)?.let { Result.failure(it) } ?: Result.success(linkedProviders)

    override suspend fun addPasskey(): Result<PasskeyInfo> {
        addCallCount++
        addGate?.await()
        addThrows?.let { throw it }
        return addPasskeyResult.onSuccess {
            if (passkeys.none { existing -> existing.credentialId == it.credentialId }) passkeys = passkeys + it
            listError = listFailureAfterMutation
        }
    }

    override suspend fun deletePasskey(credentialId: String): Result<Unit> {
        deletedCredentialIds += credentialId
        deleteThrows?.let { throw it }
        return deleteResult.onSuccess {
            passkeys = passkeys.filterNot { it.credentialId == credentialId }
            listError = listFailureAfterMutation
        }
    }
}

internal class SignInFakePasskeyManager(
    private val supported: Boolean = true,
) : PasskeyManager {
    override suspend fun getCapabilities(): PasskeyCapabilities =
        PasskeyCapabilities(
            isSupported = supported,
            isPlatformAuthenticatorAvailable = supported,
            supportedAlgorithms = listOf("ES256"),
        )

    override suspend fun isPlatformAuthenticatorAvailable(): Boolean = supported

    override suspend fun registerPasskey(options: PasskeyRegistrationOptions): Result<String> = Result.failure(NotImplementedError())

    override suspend fun authenticateWithPasskey(options: PasskeyAuthenticationOptions): Result<String> =
        Result.failure(NotImplementedError())

    override fun getAvailabilityStatus(): Flow<PasskeyCapabilities> = emptyFlow()
}
