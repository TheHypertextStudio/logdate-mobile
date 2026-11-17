package app.logdate.client.device.crypto

import app.logdate.client.device.storage.putBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [IdentityKeyManager], which coordinates the lifecycle of the user's
 * primary identity keys.
 *
 * These tests cover the initial setup of an identity, recovery from a mnemonic phrase,
 * and the secure clearing of identity material from storage.
 */
class IdentityKeyManagerTest {
    private val mockSecureStorage = InMemorySecureStorage()
    private val cryptoManager = FakeCryptoManager()
    private val backupStore = InMemoryIdentityKeyBackupStore()
    private val manager = IdentityKeyManager(mockSecureStorage, cryptoManager, backupStore)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `identity changes signal installation binding and removal without exposing keys`() =
        runTest {
            var changes = 0
            backgroundScope.launch { manager.observeIdentityChanges().collect { changes++ } }
            runCurrent()
            val initial = changes
            manager.installLinkedIdentityKey(ByteArray(32) { 1 }, IdentityKeyFingerprint.of(ByteArray(32) { 1 }, cryptoManager))
            runCurrent()
            assertTrue(changes > initial)
            val installed = changes
            manager.bindToAccount("account")
            runCurrent()
            assertTrue(changes > installed)
            val bound = changes
            manager.clearIdentityKey()
            runCurrent()
            assertTrue(changes > bound)
        }

    @Test
    fun `an existing phrase-less identity gains portable recovery on upgrade`() =
        runTest {
            val key = ByteArray(32) { 0x42 }
            mockSecureStorage.putBytes("identity_key_v1", key)
            mockSecureStorage.putString("identity_account_id_v1", "account-a")
            manager.ensureIdentityKey()
            assertTrue(key.contentEquals(backupStore.readAccountIdentity()?.key))
            assertEquals("account-a", backupStore.readAccountIdentity()?.accountId)
        }

    @Test
    fun `native recovery retains an account key that has no recovery phrase`() =
        runTest {
            val key = ByteArray(32) { 0x31 }
            manager.installAccountKey("account-a", key)
            val destination = IdentityKeyManager(InMemorySecureStorage(), cryptoManager, backupStore)

            destination.ensureIdentityKey()

            assertTrue(key.contentEquals(destination.getIdentityKey()))
            assertEquals(null, destination.getStoredRecoveryPhrase())
            assertFalse(destination.bindToAccount("account-b"))
        }

    @Test
    fun `a malformed retained backup cannot mint an unrelated replacement identity`() =
        runTest {
            backupStore.writePhrase("truncated words")
            assertFailsWith<IllegalStateException> { manager.ensureIdentityKey() }
            assertFalse(manager.hasIdentityKey())
            assertEquals("truncated words", backupStore.readPhrase())
        }

    @Test
    fun `account key backup is refused when the retained journal belongs to another owner`() =
        runTest {
            manager.installAccountKey("account-a", ByteArray(32) { 0x31 })
            val destination =
                IdentityKeyManager(
                    InMemorySecureStorage(),
                    cryptoManager,
                    backupStore,
                    expectedAccountOwner = { "account-b" },
                )
            assertFailsWith<IllegalStateException> { destination.restoreFromBackupIfAvailable() }
            assertFalse(destination.hasIdentityKey())
        }

    @Test
    fun `account key restore binds the identity to one account`() =
        runTest {
            val key = ByteArray(32) { 0x31 }
            manager.installAccountKey("account-a", key)
            assertTrue(manager.hasIdentityKey())
            assertTrue(manager.getIdentityKey().contentEquals(key))
            assertEquals(null, manager.getStoredRecoveryPhrase())
            assertTrue(manager.bindToAccount("account-a"))
            assertFalse(manager.bindToAccount("account-b"))
            assertFailsWith<IllegalArgumentException> {
                manager.installAccountKey("account-b", ByteArray(32) { 0x42 })
            }
        }

    @Test
    fun `a device that never mints refuses to create a key`() =
        runTest {
            val linked = linkedManager()

            assertFailsWith<IdentityMintingNotAllowedException> { linked.ensureIdentityKey() }
            assertFailsWith<IdentityMintingNotAllowedException> { linked.setupNewIdentity() }
            assertFalse(linked.hasIdentityKey())
        }

    @Test
    fun `linked key preflight validates length fingerprint and existing key without mutation`() =
        runTest {
            val key = ByteArray(32) { 0x31 }
            val fingerprint = IdentityKeyFingerprint.of(key, cryptoManager)
            assertTrue(manager.canInstallLinkedIdentityKey(key, fingerprint))
            assertFalse(manager.hasIdentityKey())
            assertFalse(manager.canInstallLinkedIdentityKey(ByteArray(1), fingerprint))
            assertFalse(manager.canInstallLinkedIdentityKey(key, "wrong"))
            manager.installLinkedIdentityKey(key, fingerprint)
            val other = ByteArray(32) { 0x42 }
            assertFalse(manager.canInstallLinkedIdentityKey(other, IdentityKeyFingerprint.of(other, cryptoManager)))
            assertTrue(manager.canInstallLinkedIdentityKey(key, fingerprint))
            assertTrue(manager.getIdentityKey().contentEquals(key))
        }

