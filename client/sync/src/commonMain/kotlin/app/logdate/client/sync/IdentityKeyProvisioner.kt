package app.logdate.client.sync

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import app.logdate.client.sync.location.LocationHistorySyncEngine
import app.logdate.client.sync.metadata.IdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException

/**
 * Gives [DefaultSyncManager] the identity key a sync run needs, or pauses it when it cannot.
 *
 * @param mintsIdentityKey False on a linked watch, which only uses the key its phone installed: see
 *   [bindLinkedIdentityKey].
 * @param locationHistorySyncEngine Null when location history is not synced on this device.
 */
internal class IdentityKeyProvisioner(
    private val identityKeyManager: IdentityKeyManager?,
    private val cloudApiClient: CloudApiClient?,
    private val sessionStorage: SessionStorage,
    private val identityRecoveryNeededStore: IdentityRecoveryNeededStore,
    private val mediaPayloadKeyProvider: MediaPayloadKeyProvider?,
    private val syncMetadataService: SyncMetadataService,
    private val downloadInbox: DownloadInbox?,
    private val locationHistorySyncEngine: LocationHistorySyncEngine?,
    private val mintsIdentityKey: Boolean,
) {
    /**
     * Every note, journal, and draft is encrypted with a key derived from this device's identity
     * key, so a device without one fails every upload. Onboarding provisions it for new devices;
     * this covers the ones that finished onboarding back when nothing did.
     *
     * A device can also have no key because it *lost* one -- a reinstall, a restore onto a
     * different device -- while the account it signs back into already has data in the cloud
     * encrypted under the old key. Minting a fresh key in that case would look identical to
     * onboarding a new device, but it silently starts a second, unrelated identity: nothing
     * already in the cloud can ever be read again, and uploads from now on quietly diverge from
     * everything before them. So before minting anything, this first checks whether the identity
     * key manager can silently restore the key from its own device-transfer/cloud backup (see
     * [app.logdate.client.device.crypto.IdentityKeyBackupStore]) -- the common case for exactly
     * this scenario, needing no user interaction at all. Only when that backup is also empty does
     * this fall back to the mint-or-pause decision: mints when the account can be confirmed empty;
     * when it already has data, sync pauses with [SyncPausedReason.NEEDS_RECOVERY_PHRASE] instead
     * and waits for the phrase to be entered again, and when it cannot be determined (offline, a
     * server error), nothing happens this pass and the next sync attempt tries again.
     */
    suspend fun provision(
        accessToken: String,
        requiresBoundAccountKey: Boolean = false,
    ) {
        val manager = identityKeyManager ?: return
        val client = cloudApiClient
        val accountId = sessionStorage.getSession()?.accountId
        if (requiresBoundAccountKey) return checkBoundAccountKey(manager, accountId)
        if (!mintsIdentityKey) return bindLinkedIdentityKey(manager, accountId, identityRecoveryNeededStore)
        if (manager.hasIdentityKey()) {
            if (accountId != null && !manager.bindToAccount(accountId)) {
                identityRecoveryNeededStore.setNeeded(true)
                return
            }
            manager.ensureExistingIdentityBackup()
            if (identityRecoveryNeededStore.isNeeded()) identityRecoveryNeededStore.setNeeded(false)
            return
        }

        if (manager.restoreFromBackupIfAvailable()) {
            Napier.i(
                "Identity key silently restored from its backup store -- resetting sync state so " +
                    "records the old, now-replaced identity could not read re-arrive and get " +
                    "another chance",
            )
            if (accountId != null && !manager.bindToAccount(accountId)) {
                identityRecoveryNeededStore.setNeeded(true)
                return
            }
            mediaPayloadKeyProvider?.clearCachedKey()
            syncMetadataService.resetAllCursors()
            identityRecoveryNeededStore.setNeeded(false)
            downloadInbox?.release(afterRecovery = true)
            return
        }

        if (client == null) {
            runCatching { manager.setupNewIdentity() }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    throwIfDeviceBlocked(it)
                    Napier.e("Could not provision an identity key")
                }
            return
        }

        when (accountAlreadyHasCloudData(client, accessToken)) {
            true -> {
                Napier.w(
                    "This device has no identity key but the account already has cloud data -- " +
                        "pausing sync instead of minting a key that could never decrypt it",
                )
                identityRecoveryNeededStore.setNeeded(true)
            }
            false -> {
                runCatching { manager.setupNewIdentity() }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        throwIfDeviceBlocked(it)
                        Napier.e("Could not provision an identity key")
                    }
                if (manager.hasIdentityKey() && accountId != null) manager.bindToAccount(accountId)
            }
            null -> {
                // Could not tell (offline, a server error). Leave things as they are; the next
                // sync attempt checks again rather than guessing now.
            }
        }
    }

    /** Personal-space keys are installed by verified enrollment/recovery, never background sync. */
    private suspend fun checkBoundAccountKey(
        manager: IdentityKeyManager,
        accountId: String?,
    ) {
        val key =
            try {
                accountId?.let { manager.getAccountIdentityKey(it) }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                null
            }
        identityRecoveryNeededStore.setNeeded(key == null)
        key?.fill(0)
    }

    /** Null when it could not be determined -- offline, a server error -- rather than assumed either way. */
    private suspend fun accountAlreadyHasCloudData(
        client: CloudApiClient,
        accessToken: String,
    ): Boolean? =
        runCatching {
            val journals = client.getJournalChanges(accessToken, since = 0L, limit = 1).getOrThrow()
            if (journals.changes.isNotEmpty() || journals.deletions.isNotEmpty()) return@runCatching true
            val content = client.getContentChanges(accessToken, since = 0L, limit = 1).getOrThrow()
            if (content.changes.isNotEmpty() || content.deletions.isNotEmpty()) return@runCatching true
            val drafts = client.getDraftChanges(accessToken, since = 0L, limit = 1).getOrThrow()
            drafts.drafts.isNotEmpty() ||
                drafts.deletions.isNotEmpty() ||
                locationHistorySyncEngine?.hasRemoteRecords(accessToken) == true
        }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            throwIfDeviceBlocked(it)
            Napier.w("Could not check whether the account already has cloud data")
            null
        }
}
