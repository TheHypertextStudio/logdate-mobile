@file:Suppress("ktlint:standard:filename")

package app.logdate.client.data.account

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.PlatformAccountManager
import app.logdate.client.device.auth.BlueskyAttemptStore
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.networking.PasskeyApiClientContract
import app.logdate.client.networking.PasskeyApiErrorCodes
import app.logdate.client.networking.PasskeyApiException
import app.logdate.client.networking.bluesky.BlueskyApi
import app.logdate.client.permissions.GoogleSignInManager
import app.logdate.client.permissions.NoOpGoogleSignInManager
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.permissions.RestoreCredentialManager
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInRepository
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.client.repository.account.PasskeyConfirmations
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyInfo
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Default implementation of PasskeyAccountRepository for managing passkey-based account operations.
 *
 * Delegates each family of operations to a focused collaborator: [PasskeyRegistrationCoordinator]
 * for passkey creation/sign-in, [GoogleSignInCoordinator] for Google sign-up/sign-in,
 * [AccountSessionRefreshCoordinator] for token refresh and the authenticated passkey endpoints, and
 * [RestoreCredentialCoordinator] for cloud-backup restore credentials. [CanonicalOwnerBindingGuard]
 * enforces LogDate's single-identity rule for all of them, and [PasskeyAccountSessionState] is the
 * shared current-account/authenticated state they report back to.
 */