    @Test
    fun `a linked key is installed when it matches its fingerprint`() =
        runTest {
            val linked = linkedManager()
            val key = ByteArray(32) { 0x31 }

            linked.installLinkedIdentityKey(key, IdentityKeyFingerprint.of(key, cryptoManager))

            assertTrue(linked.getIdentityKey().contentEquals(key))
            assertEquals(null, linked.getStoredRecoveryPhrase())
        }

    @Test
    fun `a linked key that does not match its fingerprint is refused`() =
        runTest {
            val linked = linkedManager()
            val key = ByteArray(32) { 0x31 }

            assertFailsWith<IdentityKeyFingerprintMismatchException> {
                linked.installLinkedIdentityKey(key, IdentityKeyFingerprint.of(ByteArray(32) { 0x32 }, cryptoManager))
            }
            assertFalse(linked.hasIdentityKey())
        }

    @Test
    fun `a linked key never replaces a different key`() =
        runTest {
            val linked = linkedManager()
            val key = ByteArray(32) { 0x31 }
            val other = ByteArray(32) { 0x32 }
            linked.installLinkedIdentityKey(key, IdentityKeyFingerprint.of(key, cryptoManager))

            assertFailsWith<IllegalStateException> {
                linked.installLinkedIdentityKey(
                    other,
                    IdentityKeyFingerprint.of(other, cryptoManager),
                )
            }
            assertTrue(linked.getIdentityKey().contentEquals(key))
        }

    @Test
    fun `installing the same linked key again is harmless`() =
        runTest {
            val linked = linkedManager()
            val key = ByteArray(32) { 0x31 }

            repeat(2) { linked.installLinkedIdentityKey(key, IdentityKeyFingerprint.of(key, cryptoManager)) }

            assertTrue(linked.getIdentityKey().contentEquals(key))
        }

