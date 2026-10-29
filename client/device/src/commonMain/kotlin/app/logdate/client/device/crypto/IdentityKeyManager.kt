package app.logdate.client.device.crypto

import app.logdate.client.device.identity.sameOwnerId
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.device.storage.getBytes
import app.logdate.client.device.storage.putBytes
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Manages the user's identity cryptographic key.
 *
 * The identity key is the root of all encryption for the user's data.
 * It's derived from a BIP-39 recovery phrase and stored securely in the device keystore.
 * A device with [IdentityMintPolicy.NEVER], a linked watch, never derives one of its own: it uses the
 * key its phone installs with [installLinkedIdentityKey].
 */
class IdentityKeyManager(
    private val secureStorage: SecureStorage,
    private val cryptoManager: CryptoManager,
    private val backupStore: IdentityKeyBackupStore = NoOpIdentityKeyBackupStore,
    private val mintPolicy: IdentityMintPolicy = IdentityMintPolicy.ALLOWED,
    private val expectedAccountOwner: (suspend () -> String?)? = null,
) {
    suspend fun isRecoveryPhraseVerified(): Boolean = hasIdentityKey() && secureStorage.getString(KEY_RECOVERY_VERIFIED) == "true"

    suspend fun verifyRecoveryPhrase(words: List<String>): Boolean =
        identityMutex.withLock {
            val storedWords = getStoredRecoveryPhrase()?.words ?: return@withLock false
            if (storedWords != words) return@withLock false
            secureStorage.putString(KEY_RECOVERY_VERIFIED, "true")
            true
        }

    /** Signals changes to identity material or ownership without publishing either value. */
    fun observeIdentityChanges(): Flow<Unit> =
        combine(
            secureStorage.observeString(KEY_IDENTITY_KEY).distinctUntilChanged(),
            secureStorage.observeString(KEY_ACCOUNT_ID).distinctUntilChanged(),
        ) { _, _ -> Unit }

    /**
     * Checks if this device has already been set up with an identity key.
     */
    suspend fun hasIdentityKey(): Boolean = secureStorage.getBytes(KEY_IDENTITY_KEY) != null

    suspend fun canUseForAccount(accountId: String): Boolean {
        val owner = secureStorage.getString(KEY_ACCOUNT_ID)
        return owner == null || sameOwnerId(owner, accountId)
    }

    /** Bind a locally held key to one account before provisioning an encrypted unlock envelope. */
    suspend fun bindToAccount(accountId: String): Boolean =
        identityMutex.withLock {
            if (secureStorage.getBytes(KEY_IDENTITY_KEY)?.size != 32) return@withLock false
            val owner = secureStorage.getString(KEY_ACCOUNT_ID)
            if (owner != null && !sameOwnerId(owner, accountId)) return@withLock false
            if (owner == null) secureStorage.putString(KEY_ACCOUNT_ID, accountId)
            true
        }

    /** Install an account key on a signed-in device without creating a recovery phrase. */
    suspend fun installAccountKey(
        accountId: String,
        key: ByteArray,
        mediaKey: ByteArray? = null,
    ) {
        require(mediaKey == null || mediaKey.size == 32)
        require(key.size == 32) { "Invalid identity key length" }
        identityMutex.withLock {
            val owner = secureStorage.getString(KEY_ACCOUNT_ID)
            require(owner == null || sameOwnerId(owner, accountId)) { "Identity key belongs to a different account" }
            expectedAccountOwner?.invoke()?.let { expected ->
                require(sameOwnerId(expected, accountId)) { "Identity key does not match the retained journal owner" }
            }
            val existing = secureStorage.getBytes(KEY_IDENTITY_KEY)
            require(existing == null || existing.contentEquals(key)) { "Local identity key conflicts with account key" }
            val (retainedAccount, retainedPhrase) = portable.validateRoot(key, accountId)
            val cachedMedia = secureStorage.getBytes(KEY_MEDIA_KEY)
            require(
                mediaKey == null || cachedMedia == null || cachedMedia.contentEquals(mediaKey),
            ) { "Local media key conflicts with account key" }
            val selectedMedia = portable.validateMedia(key, mediaKey ?: cachedMedia, retainedAccount)
            val installedMedia = mediaKey ?: cachedMedia ?: selectedMedia
            val phrase = secureStorage.getString(KEY_RECOVERY_PHRASE) ?: retainedPhrase
            if (phrase == null) {
                backupStore.writeAccountIdentity(
                    AccountIdentityBackup(retainedAccount?.accountId ?: owner ?: accountId, key, installedMedia),
                )
            } else if (installedMedia != null) {
                portable.preserveMedia(key, installedMedia)
            }
            if (owner == null) secureStorage.putString(KEY_ACCOUNT_ID, retainedAccount?.accountId ?: accountId)
            secureStorage.putBytes(KEY_IDENTITY_KEY, key)
            if (phrase != null) secureStorage.putString(KEY_RECOVERY_PHRASE, phrase)
            if (installedMedia != null) secureStorage.putBytes(KEY_MEDIA_KEY, installedMedia)
            if (existing == null) secureStorage.remove(KEY_RECOVERY_VERIFIED)
        }
    }

    /** Checks a linked identity without changing the installed key or its recovery material. */
    suspend fun canInstallLinkedIdentityKey(
        key: ByteArray,
        expectedFingerprint: String,
    ): Boolean {
        if (key.size != 32 || IdentityKeyFingerprint.of(key, cryptoManager) != expectedFingerprint) return false
        val existing = secureStorage.getBytes(KEY_IDENTITY_KEY)
        return existing == null || existing.contentEquals(key)
    }

    /**
     * Installs the identity key a phone sent while linking this device, after checking it against the
     * fingerprint sent with it. A device that already has a different key refuses: it has to be wiped
     * before it can take another identity. The recovery phrase never travels with the key, so none is stored.
     */
    suspend fun installLinkedIdentityKey(
        key: ByteArray,
        expectedFingerprint: String,
    ) {
        require(key.size == 32) { "Invalid identity key length" }
        if (IdentityKeyFingerprint.of(key, cryptoManager) != expectedFingerprint) throw IdentityKeyFingerprintMismatchException()
        identityMutex.withLock {
            val existing = secureStorage.getBytes(KEY_IDENTITY_KEY)
            check(existing == null || existing.contentEquals(key)) { "A different identity key is already installed" }
            if (existing != null) return@withLock
            backupStore.clear()
            secureStorage.putBytes(KEY_IDENTITY_KEY, key)
            secureStorage.remove(KEY_RECOVERY_PHRASE)
            secureStorage.remove(KEY_RECOVERY_VERIFIED)
        }
    }

    private val identityMutex = Mutex()
    private val portable = IdentityBackupCoordinator(secureStorage, cryptoManager, backupStore)

    /**
     * Guarantees this device has an identity key, creating one if it does not.
     *
     * Sync encrypts every note, journal, and draft with a key derived from this one, so the key
     * is infrastructure rather than a user-facing choice -- it must exist before the first upload
     * regardless of whether the user has been shown their recovery phrase yet. Showing the phrase
     * is a separate concern, handled from settings.
     *
     * If [SecureStorage] has no key but [backupStore] holds a recoverable phrase -- the usual
     * shape of a reinstall or a restore onto a new device -- that phrase is used to silently
     * restore the key rather than minting a new, unrelated identity. Callers that need to tell
     * "restored" apart from "already had a key" (to reset sync state that depends on the
     * identity, for example) should use [restoreFromBackupIfAvailable] directly instead.
     *
     * Idempotent, and safe to call concurrently: callers racing here would otherwise each derive a
     * different key and the later write would orphan content encrypted under the earlier one.
     */
    suspend fun ensureIdentityKey() = identityMutex.withLock { ensureIdentityKeyLocked() }

    private suspend fun ensureIdentityKeyLocked() {
        if (hasIdentityKey()) {
            ensurePortableBackupLocked()
            return
        }
        if (restoreFromBackupLocked()) return
        check(!backupStore.hasBackup()) { "The existing identity backup needs recovery" }
        check(expectedAccountOwner?.invoke() == null) { "The retained account needs its original encryption identity" }
        setupNewIdentityLocked()
    }

    /** Keeps derived media selection and persistence in the same transaction as identity recovery. */
    suspend fun <T> withIdentityKey(block: suspend (ByteArray) -> T): T =
        identityMutex.withLock {
            ensureIdentityKeyLocked()
            val result = block(getIdentityKey())
            ensurePortableBackupLocked()
            result
        }

    suspend fun <T> withIdentityMutation(block: suspend () -> T): T = identityMutex.withLock { block() }

    /** Backfills an existing installation without restoring or minting a different identity. */
    suspend fun ensureExistingIdentityBackup() {
        identityMutex.withLock {
            if (hasIdentityKey()) ensurePortableBackupLocked()
        }
    }

    private suspend fun ensurePortableBackupLocked() {
        portable.synchronize(getIdentityKey(), secureStorage.getString(KEY_RECOVERY_PHRASE), secureStorage.getString(KEY_ACCOUNT_ID))
    }

    suspend fun snapshotAccountKeys(
        accountId: String,
        selectMedia: suspend (ByteArray) -> ByteArray,
    ): AccountIdentityBackup =
        identityMutex.withLock {
            ensureIdentityKeyLocked()
            require(canUseForAccount(accountId)) { "Local keys belong to another account" }
            expectedAccountOwner?.invoke()?.let { require(sameOwnerId(it, accountId)) { "Keys do not match the retained journal owner" } }
            if (secureStorage.getString(KEY_ACCOUNT_ID) == null) secureStorage.putString(KEY_ACCOUNT_ID, accountId)
            val root = getIdentityKey()
            val media = selectMedia(root)
            ensurePortableBackupLocked()
            AccountIdentityBackup(accountId, root, media)
        }

    /** Reads existing keys only; null ownership explicitly requests a local-only identity. */
    suspend fun snapshotExistingKeys(
        expectedOwner: String?,
        deriveMedia: (ByteArray) -> ByteArray,
    ): ExistingIdentityKeySnapshot? =
        identityMutex.withLock {
            val observedOwner = secureStorage.getString(KEY_ACCOUNT_ID)
            if (!sameOwnerId(observedOwner, expectedOwner)) return@withLock null
            expectedAccountOwner?.let { retained ->
                if (!sameOwnerId(retained(), expectedOwner)) return@withLock null
            }
            val root = secureStorage.readExistingTransferKey(KEY_IDENTITY_KEY) ?: return@withLock null
            val (account, _) = portable.validateRoot(root, expectedOwner)
            secureStorage.getString(KEY_RECOVERY_PHRASE)?.let { portable.validatePhrase(it, root) }
            val cached = secureStorage.readExistingTransferKey(KEY_MEDIA_KEY)
            val retained = portable.validateMedia(root, cached, account)
            val source =
                when {
                    cached != null -> ExistingMediaKeySource.CACHED
                    retained != null -> ExistingMediaKeySource.RETAINED
                    else -> ExistingMediaKeySource.DERIVED_EXISTING_ROOT
                }
            val media = cached ?: retained ?: deriveMedia(root.copyOf())
            check(media.size == 32) { "Invalid selected media key" }
            ExistingIdentityKeySnapshot(observedOwner, root, media, source)
        }

    /** Observes rootless material only; never classifies original files as plaintext or creates keys. */
    suspend fun observeRootlessKeys(): RootlessIdentityKeyObservation? =
        identityMutex.withLock {
            if (expectedAccountOwner?.invoke() != null || secureStorage.getString(KEY_ACCOUNT_ID) != null) return@withLock null
            if (secureStorage.getString(KEY_RECOVERY_PHRASE) != null || secureStorage.getString(KEY_RECOVERY_VERIFIED) != null) {
                return@withLock null
            }
            if (secureStorage.readExistingTransferKey(KEY_IDENTITY_KEY) != null) return@withLock null
            if (backupStore.hasRootBackup() || backupStore.readPhrase() != null || backupStore.readAccountIdentity() != null) {
                return@withLock null
            }
            RootlessIdentityKeyObservation(
                secureStorage.readExistingTransferKey(KEY_MEDIA_KEY),
                backupStore.hasMediaBackup(),
            )
        }

    /**
     * Sets up a new identity for the first time.
     *
     * This generates a new recovery phrase and derives the identity key from it.
     * The recovery phrase is stored in [SecureStorage] so the user can reference it later from
     * app settings without sending it to LogDate servers or regular app preferences. It is also
     * mirrored into [backupStore] so it survives a device transfer or cloud restore that
     * [SecureStorage]'s KeyStore/Keychain binding does not.
     *
     * @return The recovery phrase the user must store safely
     */
    suspend fun setupNewIdentity(): RecoveryPhrase = identityMutex.withLock { setupNewIdentityLocked() }

    private suspend fun setupNewIdentityLocked(): RecoveryPhrase {
        if (mintPolicy == IdentityMintPolicy.NEVER) throw IdentityMintingNotAllowedException()
        require(!hasIdentityKey()) { "Identity key already exists" }
        check(!backupStore.hasBackup() && secureStorage.getBytes(KEY_MEDIA_KEY) == null) {
            "The retained encryption recovery material needs recovery"
        }

        val phrase = cryptoManager.generateRecoveryPhrase()
        val identityKey = cryptoManager.deriveMasterKey(phrase)
        val phraseText = phrase.joinToString(" ")

        backupStore.writePhrase(phraseText)
        secureStorage.remove(KEY_RECOVERY_VERIFIED)
        secureStorage.putString(KEY_RECOVERY_PHRASE, phraseText)
        secureStorage.putBytes(KEY_IDENTITY_KEY, identityKey)
        Napier.d("New identity established")

        return RecoveryPhrase(phrase)
    }

    /**
     * Recovers the identity key using a previously saved recovery phrase.
     *
     * Used when the user is setting up on a new device or re-authenticating.
     *
     * @param phrase The 12-word recovery phrase
     * @throws IllegalArgumentException if phrase is invalid
     */
    suspend fun recoverIdentity(
        phrase: List<String>,
        accountId: String? = null,
        sessionCurrent: suspend () -> Boolean = { true },
    ) = identityMutex.withLock {
        require(cryptoManager.validateRecoveryPhrase(phrase)) {
            "Invalid recovery phrase"
        }

        val identityKey = cryptoManager.deriveMasterKey(phrase)
        val phraseText = phrase.joinToString(" ")
        if (accountId != null) {
            if (!canUseForAccount(accountId)) throw IdentityRecoveryOwnerMismatchException()
            expectedAccountOwner?.invoke()?.let { expected ->
                if (!sameOwnerId(expected, accountId)) throw IdentityRecoveryOwnerMismatchException()
            }
            if (!sessionCurrent()) throw IdentityRecoverySessionChangedException()
        }
        val paired = backupStore.readAccountIdentity()
        if (paired?.mediaKey != null) portable.preserveMedia(paired.key, paired.mediaKey)
        val outgoingRoot = secureStorage.getBytes(KEY_IDENTITY_KEY)
        val outgoingMedia = secureStorage.getBytes(KEY_MEDIA_KEY)
        if (outgoingRoot != null && outgoingMedia != null) portable.preserveMedia(outgoingRoot, outgoingMedia)
        if (outgoingRoot == null || !outgoingRoot.contentEquals(identityKey)) secureStorage.remove(KEY_MEDIA_KEY)
        secureStorage.remove(KEY_RECOVERY_VERIFIED)
        secureStorage.putBytes(KEY_IDENTITY_KEY, identityKey)
        secureStorage.putString(KEY_RECOVERY_PHRASE, phraseText)
        backupStore.writePhrase(phraseText)
        if (accountId != null) secureStorage.putString(KEY_ACCOUNT_ID, accountId)
        if (accountId != null && !sessionCurrent()) throw IdentityRecoverySessionChangedException()
        Napier.d("Identity recovered from recovery phrase")
    }

    /**
     * Attempts to silently restore the identity key from [backupStore] when [SecureStorage] has
     * none -- the same outcome as a successful [recoverIdentity] call, but driven by a phrase this
     * device already had a backup copy of rather than one the user re-typed.
     *
     * Returns `true` only when a key was actually restored. Returns `false` both when a key
     * already exists (nothing to do) and when there was nothing usable to restore, so callers can
     * tell "just restored" apart from "already had a key" and react accordingly -- e.g. resetting
     * download cursors and cached keys that were derived from a since-replaced identity.
     */
    suspend fun restoreFromBackupIfAvailable(): Boolean = identityMutex.withLock { restoreFromBackupLocked() }

    /** Must only be called while holding [identityMutex]. */
    private suspend fun restoreFromBackupLocked(): Boolean {
        if (hasIdentityKey()) return false

        val accountBackup = backupStore.readAccountIdentity()
        if (accountBackup != null) {
            check(accountBackup.key.size == 32 && accountBackup.accountId.isNotBlank()) { "Invalid account identity backup" }
            val storedAccount = secureStorage.getString(KEY_ACCOUNT_ID)
            check(
                storedAccount == null || sameOwnerId(storedAccount, accountBackup.accountId),
            ) { "Identity backup belongs to another account" }
            expectedAccountOwner?.let { expected ->
                check(sameOwnerId(expected(), accountBackup.accountId)) { "Identity backup does not match the retained journal owner" }
            }
            if (storedAccount == null) secureStorage.putString(KEY_ACCOUNT_ID, accountBackup.accountId)
            secureStorage.putBytes(KEY_IDENTITY_KEY, accountBackup.key)
            secureStorage.remove(KEY_RECOVERY_PHRASE)
            secureStorage.remove(KEY_RECOVERY_VERIFIED)
            ensurePortableBackupLocked()
            Napier.i("Account encryption identity restored from native backup")
            return true
        }

        val phraseText = backupStore.readPhrase() ?: return false
        val words =
            phraseText
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        if (words.size != RECOVERY_PHRASE_WORD_COUNT || !cryptoManager.validateRecoveryPhrase(words)) {
            Napier.w("Identity key backup held an unusable recovery phrase; ignoring it")
            return false
        }

        val identityKey = cryptoManager.deriveMasterKey(words)
        secureStorage.remove(KEY_RECOVERY_VERIFIED)
        secureStorage.putBytes(KEY_IDENTITY_KEY, identityKey)
        secureStorage.putString(KEY_RECOVERY_PHRASE, phraseText)
        ensurePortableBackupLocked()
        Napier.i("Identity key silently restored from backup store")
        return true
    }

    /**
     * Retrieves the user's recovery phrase from local secure storage.
     *
     * This is local-only. Account key sync transfers the derived key, never the phrase.
     */
    suspend fun getStoredRecoveryPhrase(): RecoveryPhrase? {
        val phrase =
            secureStorage
                .getString(KEY_RECOVERY_PHRASE)
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?: return null
        if (phrase.size != RECOVERY_PHRASE_WORD_COUNT) {
            Napier.w("Stored recovery phrase is malformed")
            return null
        }
        return RecoveryPhrase(phrase)
    }

    /** Reads an existing account root for personal-space sync, without provisioning or adoption. */
    suspend fun getAccountIdentityKey(accountId: String): ByteArray =
        identityMutex.withLock {
            val owner = secureStorage.getString(KEY_ACCOUNT_ID)
            check(owner != null && sameOwnerId(owner, accountId)) { "This device needs the account's encryption keys" }
            expectedAccountOwner?.invoke()?.let {
                check(sameOwnerId(it, accountId)) { "The encryption keys do not match the retained journal owner" }
            }
            getIdentityKey().also { portable.validateRoot(it, owner) }
        }

    /**
     * Retrieves the identity key for encryption/decryption operations.
     *
     * @throws IdentityKeyNotFoundException if no identity has been set up
     */
    suspend fun getIdentityKey(): ByteArray {
        val key = secureStorage.getBytes(KEY_IDENTITY_KEY)
        if (key == null) {
            Napier.w("Identity key not found - user must set up or recover")
            throw IdentityKeyNotFoundException(
                "No identity key found. User must complete onboarding or recovery.",
            )
        }
        return key
    }

    /**
     * Clears the identity key from the device.
     *
     * Used when user explicitly signs out or unlinks device.
     */
    suspend fun clearIdentityKey() =
        identityMutex.withLock {
            secureStorage.remove(KEY_IDENTITY_KEY)
            secureStorage.remove(KEY_MEDIA_KEY)
            secureStorage.remove(KEY_ACCOUNT_ID)
            secureStorage.remove(KEY_RECOVERY_PHRASE)
            secureStorage.remove(KEY_RECOVERY_VERIFIED)
            backupStore.clear()
            Napier.d("Identity key cleared from device")
        }

    companion object {
        private const val KEY_MEDIA_KEY = "media_payload_key_v1"
        private const val KEY_IDENTITY_KEY = "identity_key_v1"
        private const val KEY_ACCOUNT_ID = "identity_account_id_v1"
        private const val KEY_RECOVERY_PHRASE = "identity_recovery_phrase_v1"
        private const val KEY_RECOVERY_VERIFIED = "identity_recovery_verified_v1"
        private const val RECOVERY_PHRASE_WORD_COUNT = 12
    }
}

/**
 * Represents a 12-word BIP-39 recovery phrase.
 *
 * These words should be written down and stored safely by the user.
 */
@Serializable
data class RecoveryPhrase(
    val words: List<String>,
) {
    init {
        require(words.size == 12) { "Recovery phrase must be exactly 12 words" }
    }

    /**
     * Returns the phrase as a space-separated string for display/entry.
     */
    override fun toString(): String = words.joinToString(" ")
}

class IdentityKeyNotFoundException(
    message: String,
) : Exception(message)

/** Whether this device may derive an identity key of its own. */
enum class IdentityMintPolicy {
    ALLOWED,

    /** A linked watch: it only ever uses the key its phone installed. */
    NEVER,
}

class IdentityMintingNotAllowedException :
    IllegalStateException("This device never creates its own identity key; it is linked from a phone")

class IdentityKeyFingerprintMismatchException : IllegalArgumentException("The identity key does not match the fingerprint sent with it")

class IdentityRecoveryOwnerMismatchException : Exception("The retained journal belongs to another account")

class IdentityRecoverySessionChangedException : Exception("The signed-in account changed during recovery")
