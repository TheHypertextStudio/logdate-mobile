package app.logdate.client.device.crypto

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * The items behind the iOS identity backup ([ItemIdentityKeyBackupStore]): the recovery phrase, or
 * an account key with its media key, plus the media keys, each in its own iCloud Keychain item. This is Apple's counterpart to Android's
 * backup files ([AndroidIdentityKeyBackupStore]) and uses the same record formats.
 *
 * These items are kept apart from [app.logdate.client.device.storage.IosSecureStorage], which also
 * holds session tokens that must never sync to other devices. Only the identity material needed
 * to read already-synced content after a reinstall or on a new device goes here.
 *
 * iCloud Keychain items are always end-to-end encrypted. Syncing an app's own items needs no
 * entitlement, only iCloud Keychain turned on; when it is off, the items stay on this device.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class ICloudKeychainIdentityBackupItems(
    private val serviceName: String = "app.logdate.identity-recovery",
) : IdentityBackupItems {
    override suspend fun read(name: String): ByteArray? =
        memScoped {
            val query = baseQuery(name).toMutableMap()
            query[kSecReturnData] = kCFBooleanTrue
            query[kSecMatchLimit] = kSecMatchLimitOne
            val result = alloc<CFTypeRefVar>()
            val status = withKeychainDictionary(query) { SecItemCopyMatching(it, result.ptr) }
            when (status) {
                errSecItemNotFound -> null
                errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray() ?: error("The identity backup item is empty")
                else -> throw IllegalStateException("The identity backup could not be read: status=$status")
            }
        }

    override suspend fun write(
        name: String,
        value: ByteArray,
    ) {
        val data = value.toNSData()
        val status = withKeychainDictionary(baseQuery(name) + (kSecValueData to data)) { SecItemAdd(it, null) }
        if (status == errSecSuccess) return
        check(status == errSecDuplicateItem) { "The identity backup could not be saved: status=$status" }
        val updateStatus =
            withKeychainDictionary(baseQuery(name)) { query ->
                withKeychainDictionary(mapOf(kSecValueData to data)) { attributes -> SecItemUpdate(query, attributes) }
            }
        check(updateStatus == errSecSuccess) { "The identity backup could not be saved: status=$updateStatus" }
    }

    override suspend fun delete(name: String) {
        val status = withKeychainDictionary(baseQuery(name)) { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "The identity backup could not be removed: status=$status" }
    }

    private fun baseQuery(name: String): Map<Any?, Any?> =
        mapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to serviceName,
            kSecAttrAccount to name,
            kSecAttrSynchronizable to kCFBooleanTrue,
        )

    private inline fun <T> withKeychainDictionary(
        query: Map<Any?, Any?>,
        block: (CFDictionaryRef) -> T,
    ): T {
        // Security framework functions take CoreFoundation dictionaries, so build an NSDictionary
        // and bridge it to a CFDictionaryRef.
        val nsQuery = NSMutableDictionary()
        query.forEach { (key, value) ->
            if (key != null && value != null) {
                val copyKey = key as? NSCopyingProtocol ?: error("Keychain query key does not conform to NSCopyingProtocol.")
                nsQuery.setObject(value, forKey = copyKey)
            }
        }
        val retained = CFBridgingRetain(nsQuery) ?: error("CFBridgingRetain returned null for keychain query dictionary")
        try {
            return block(retained.reinterpret())
        } finally {
            CFRelease(retained)
        }
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }

    private fun NSData.toByteArray(): ByteArray {
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
        return bytes
    }
}
