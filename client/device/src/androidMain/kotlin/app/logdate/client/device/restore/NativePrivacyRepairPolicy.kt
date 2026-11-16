package app.logdate.client.device.restore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.logdate.client.datastore.LegacyCloudCredentialMigration
import app.logdate.client.datastore.LogdatePreferencesDataSource
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** A candidate is not authority: completed delivery and protected-byte proofs are checked separately. */
internal fun nativePrivacyRepairPolicy(
    current: Preferences,
    incoming: Preferences,
    currentPolicyReceived: Boolean = true,
): Preferences? {
    if ((currentPolicyReceived && NativeRestorePrivacyPolicy.hasKnownPrivacyMetadata(current)) ||
        !NativeRestorePrivacyPolicy.hasKnownPrivacyMetadata(incoming)
    ) {
        return null
    }
    if (nativePrivacyPreferenceFingerprint(nonPrivacyRepairFields(current)) !=
        nativePrivacyPreferenceFingerprint(nonPrivacyRepairFields(incoming))
    ) {
        return null
    }
    return current.toMutablePreferences().apply {
        remove(LogdatePreferencesDataSource.IS_ONBOARDED)
        remove(LogdatePreferencesDataSource.SECURITY_LEVEL)
        incoming[LogdatePreferencesDataSource.IS_ONBOARDED]?.let { this[LogdatePreferencesDataSource.IS_ONBOARDED] = it }
        incoming[LogdatePreferencesDataSource.SECURITY_LEVEL]?.let { this[LogdatePreferencesDataSource.SECURITY_LEVEL] = it }
    }
}

/** A typed digest binds the whole CAS state, including installation values omitted only from source comparison. */
internal fun nativePrivacyPreferenceFingerprint(preferences: Preferences): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val discarded =
        object : OutputStream() {
            override fun write(value: Int) = Unit

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) = Unit
        }
    DataOutputStream(DigestOutputStream(discarded, digest)).use { output ->
        fun text(value: String) {
            val bytes = value.encodeToByteArray()
            output.writeInt(bytes.size)
            output.write(bytes)
        }
        val values = preferences.asMap().entries.sortedBy { it.key.name }
        output.writeInt(values.size)
        for ((key, value) in values) {
            text(key.name)
            when (value) {
                is Boolean -> {
                    output.writeByte(1)
                    output.writeBoolean(value)
                }

                is Int -> {
                    output.writeByte(2)
                    output.writeInt(value)
                }

                is Long -> {
                    output.writeByte(3)
                    output.writeLong(value)
                }

                is Float -> {
                    output.writeByte(4)
                    output.writeInt(value.toBits())
                }

                is Double -> {
                    output.writeByte(5)
                    output.writeLong(value.toBits())
                }

                is String -> {
                    output.writeByte(6)
                    text(value)
                }

                is Set<*> -> {
                    output.writeByte(7)
                    check(value.all { it is String })
                    val strings = value.map { it as String }.sorted()
                    output.writeInt(strings.size)
                    strings.forEach(::text)
                }

                is ByteArray -> {
                    output.writeByte(8)
                    output.writeInt(value.size)
                    output.write(value)
                }

                else -> {
                    error("Unsupported native preference type")
                }
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun nonPrivacyRepairFields(preferences: Preferences): Preferences =
    LegacyCloudCredentialMigration.withoutCredentials(preferences).toMutablePreferences().apply {
        remove(LogdatePreferencesDataSource.IS_ONBOARDED)
        remove(LogdatePreferencesDataSource.SECURITY_LEVEL)
        remove(stringPreferencesKey("device.id"))
    }
