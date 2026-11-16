package app.logdate.client.domain.activity

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PhoneActivityRegistrationState {
    Checking,
    Unavailable,
    Off,
    NeedsPermission,
    Starting,
    Linked,
    Failed,
}

/** Owns the effective phone link, which can differ from the person's saved choice. */
class PhoneActivityRegistration(
    private val optedIn: suspend () -> Boolean,
    private val available: () -> Boolean,
    private val permitted: () -> Boolean,
    private val register: suspend () -> Boolean,
    private val unregister: suspend () -> Unit,
) {
    private val reconciliation = Mutex()
    private val current = MutableStateFlow(PhoneActivityRegistrationState.Checking)
    private val refreshes = MutableStateFlow(0)

    val state = current.asStateFlow()
    val refreshRequests = refreshes.asStateFlow()

    /** Request a live permission/provider check after returning to the app or retrying. */
    fun refresh() {
        refreshes.update { it + 1 }
    }

    fun canStartLink(): Boolean = available() && permitted()

    /** Recheck consent at delivery time too: an already queued transition can outlive its subscription. */
    suspend fun canReceiveTransitions(): Boolean =
        current.value == PhoneActivityRegistrationState.Linked &&
            unavailableState() == null &&
            current.value == PhoneActivityRegistrationState.Linked

    suspend fun reconcile() {
        reconciliation.withLock {
            val previous = current.value
            val unavailable = unavailableState()
            if (unavailable != null) {
                current.value = unavailable
                if (previous == PhoneActivityRegistrationState.Checking || previous == PhoneActivityRegistrationState.Linked) {
                    unregister()
                }
                return@withLock
            }
            if (previous == PhoneActivityRegistrationState.Linked) return@withLock

            current.value = PhoneActivityRegistrationState.Starting
            val started = register()
            // Play services can complete after the user revoked the grant or turned the choice off.
            val changed = unavailableState()
            if (changed != null) {
                current.value = changed
                if (started) unregister()
            } else {
                current.value = if (started) PhoneActivityRegistrationState.Linked else PhoneActivityRegistrationState.Failed
            }
        }
    }

    private suspend fun unavailableState(): PhoneActivityRegistrationState? =
        try {
            when {
                !available() -> PhoneActivityRegistrationState.Unavailable
                !optedIn() -> PhoneActivityRegistrationState.Off
                !permitted() -> PhoneActivityRegistrationState.NeedsPermission
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Couldn't verify the phone activity choice", e)
            PhoneActivityRegistrationState.Failed
        }
}
