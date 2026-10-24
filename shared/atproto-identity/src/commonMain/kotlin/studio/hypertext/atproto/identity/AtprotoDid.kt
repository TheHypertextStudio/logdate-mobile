package studio.hypertext.atproto.identity

import kotlinx.serialization.Serializable
import studio.hypertext.atproto.syntax.Did
import kotlin.jvm.JvmInline

private val plcIdentifierRegex: Regex = Regex("^[a-z2-7]{24}$")
private val didWebHostRegex: Regex =
    Regex("^(localhost|([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)$")

/**
 * DID restricted to the forms accepted by the AT Protocol identity spec.
 *
 * Supported values are `did:plc` and hostname-only `did:web`.
 *
 * @property value Underlying generic DID value.
 */
@JvmInline
@Serializable
public value class AtprotoDid(
    public val value: Did,
) {
    init {
        validateAtprotoDid(value)
    }

    /**
     * DID method name.
     */
    public val method: String
        get() = value.method

    /**
     * Returns the raw DID string.
     */
    override fun toString(): String = value.toString()

    /**
     * Factory helpers for [AtprotoDid].
     */
    public companion object {
        /**
         * Parses [value] into an [AtprotoDid], returning failures as [Result].
         */
        public fun parse(value: String): Result<AtprotoDid> = runCatching { AtprotoDid(Did.require(value)) }

        /**
         * Parses [value] into an [AtprotoDid] or throws [InvalidAtprotoDidException].
         */
        public fun require(value: String): AtprotoDid = AtprotoDid(Did.require(value))
    }
}

internal fun AtprotoDid.didWebDocumentUrl(): String {
    if (method != "web") {
        throw InvalidAtprotoDidException(value.value)
    }

    val decodedAuthority: String = decodeDidWebAuthority(value.identifier)
    return "https://$decodedAuthority/.well-known/did.json"
}

internal fun AtprotoDid.didPlcDocumentUrl(): String {
    if (method != "plc") {
        throw InvalidAtprotoDidException(value.value)
    }
    return "https://plc.directory/${value.value}"
}

private fun validateAtprotoDid(value: Did) {
    when (value.method) {
        "plc" -> {
            if (!plcIdentifierRegex.matches(value.identifier)) {
                throw InvalidAtprotoDidException(value.value)
            }
        }

        "web" -> {
            validateDidWebIdentifier(value.identifier, value.value)
        }

        else -> {
            throw InvalidAtprotoDidException(value.value)
        }
    }
}

private fun validateDidWebIdentifier(
    identifier: String,
    original: String,
) {
    if (identifier.contains(':')) {
        throw InvalidAtprotoDidException(original)
    }

    val decodedAuthority: String = decodeDidWebAuthority(identifier)
    val host: String = decodedAuthority.substringBefore(':')
    val port: String = decodedAuthority.substringAfter(':', "")
    if (!didWebHostRegex.matches(host)) {
        throw InvalidAtprotoDidException(original)
    }
    if (port.isNotEmpty() && port.toIntOrNull() == null) {
        throw InvalidAtprotoDidException(original)
    }
}

private fun decodeDidWebAuthority(identifier: String): String = identifier.replace("%3A", ":", ignoreCase = true)
