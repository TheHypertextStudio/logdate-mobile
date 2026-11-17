package app.logdate.client.data.account

import app.logdate.client.datastore.NativeRestorePolicy
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.OriginSessionVault
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.PlatformAccountManager
import app.logdate.client.device.removeAccountIfSignedOut
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.permissions.GuardedRestoreCredentialManager
import app.logdate.client.permissions.RestoreCredentialManager
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyRegistrationOptions
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

fun interface RestoreCredentialCleanupScheduler {
    suspend fun enqueue(ticketId: String)
}

/** Owns the app-wide provider key and installation-local cleanup obligations. */
class RestoreCredentialLifecycle(
    private val provider: RestoreCredentialManager,
    private val storage: SecureStorage,
    private val sessions: SessionStorage,
    private val policy: NativeRestorePolicy,
    private val config: LogDateConfigRepository,
    private val scheduler: RestoreCredentialCleanupScheduler,
    private val platformAccounts: PlatformAccountManager? = null,
) : GuardedRestoreCredentialManager {
    @Serializable
    private data class Ticket(
        val id: String,
        val origin: String,
        val generation: String,
        val decision: String? = null,
        val beforeDecision: String? = null,
    )

    @Serializable
    private data class State(
        val generation: String,
        val providerOrigins: Set<String> = emptySet(),
        val providerClearedGeneration: String? = null,
        val tickets: List<Ticket> = emptyList(),
        val completedDecisions: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val STATE_KEY = "restore_provider_lifecycle_v1"
        val providerMutex = Mutex()
    }

    override suspend fun prepareRestoreCredentialClear(
        origin: String,
        isCurrent: suspend () -> Boolean,
    ) = prepareClear(origin, isCurrent)

    suspend fun prepareClear(
        origin: String,
        isCurrent: suspend () -> Boolean,
    ) = providerMutex.withLock {
        check(isCurrent()) { "Sign-out identity changed" }
        val decision = policy.decision(origin)
        check(isCurrent()) { "Sign-out identity changed" }
        val state = readState()
        save(
            state.copy(
                tickets =
                    state.tickets.filterNot { it.origin == origin } +
                        Ticket(Uuid.random().toString(), origin, state.generation, beforeDecision = decision),
            ),
        )
        check(isCurrent()) { "Sign-out identity changed" }
    }

    // RestoreCredentialLifecycleTest and RestoreCredentialCleanupRuntimeTest read cleanup tickets with it; tests are not roots.
    @Suppress("unused")
    suspend fun pendingCleanupId(): String? = providerMutex.withLock { readState().tickets.lastOrNull()?.id }

    /** Reconciles the persist/enqueue boundary, including cleanup recorded by an earlier process. */
    suspend fun reconcile() {
        val tickets = providerMutex.withLock { ensureTickets() }
        for (ticket in tickets) scheduler.enqueue(ticket.id)
    }

    fun start(scope: CoroutineScope) =
        scope.launch {
            while (isActive) {
                try {
                    reconcile()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Restore credential cleanup scheduling will retry", error)
                }
                delay(30_000)
            }
        }

    override suspend fun clearRestoreCredential(): Result<Unit> =
        try {
            val tickets = providerMutex.withLock { ensureTickets() }
            if (tickets.isEmpty()) return Result.success(Unit)
            var completed = true
            for (ticket in tickets) {
                val id = ticket.id
                try {
                    scheduler.enqueue(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Restore credential cleanup remains pending scheduling", error)
                }
                if (!cleanup(id)) completed = false
            }
            if (completed) Result.success(Unit) else Result.failure(IllegalStateException("Restore credential cleanup pending"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    /** True means completed or superseded; false retains this ticket for a worker retry. */
    suspend fun cleanup(ticketId: String): Boolean =
        providerMutex.withLock {
            val state = readState()
            val ticket = state.tickets.find { it.id == ticketId } ?: return@withLock true
            if (ticket.decision == null && !isDisabled(policy.decision(ticket.origin))) {
                if (policy.decision(ticket.origin) != ticket.beforeDecision) {
                    save(state.copy(tickets = state.tickets.filterNot { it.id == ticket.id }))
                    return@withLock true
                }
                if (!finishLocalSignOut(ticket)) return@withLock false
            }
            val decision = policy.decision(ticket.origin)
            if (ticket.decision != null && ticket.decision != decision) {
                save(state.copy(tickets = state.tickets.filterNot { it.id == ticket.id }))
                return@withLock true
            }
            if (!isDisabled(decision)) return@withLock false
            val bound = ticket.copy(decision = decision)
            if (bound != ticket) save(state.copy(tickets = state.tickets.map { if (it.id == ticket.id) bound else it }))
            if (!clearPlatformAccounts(ticket.origin, requireNotNull(decision))) return@withLock false
            val connectedOrigin = config.getCurrentBackendUrl()
            val connectedSignedOut = sessions.isSignedOutForProviderCleanup()
            if (state.generation != ticket.generation ||
                state.providerClearedGeneration == state.generation ||
                !allProviderOriginsSignedOut(state.providerOrigins) ||
                (state.providerOrigins.isEmpty() && !connectedSignedOut)
            ) {
                completeTicket(state, ticket, requireNotNull(decision))
                return@withLock true
            }
            if (config.getCurrentBackendUrl() != connectedOrigin) return@withLock false
            // Keep serialization until the external provider operation finishes, even on cancellation.
            withContext(NonCancellable) {
                val result = provider.clearRestoreCredential()
                if (result.isSuccess) {
                    completeTicket(state, ticket, requireNotNull(decision), providerCleared = true)
                    true
                } else {
                    Napier.w("Restore credential cleanup will retry", result.exceptionOrNull())
                    false
                }
            }
        }

    override suspend fun createRestoreKey(options: PasskeyRegistrationOptions): Result<String> = createRestoreKeyIfCurrent(options) { true }

    override suspend fun createRestoreKeyIfCurrent(
        options: PasskeyRegistrationOptions,
        isCurrent: suspend () -> Boolean,
    ): Result<String> =
        providerMutex.withLock {
            val expected =
                sessions.getOriginBoundSession()
                    ?: return@withLock Result.failure(IllegalStateException("Restore identity unavailable"))
            val decision = policy.decision(expected.origin)
            if (!NativeRestorePolicy.allows(decision) || !isCurrent() || !matches(expected)) {
                return@withLock Result.failure(IllegalStateException("Restore identity changed"))
            }
            val pending =
                readState().let {
                    it.copy(
                        generation = Uuid.random().toString(),
                        providerOrigins = it.providerOrigins + expected.origin,
                        providerClearedGeneration = null,
                    )
                }
            save(pending)
            currentCoroutineContext().ensureActive()
            if (!isCurrent() || !matches(expected) || policy.decision(expected.origin) != decision) {
                return@withLock Result.failure(IllegalStateException("Restore identity changed"))
            }
            val result =
                withContext(NonCancellable) {
                    provider.createRestoreKey(options).also {
                        if (it.isSuccess) save(pending.copy(providerOrigins = setOf(expected.origin)))
                    }
                }
            currentCoroutineContext().ensureActive()
            if (!isCurrent() || !matches(expected) || policy.decision(expected.origin) != decision) {
                Result.failure(IllegalStateException("Restore identity changed"))
            } else {
                result
            }
        }

    override suspend fun getRestoreCredential(options: PasskeyAuthenticationOptions): Result<String> =
        provider.getRestoreCredential(options)

    private suspend fun allProviderOriginsSignedOut(origins: Set<String>): Boolean {
        for (origin in origins) if (!isDisabled(policy.decision(origin))) return false
        return true
    }

    private suspend fun completeTicket(
        state: State,
        ticket: Ticket,
        decision: String,
        providerCleared: Boolean = false,
    ) {
        save(
            state.copy(
                tickets = state.tickets.filterNot { it.id == ticket.id },
                completedDecisions = state.completedDecisions + (ticket.origin to decision),
                providerOrigins = if (providerCleared) emptySet() else state.providerOrigins,
                providerClearedGeneration = if (providerCleared) state.generation else state.providerClearedGeneration,
            ),
        )
    }

    private suspend fun clearPlatformAccounts(
        origin: String,
        decision: String,
    ): Boolean {
        val accounts = platformAccounts ?: return true
        val retained = accounts.getStoredAccounts().getOrThrow().filter { it.backendUrl == origin }
        for (account in retained) {
            accounts.removeAccountIfSignedOut(account.username, origin) { policy.decision(origin) == decision }.getOrThrow()
        }
        return policy.decision(origin) == decision
    }

    private suspend fun finishLocalSignOut(ticket: Ticket): Boolean {
        val job = SupervisorJob()
        val scope = CoroutineScope(currentCoroutineContext() + job)
        try {
            val target =
                if (config.getCurrentBackendUrl() == ticket.origin) {
                    sessions
                } else {
                    (sessions as? OriginSessionVault)?.openSessionStorage(ticket.origin, scope) ?: return false
                }
            val guard = target.captureAuthenticationGuard() ?: return false
            // Guard capture and clear both compare the durable policy, so a later login wins.
            if (policy.decision(ticket.origin) != ticket.beforeDecision) return false
            return target.clearSessionIfUnchanged(guard)
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }

    private fun matches(expected: OriginBoundSession): Boolean {
        val current = sessions.getOriginBoundSession() ?: return false
        return config.getCurrentBackendUrl() == expected.origin &&
            current.origin == expected.origin &&
            current.session.accountId == expected.session.accountId &&
            current.session.refreshToken == expected.session.refreshToken
    }

    private suspend fun ensureTickets(): List<Ticket> {
        val state = readState()
        var tickets = state.tickets
        val origins = state.providerOrigins + config.getCurrentBackendUrl()
        for (origin in origins) {
            if (tickets.any { it.origin == origin }) continue
            val decision = policy.decision(origin)
            if (!isDisabled(decision) || state.completedDecisions[origin] == decision) continue
            tickets = tickets + Ticket(Uuid.random().toString(), origin, state.generation, decision)
        }
        if (state.providerOrigins.isNotEmpty() &&
            allProviderOriginsSignedOut(state.providerOrigins) &&
            tickets.none { it.generation == state.generation }
        ) {
            val origin = state.providerOrigins.first()
            tickets = tickets.filterNot { it.origin == origin } +
                Ticket(Uuid.random().toString(), origin, state.generation, policy.decision(origin))
        }
        if (tickets != state.tickets) save(state.copy(tickets = tickets))
        return tickets
    }

    private fun isDisabled(decision: String?): Boolean =
        decision?.startsWith("disabled:") == true &&
            runCatching { Uuid.parse(decision.substringAfter(':')) }.isSuccess

    private suspend fun readState(): State {
        val encoded = storage.getString(STATE_KEY) ?: return State(Uuid.random().toString())
        return try {
            Json.decodeFromString<State>(encoded)
        } catch (error: kotlinx.serialization.SerializationException) {
            Napier.w("Repairing unreadable provider lifecycle state", error)
            State(Uuid.random().toString()).also { save(it) }
        }
    }

    private suspend fun save(state: State) = storage.putString(STATE_KEY, Json.encodeToString(state))
}