class DefaultPasskeyAccountRepository(
    private val apiClient: PasskeyApiClientContract,
    private val passkeyManager: PasskeyManager,
    private val restoreCredentialManager: RestoreCredentialManager,
    private val sessionStorage: SessionStorage,
    private val platformAccountManager: PlatformAccountManager,
    private val configRepository: LogDateConfigRepository,
    private val canonicalOwnerProvider: CanonicalOwnerProvider,
    private val hasLocalData: suspend () -> Boolean,
    private val googleSignInManager: GoogleSignInManager = NoOpGoogleSignInManager(),
    private val serverClientId: String = "",
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        },
    /** The name of this device, given to passkeys it creates. `null` leaves the server default. */
    private val deviceName: () -> String? = { null },
    /**
     * Whether signing up creates the app's restore credential. There is one per app, so an account
     * opened on a server the app isn't connected to must not replace the connected server's.
     */
    private val createsRestoreKey: Boolean = true,
    private val adoptCanonicalOwner: (suspend (String) -> Boolean)? = null,
    private val accountKeyUnlockCoordinator: AccountKeyUnlockCoordinator? = null,
    private val beforeSignOut: suspend () -> Boolean = { true },
    private val restoreCredentialRenewal: RestoreCredentialRenewal? = null,
    /** Where a passkey sign-in's confirmation goes, so a first recovery key can be registered without another prompt. */
    private val passkeyConfirmations: PasskeyConfirmations? = null,
) : PasskeyAccountRepository {
    private val sessionState = PasskeyAccountSessionState()
    override val currentAccount: StateFlow<LogDateAccount?> = sessionState.currentAccount
    override val isAuthenticated: StateFlow<Boolean> = sessionState.isAuthenticated

    private val bindingGuard =
        CanonicalOwnerBindingGuard(
            canonicalOwnerProvider = canonicalOwnerProvider,
            hasLocalData = hasLocalData,
            configRepository = configRepository,
            adoptCanonicalOwner = adoptCanonicalOwner,
        )

    private val credentialCodec = PasskeyCredentialCodec(json)
    private val restoreCredentialRegistrationMutex = Mutex()
    private val restoreRenewalRequests = MutableStateFlow(0L)

    private val sessionRefreshCoordinator =
        AccountSessionRefreshCoordinator(
            apiClient = apiClient,
            sessionStorage = sessionStorage,
            platformAccountManager = platformAccountManager,
            configRepository = configRepository,
            sessionState = sessionState,
            deleteRestoreKey = { deleteRestoreKey() },
            prepareRestoreKeyClear = restoreCredentialManager::prepareRestoreCredentialClear,
            apiForOrigin = { origin ->
                restoreCredentialRenewal?.apiFor(
                    app.logdate.shared.config
                        .PinnedLogDateConfigRepository(origin, null, configRepository.apiVersion.value),
                ) ?: apiClient
            },
        )

    private val restoreCredentialCoordinator =
        RestoreCredentialCoordinator(
            apiClient = apiClient,
            restoreCredentialManager = restoreCredentialManager,
            sessionStorage = sessionStorage,
            configRepository = configRepository,
            bindingGuard = bindingGuard,
            sessionState = sessionState,
            sessionRefreshCoordinator = sessionRefreshCoordinator,
            registrationMutex = restoreCredentialRegistrationMutex,
            renewal = restoreCredentialRenewal,
            onSessionInstalled = { restoreRenewalRequests.update { it + 1 } },
        )

    private val registrationCoordinator =
        PasskeyRegistrationCoordinator(
            apiClient = apiClient,
            passkeyManager = passkeyManager,
            sessionStorage = sessionStorage,
            platformAccountManager = platformAccountManager,
            configRepository = configRepository,
            canonicalOwnerProvider = canonicalOwnerProvider,
            bindingGuard = bindingGuard,
            credentialCodec = credentialCodec,
            sessionState = sessionState,
            createRestoreKey = ::enrollAfterAccountCreation,
            beforeSessionPersist = ::prepareRestoreEnrollment,
            onSessionPersisted = ::resumeRestoreEnrollment,
            updateTokensOrRegisterPlatformAccount = sessionRefreshCoordinator::updateTokensOrRegisterPlatformAccount,
            deviceName = deviceName,
            accountKeyUnlockCoordinator = accountKeyUnlockCoordinator,
            onPasskeyConfirmed = { accountId, grant -> passkeyConfirmations?.record(accountId, grant) },
        )

    private val enrollmentCoordinator =
        PasskeyEnrollmentCoordinator(
            apiClient = apiClient,
            passkeyManager = passkeyManager,
            credentialCodec = credentialCodec,
            sessionRefreshCoordinator = sessionRefreshCoordinator,
            deviceName = deviceName,
        )

    private val googleSignInCoordinator =
        GoogleSignInCoordinator(
            apiClient = apiClient,
            sessionStorage = sessionStorage,
            platformAccountManager = platformAccountManager,
            configRepository = configRepository,
            canonicalOwnerProvider = canonicalOwnerProvider,
            bindingGuard = bindingGuard,
            googleSignInManager = googleSignInManager,
            serverClientId = serverClientId,
            sessionState = sessionState,
            createRestoreKey = ::enrollAfterAccountCreation,
            beforeSessionPersist = ::prepareRestoreEnrollment,
            onSessionPersisted = ::resumeRestoreEnrollment,
        )

    init {
        repositoryScope.launch {
            combine(sessionStorage.getSessionFlow(), restoreRenewalRequests) { session, _ -> session }.collectLatest { session ->
                val binding = currentBinding(session)
                if (binding?.session != session) return@collectLatest
                if (session != null && !bindingGuard.sessionBelongsToCanonicalOwner(session)) {
                    Napier.w("Discarding credentials for a different LogDate identity")
                    if (binding != null && sessionStorage.supportsGuardedAuthentication) {
                        sessionStorage.clearSessionIfCurrent(binding) { sessionState.clear() }
                    } else if (sessionStorage.getSession() == session) {
                        sessionStorage.clearSession()
                        sessionState.clear()
                    }
                } else {
                    val published =
                        sessionStorage.runIfSessionCurrent(binding) {
                            sessionState.setAuthenticated(session != null)
                            if (session == null || sessionState.accountOrigin != binding?.origin) sessionState.clearAccount()
                        }
                    if (published && session != null) renewBackgroundRestoreKey(session)
                }
            }
        }

        repositoryScope.launch {
            configRepository.backendUrl.drop(1).collect {
                val binding = currentBinding(sessionStorage.getSession())
                val belongs = binding?.session?.let { bindingGuard.sessionBelongsToCanonicalOwner(it) } == true
                sessionStorage.runIfSessionCurrent(binding) {
                    val session = binding?.session
                    if (session == null) {
                        sessionState.clear()
                    } else {
                        if (sessionState.accountOrigin != binding.origin) sessionState.clearAccount()
                        sessionState.setAuthenticated(belongs)
                    }
                }
            }
        }
    }

    private fun currentBinding(session: app.logdate.client.datastore.UserSession?): OriginBoundSession? =
        sessionStorage.getOriginBoundSession() ?: session
            ?.takeUnless { sessionStorage.supportsGuardedAuthentication }
            ?.let { OriginBoundSession(configRepository.getCurrentBackendUrl(), it) }

    private suspend fun renewBackgroundRestoreKey(session: app.logdate.client.datastore.UserSession) {
        if (!createsRestoreKey) return
        val renewal = restoreCredentialRenewal ?: return
        var captured: app.logdate.client.datastore.SessionAuthenticationSnapshot? = null
        var captureComplete = false
        val origin = configRepository.getCurrentBackendUrl()

        fun pinnedConfig() =
            app.logdate.shared.config.PinnedLogDateConfigRepository(
                origin,
                configRepository.getCurrentServerDescriptor(),
                configRepository.apiVersion.value,
            )
        val isCurrent: suspend () -> Boolean = {
            val guard = captured?.guard
            val authorized = guard == null || sessionStorage.isAuthenticationGuardCurrent(guard)
            authorized && configRepository.getCurrentBackendUrl() == origin && sessionStorage.getSession() == session
        }
        retryRestoreCredentialRenewal(
            isPending = pending@{
                if (!captureComplete) {
                    val snapshot = sessionStorage.captureAuthenticationSnapshot()
                    check(snapshot != null || !sessionStorage.supportsGuardedAuthentication) { "Restore authorization unavailable" }
                    if (snapshot != null && snapshot.session != OriginBoundSession(origin, session)) return@pending false
                    captured = snapshot
                    captureComplete = true
                }
                if (!isCurrent()) return@pending false
                renewal.ensurePendingIfAbsent(origin, session.accountId)
                renewal.isPending(origin, session.accountId)
            },
            isCurrent = isCurrent,
            renew = {
                restoreCredentialRegistrationMutex.withLock {
                    renewal.renew(pinnedConfig(), session, restoreCredentialManager, platformAccountManager, isCurrent)
                }
            },
            onFailure = { error ->
                if (error is PasskeyApiException &&
                    error.errorCode in setOf(PasskeyApiErrorCodes.INVALID_TOKEN, PasskeyApiErrorCodes.UNAUTHORIZED)
                ) {
                    renewal.refreshSession(pinnedConfig(), session, sessionStorage, isCurrent)
                }
            },
        )
    }

    private suspend fun enrollAfterAccountCreation(): Result<Unit> {
        if (!createsRestoreKey) return Result.success(Unit)
        if (restoreCredentialRenewal == null) return createRestoreKey()
        return Result.success(Unit)
    }

    private suspend fun prepareRestoreEnrollment(
        origin: String,
        owner: String,
    ) {
        if (!createsRestoreKey) return
        val renewal = restoreCredentialRenewal ?: return
        check(configRepository.getCurrentBackendUrl() == origin) { "Authentication server changed" }
        renewal.mark(origin, owner)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(configRepository.getCurrentBackendUrl() == origin) { "Authentication server changed" }
    }

    private fun resumeRestoreEnrollment() {
        if (createsRestoreKey && restoreCredentialRenewal != null) restoreRenewalRequests.update { it + 1 }
    }

    override suspend fun checkUsernameAvailability(username: String): Result<Boolean> =
        try {
            val result = apiClient.checkUsernameAvailability(username)
            result.map { it.available }
        } catch (e: Exception) {
            Napier.w("Failed to check username availability", e)
            Result.failure(e)
        }

    /** Construct once in DI so browser returns and UI actions share the same serialized flow. */
    internal fun blueskySignInRepository(
        attempts: BlueskyAttemptStore,
        enrollments: BlueskyEnrollmentStore,
        apiFactory: (String) -> BlueskyApi,
        enabled: suspend () -> Boolean,
        prepareAvailability: suspend () -> Boolean = enabled,
    ): BlueskySignInRepository =
        BlueskySignInController(
            origin = configRepository::getCurrentBackendUrl,
            owner = canonicalOwnerProvider::getCanonicalOwnerId,
            enabled = { enabled() && passkeyManager.getCapabilities().isSupported },
            prepareAvailability = { passkeyManager.getCapabilities().isSupported && prepareAvailability() },
            attempts = attempts,
            enrollments = enrollments,
            api = apiFactory,
            register = { request, ceremony -> registrationCoordinator.createAccountWithPasskey(request, ceremony) },
            authenticate = { adopt, ceremony -> registrationCoordinator.authenticateWithPasskey(null, adopt, ceremony) },
        )

    override suspend fun createAccountWithPasskey(request: AccountCreationRequest): Result<LogDateAccount> =
        registrationCoordinator.createAccountWithPasskey(request)

    override suspend fun authenticateWithPasskey(
        username: String?,
        adoptLocalData: Boolean,
    ): Result<LogDateAccount> = registrationCoordinator.authenticateWithPasskey(username, adoptLocalData)

    override suspend fun signUpWithGoogle(
        username: String?,
        displayName: String?,
    ): Result<LogDateAccount> = googleSignInCoordinator.signUpWithGoogle(username, displayName)

    override suspend fun signInWithGoogle(): Result<LogDateAccount> = googleSignInCoordinator.signInWithGoogle()

    override suspend fun signOut(): Result<Unit> =
        if (beforeSignOut()) {
            sessionRefreshCoordinator.signOut()
        } else {
            Result.failure(IllegalStateException("The linked watch could not be removed safely. Keep it nearby and try again."))
        }

    override suspend fun deleteAccount(): Result<Unit> = sessionRefreshCoordinator.deleteAccount()

    override suspend fun refreshAuthentication(): Result<Unit> = sessionRefreshCoordinator.refreshAuthentication()

    internal suspend fun refreshConnectionAuthentication(expected: OriginBoundSession): Result<Unit> =
        sessionRefreshCoordinator.refreshAuthentication(expected)

    override suspend fun getAccountInfo(): Result<LogDateAccount> = sessionRefreshCoordinator.getAccountInfo()

    override suspend fun getCurrentAccount(): LogDateAccount? = sessionState.account

    override suspend fun listPasskeys(): Result<List<PasskeyInfo>> = sessionRefreshCoordinator.listPasskeys()

    override suspend fun deletePasskey(credentialId: String): Result<Unit> = sessionRefreshCoordinator.deletePasskey(credentialId)

    override suspend fun addPasskey(): Result<PasskeyInfo> = enrollmentCoordinator.addPasskey()

    override suspend fun listLinkedSignInProviders(): Result<List<LinkedSignInProvider>> = enrollmentCoordinator.listLinkedSignInProviders()

    override suspend fun createRestoreKey(): Result<Unit> {
        if (!createsRestoreKey) return Result.success(Unit)
        if (restoreCredentialRenewal == null) return restoreCredentialCoordinator.createRestoreKey()
        return try {
            val origin = configRepository.getCurrentBackendUrl()
            val session = sessionStorage.getSession() ?: return Result.failure(IllegalStateException("No active session"))
            prepareRestoreEnrollment(origin, session.accountId)
            check(sessionStorage.getSession() == session) { "Enrollment session changed" }
            resumeRestoreEnrollment()
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun signInWithRestoreKey(): Result<LogDateAccount> = restoreCredentialCoordinator.signInWithRestoreKey()

    override suspend fun deleteRestoreKey(): Result<Unit> = restoreCredentialCoordinator.deleteRestoreKey()
}
