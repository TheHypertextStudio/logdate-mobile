package app.logdate.client.device.crypto

import io.github.aakira.napier.Napier
import kotlin.coroutines.cancellation.CancellationException

/**
 * Keeps a second, independent copy of the root identity record in [mirror], next to the
 * authoritative [primary] backup.
 *
 * The primary store (Android's Auto Backup file) restores only when its platform has backed it up,
 * and stops backing up once app data passes its quota. The mirror (Android Block Store) holds only
 * the small root record, so it keeps working when the primary cannot.
 *
 * The mirror is a copy, never a second source of truth:
 * - When the primary holds a root, it wins. A missing or different mirror is rewritten to match it.
 * - When the primary holds no root, the mirror's root is read and written back into the primary,
 *   so the next platform backup carries it again.
 * - An unreadable or failing mirror is treated as absent and never blocks the primary.
 *
 * Media identities live only in the primary: up to 64 of them don't fit a small mirror entry.
 *
 * [mirrorEnabled] is read on every call so the mirror can stay behind a feature flag.
 */
class LayeredIdentityKeyBackupStore(
    private val primary: IdentityKeyBackupStore,
    private val mirror: IdentityKeyBackupStore,
    private val mirrorEnabled: suspend () -> Boolean = { true },
) : IdentityKeyBackupStore {
    override suspend fun hasRootBackup(): Boolean = primary.hasRootBackup() || mirrorRoot() != null

    override suspend fun hasBackup(): Boolean = primary.hasBackup() || mirrorRoot() != null

    override suspend fun hasMediaBackup(): Boolean = primary.hasMediaBackup()

    override suspend fun readMediaIdentity(identityBinding: String): MediaIdentityBackup? = primary.readMediaIdentity(identityBinding)

    override suspend fun writeMediaIdentity(identity: MediaIdentityBackup) = primary.writeMediaIdentity(identity)

    override suspend fun readAccountIdentity(): AccountIdentityBackup? = reconciledRoot()?.account

    override suspend fun readPhrase(): String? = reconciledRoot()?.phrase

    override suspend fun writeAccountIdentity(identity: AccountIdentityBackup) {
        primary.writeAccountIdentity(identity)
        mirrorSafely { mirror.writeAccountIdentity(identity) }
    }

    override suspend fun writePhrase(phrase: String) {
        primary.writePhrase(phrase)
        mirrorSafely { mirror.writePhrase(phrase) }
    }

    override suspend fun clear() {
        // Clearing is a deliberate reset (sign-out, linking another identity). A mirror that
        // outlived it would restore the old key on the next launch, so it goes first and a
        // failure is thrown, the same as a primary that can't delete its files. With the mirror
        // turned off it is still cleared, in case it was on earlier, but a failure is only logged.
        if (mirrorEnabled()) {
            mirror.clear()
        } else {
            try {
                mirror.clear()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.w("Could not clear a disabled identity backup mirror", error)
            }
        }
        primary.clear()
    }

    private suspend fun reconciledRoot(): RootRecord? {
        val primaryRoot = primary.readRoot()
        if (primaryRoot != null) {
            mirrorSafely {
                if (mirror.readRoot() != primaryRoot) primaryRoot.writeTo(mirror)
            }
            return primaryRoot
        }
        if (primary.hasRootBackup()) {
            // The primary holds a root it can't read. Let the caller's recovery handling see that
            // rather than quietly substituting the mirror's copy.
            return null
        }
        val mirrored = mirrorRoot() ?: return null
        mirrored.writeTo(primary)
        Napier.i("Restored the identity backup from its platform mirror")
        return mirrored
    }

    private suspend fun mirrorRoot(): RootRecord? {
        if (!mirrorEnabled()) return null
        return try {
            mirror.readRoot()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w("Ignoring an unreadable identity backup mirror", error)
            null
        }
    }

    private suspend fun mirrorSafely(block: suspend () -> Unit) {
        if (!mirrorEnabled()) return
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w("Could not update the identity backup mirror", error)
        }
    }

    private class RootRecord(
        val phrase: String?,
        val account: AccountIdentityBackup?,
    ) {
        suspend fun writeTo(store: IdentityKeyBackupStore) {
            if (account != null) store.writeAccountIdentity(account) else store.writePhrase(checkNotNull(phrase))
        }

        override fun equals(other: Any?): Boolean =
            other is RootRecord &&
                phrase == other.phrase &&
                account?.accountId == other.account?.accountId &&
                account?.key.contentEqualsOrBothNull(other.account?.key) &&
                account?.mediaKey.contentEqualsOrBothNull(other.account?.mediaKey)

        override fun hashCode(): Int = phrase.hashCode() * 31 + (account?.accountId?.hashCode() ?: 0)
    }

    private companion object {
        suspend fun IdentityKeyBackupStore.readRoot(): RootRecord? {
            readAccountIdentity()?.let { return RootRecord(null, it) }
            return readPhrase()?.let { RootRecord(it, null) }
        }

        fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
            if (this == null) other == null else other != null && contentEquals(other)
    }
}
