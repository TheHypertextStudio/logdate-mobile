package app.logdate.feature.core.settings.account.recovery

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AccountIdentityRepository
import app.logdate.client.repository.account.IdentityRecoveryException
import app.logdate.client.repository.account.IdentityRecoveryFailure
import app.logdate.feature.core.settings.account.ConnectedServer
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** Whether the recovery phrase on this device can move the account. */
enum class AccountRecoveryStatus {
    /** The phrase on this device can move the account. */
    READY,

    /** Nothing is set up yet, or what is set up came from a phrase this device doesn't have. */
    NEEDS_SETUP,
}

/**
 * Lets the recovery phrase move the account to another service even if its current server can't
 * help, as well as unlock the journal. Setting it up registers the phrase's key on the account's
 * identity, or replaces one made from a phrase this device doesn't have; the server asks for a
 * passkey before either change.
 */
interface AccountRecovery {
    /** Whether this can be offered: the feature is on, the server supports it, and this device has passkeys. */
    val isOffered: Flow<Boolean>

    suspend fun status(phrase: List<String>): Result<AccountRecoveryStatus>

    /**
     * Registers the key from [phrase], or replaces a key from another phrase. A recent passkey
     * sign-in confirms a first registration; otherwise [askForPasskey] decides whether to show the
     * passkey prompt or to fail with [IdentityRecoveryFailure.CONFIRMATION_NEEDED] without sending
     * anything. Replacing a key always needs the prompt.
     */
    suspend fun finishSetup(
        phrase: List<String>,
        askForPasskey: Boolean = true,
    ): Result<Unit>
}

class IdentityAccountRecovery(
    private val identity: AccountIdentityRepository,
    override val isOffered: Flow<Boolean>,
) : AccountRecovery {
    override suspend fun status(phrase: List<String>): Result<AccountRecoveryStatus> {
        val registered = identity.getIdentityStatus().getOrElse { return Result.failure(it) }.plcRecoveryDidKey
        if (registered == null) return Result.success(AccountRecoveryStatus.NEEDS_SETUP)
        val derived = identity.derivePlcRecoveryDidKey(phrase.joinToString(" ")).getOrElse { return Result.failure(it) }
        return Result.success(if (derived.recoveryDidKey == registered) AccountRecoveryStatus.READY else AccountRecoveryStatus.NEEDS_SETUP)
    }

    override suspend fun finishSetup(
        phrase: List<String>,
        askForPasskey: Boolean,
    ): Result<Unit> {
        val registered = identity.getIdentityStatus().getOrElse { return Result.failure(it) }.plcRecoveryDidKey
        val result =
            when {
                registered == null -> {
                    identity.registerRecoveryKey(phrase, askForPasskey)
                }

                status(phrase).getOrElse { return Result.failure(it) } == AccountRecoveryStatus.READY -> {
                    return Result.success(Unit)
                }

                !askForPasskey -> {
                    return Result.failure(
                        IdentityRecoveryException(
                            IdentityRecoveryFailure.CONFIRMATION_NEEDED,
                            "Replacing the recovery key needs a passkey confirmation",
                        ),
                    )
                }

                else -> {
                    identity.replaceRecoveryKey(phrase)
                }
            }
        return result.map { }
    }
}

/**
 * Whether account recovery is offered: its feature flag is on, the connected server can register
 * and use a recovery key, and this device can confirm a change with a passkey, which every change
 * needs. Without all three nothing is shown, so the app never offers a change that would be refused.
 */
fun accountRecoveryOffered(
    featureFlags: FeatureFlagStore,
    connectedServer: ConnectedServer,
    passkeysSupported: Flow<Boolean>,
): Flow<Boolean> =
    combine(
        featureFlags.observe(FeatureFlag.IDENTITY_RECOVERY_KEY),
        connectedServer.info,
        passkeysSupported,
    ) { enabled, server, passkeys ->
        enabled && server.supportsRecoveryKeys && passkeys
    }.distinctUntilChanged()

/** Whether this device can show a passkey prompt. A failed check is logged and counts as no. */
fun passkeysSupported(passkeyManager: PasskeyManager): Flow<Boolean> =
    flow {
        val supported =
            try {
                passkeyManager.getCapabilities().isSupported
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.w("Could not check whether this device supports passkeys", error)
                false
            }
        emit(supported)
    }
