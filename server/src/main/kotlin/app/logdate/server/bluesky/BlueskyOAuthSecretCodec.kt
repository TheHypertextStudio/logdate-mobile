package app.logdate.server.bluesky

import app.logdate.server.identity.AtRestKeyCipher
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** Encrypted OAuth values are bound to their logical store and lookup key, not interchangeable blobs. */
internal class BlueskyOAuthSecretCodec(
    private val cipher: AtRestKeyCipher,
) {
    private val json = Json { encodeDefaults = true }

    fun <T> encrypt(
        store: String,
        key: String,
        value: T,
        serializer: SerializationStrategy<T>,
    ): String {
        val envelope = BoundSecret(store = store, key = key, payload = json.encodeToJsonElement(serializer, value))
        return cipher.encrypt(json.encodeToString(BoundSecret.serializer(), envelope).encodeToByteArray())
    }

    fun <T> decrypt(
        store: String,
        key: String,
        encrypted: String,
        serializer: DeserializationStrategy<T>,
    ): T =
        try {
            val envelope = json.decodeFromString(BoundSecret.serializer(), cipher.decrypt(encrypted).decodeToString())
            require(envelope.version == 1 && envelope.store == store && envelope.key == key)
            json.decodeFromJsonElement(serializer, envelope.payload)
        } catch (_: Exception) {
            // Serializer failures can include decrypted input. Never retain that input in a cause or log.
            throw IllegalArgumentException("Stored Bluesky sign-in data could not be verified")
        }

    @Serializable
    private data class BoundSecret(
        val version: Int = 1,
        val store: String,
        val key: String,
        val payload: JsonElement,
    )
}
