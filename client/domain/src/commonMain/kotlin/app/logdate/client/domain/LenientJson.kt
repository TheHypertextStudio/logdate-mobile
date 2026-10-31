package app.logdate.client.domain

import kotlinx.serialization.json.Json

/**
 * Reads stored or received JSON that may carry fields this version does not know. Every lenient
 * reader in this module shares it, because building a [Json] configuration is costly.
 */
internal val LenientJson: Json = Json { ignoreUnknownKeys = true }