    @Test
    fun `a linked key of the wrong length is refused`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { linkedManager().installLinkedIdentityKey(ByteArray(16), "unused") }
        }

    @Test
    fun `installing the key a device already has leaves its recovery phrase alone`() =
        runTest {
            val phrase = manager.setupNewIdentity()
            val key = manager.getIdentityKey()

            manager.installLinkedIdentityKey(key, IdentityKeyFingerprint.of(key, cryptoManager))

            assertEquals(phrase, manager.getStoredRecoveryPhrase())
            assertEquals(phrase.toString(), backupStore.readPhrase())
        }

    @Test
    fun `a linked key replaces a backup phrase left from before`() =
        runTest {
            val staleBackup = InMemoryIdentityKeyBackupStore()
            staleBackup.writePhrase((1..12).joinToString(" ") { "stale-$it" })
            val linked = IdentityKeyManager(InMemorySecureStorage(), cryptoManager, staleBackup, IdentityMintPolicy.NEVER)
            val key = ByteArray(32) { 0x31 }

            linked.installLinkedIdentityKey(key, IdentityKeyFingerprint.of(key, cryptoManager))

            assertEquals(null, staleBackup.readPhrase())
        }

    private fun linkedManager() = IdentityKeyManager(InMemorySecureStorage(), cryptoManager, mintPolicy = IdentityMintPolicy.NEVER)

    @Test
    fun `no identity key initially`() =
        runTest {
            assertFalse(manager.hasIdentityKey())
        }

    @Test
    fun `ensure identity key creates one when absent`() =
        runTest {
            assertFalse(manager.hasIdentityKey())

            manager.ensureIdentityKey()

            assertTrue(manager.hasIdentityKey())
            assertEquals(12, manager.getStoredRecoveryPhrase()?.words?.size)
        }

    @Test
    fun `only this identity's phrase in its own order verifies`() =
        runTest {
            manager.ensureIdentityKey()
            val words = requireNotNull(manager.getStoredRecoveryPhrase()).words
            assertFalse(manager.verifyRecoveryPhrase(words.reversed()))
            assertTrue(manager.verifyRecoveryPhrase(words))
        }

    @Test
    fun `ensure identity key is idempotent and preserves the original phrase`() =
        runTest {
            manager.ensureIdentityKey()
            val firstPhrase = manager.getStoredRecoveryPhrase()
            val firstKey = manager.getIdentityKey()

            manager.ensureIdentityKey()

            assertEquals(firstPhrase, manager.getStoredRecoveryPhrase())
            assertTrue(firstKey.contentEquals(manager.getIdentityKey()))
        }

    @Test
    fun `ensure identity key leaves a recovered identity untouched`() =
        runTest {
            val original = manager.setupNewIdentity()
            manager.clearIdentityKey()
            manager.recoverIdentity(original.words)

            manager.ensureIdentityKey()

            assertEquals(original, manager.getStoredRecoveryPhrase())
        }

    @Test
    fun `setup new identity`() =
        runTest {
            val phrase = manager.setupNewIdentity()

            assertEquals(12, phrase.words.size)
            assertTrue(manager.hasIdentityKey())
            assertEquals(phrase, manager.getStoredRecoveryPhrase())
        }

    @Test
    fun `get identity key after setup`() =
        runTest {
            manager.setupNewIdentity()
            val key = manager.getIdentityKey()

            assertEquals(32, key.size)
        }

    @Test
    fun `recover identity`() =
        runTest {
            val phrase1 = manager.setupNewIdentity()
            val key1 = manager.getIdentityKey()

            manager.clearIdentityKey()
            assertFalse(manager.hasIdentityKey())

            manager.recoverIdentity(phrase1.words)
            assertTrue(manager.hasIdentityKey())
            assertEquals(phrase1, manager.getStoredRecoveryPhrase())

            val key2 = manager.getIdentityKey()
            assertTrue(key1.contentEquals(key2), "Same phrase should derive same key")
        }

    @Test
    fun `deterministic key derivation`() =
        runTest {
            val phrase = manager.setupNewIdentity()
            val key1 = manager.getIdentityKey()

            manager.clearIdentityKey()
            manager.recoverIdentity(phrase.words)
            val key2 = manager.getIdentityKey()

            assertTrue(key1.contentEquals(key2))
        }

    @Test
    fun `get identity key throws when not set`() =
        runTest {
            assertFailsWith<IdentityKeyNotFoundException> {
                manager.getIdentityKey()
            }
        }

    @Test
    fun `clear identity key`() =
        runTest {
            manager.setupNewIdentity()
            assertTrue(manager.hasIdentityKey())

            manager.clearIdentityKey()
            assertFalse(manager.hasIdentityKey())
            assertEquals(null, manager.getStoredRecoveryPhrase())
        }

    @Test
    fun `setup new identity mirrors the phrase into the backup store`() =
        runTest {
            val phrase = manager.setupNewIdentity()

            assertEquals(phrase.toString(), backupStore.readPhrase())
        }

    @Test
    fun `recover identity mirrors the phrase into the backup store`() =
        runTest {
            val phrase = manager.setupNewIdentity()
            manager.clearIdentityKey()
            backupStore.clear()

            manager.recoverIdentity(phrase.words)

            assertEquals(phrase.toString(), backupStore.readPhrase())
        }

    @Test
    fun `clear identity key also clears the backup store`() =
        runTest {
            manager.setupNewIdentity()
            assertTrue(backupStore.readPhrase() != null)

            manager.clearIdentityKey()

            assertEquals(null, backupStore.readPhrase())
        }

    @Test
    fun `restoring from backup does nothing when a key already exists`() =
        runTest {
            manager.setupNewIdentity()
            val existingKey = manager.getIdentityKey()
            backupStore.writePhrase((1..12).joinToString(" ") { "backup-$it" })

            val restored = manager.restoreFromBackupIfAvailable()

            assertFalse(restored)
            assertTrue(existingKey.contentEquals(manager.getIdentityKey()))
        }

    @Test
    fun `restoring from backup does nothing when there is no backup`() =
        runTest {
            val restored = manager.restoreFromBackupIfAvailable()

            assertFalse(restored)
            assertFalse(manager.hasIdentityKey())
        }

    @Test
    fun `restoring from backup ignores a malformed phrase and does not mint a key`() =
        runTest {
            backupStore.writePhrase("not enough words")

            val restored = manager.restoreFromBackupIfAvailable()

            assertFalse(restored)
            assertFalse(manager.hasIdentityKey())
        }

    @Test
    fun `restoring from backup silently derives the same key the phrase would have set up`() =
        runTest {
            // Simulate a first device establishing an identity, then losing SecureStorage (a
            // reinstall) while its backup survives.
            val original = manager.setupNewIdentity()
            val originalKey = manager.getIdentityKey()
            val freshManager = IdentityKeyManager(InMemorySecureStorage(), cryptoManager, backupStore)

            val restored = freshManager.restoreFromBackupIfAvailable()

            assertTrue(restored)
            assertTrue(freshManager.hasIdentityKey())
            assertEquals(original, freshManager.getStoredRecoveryPhrase())
            assertTrue(originalKey.contentEquals(freshManager.getIdentityKey()))
        }

    @Test
    fun `ensure identity key restores from backup instead of minting a new identity`() =
        runTest {
            val original = manager.setupNewIdentity()
            val originalKey = manager.getIdentityKey()
            val freshManager = IdentityKeyManager(InMemorySecureStorage(), cryptoManager, backupStore)

            freshManager.ensureIdentityKey()

            assertEquals(original, freshManager.getStoredRecoveryPhrase())
            assertTrue(originalKey.contentEquals(freshManager.getIdentityKey()))
        }
}
