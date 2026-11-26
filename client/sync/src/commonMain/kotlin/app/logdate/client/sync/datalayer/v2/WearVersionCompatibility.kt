package app.logdate.client.sync.datalayer.v2

import kotlinx.serialization.Serializable

/**
 * Lightweight version and protocol summary returned by [WearPaths.WATCH_PING] so the phone can
 * identify app presence and version skew even before the watch finishes warming up storage.
 */
@Serializable
data class WatchPingInfo(
    val protocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    val minPeerProtocolVersion: Int = WearProtocol.MIN_COMPATIBLE_PROTOCOL_VERSION,
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
) {
    fun encodeToBytes(): ByteArray = WearJson.encodeToBytes(serializer(), this)

    companion object {
        fun decodeOrNull(bytes: ByteArray): WatchPingInfo? {
            if (bytes.isEmpty() || (bytes.size == 1 && bytes[0] == 1.toByte())) return null
            return (WearJson.decode(bytes, 1, serializer()) as? Decoded.Supported)?.value
        }

        fun isLegacyAck(bytes: ByteArray): Boolean = bytes.size == 1 && bytes[0] == 1.toByte()
    }
}

/** Compatibility verdict between the phone and watch apps. */
sealed interface WearCompatibility {
    val phoneVersion: String?
    val watchVersion: String?

    data class Compatible(
        override val phoneVersion: String? = null,
        override val watchVersion: String? = null,
        val versionsDiffer: Boolean = false,
    ) : WearCompatibility

    data class WatchUpdateRequired(
        override val phoneVersion: String? = null,
        override val watchVersion: String? = null,
    ) : WearCompatibility

    data class PhoneUpdateRequired(
        override val phoneVersion: String? = null,
        override val watchVersion: String? = null,
    ) : WearCompatibility
}

/**
 * Evaluates whether phone and watch speak a compatible protocol, distinguishing an older watch
 * build from an older phone build without penalizing differing patch releases that still share
 * a compatible protocol.
 */
fun evaluateWearCompatibility(
    localAppVersion: String? = null,
    peerAppVersion: String? = null,
    peerProtocolVersion: Int? = null,
    peerMinPeerProtocolVersion: Int? = null,
    peerSchemaUnsupported: Boolean = false,
    legacyPeerPing: Boolean = false,
    localProtocolVersion: Int = WearProtocol.PROTOCOL_VERSION,
    localMinPeerProtocolVersion: Int = WearProtocol.MIN_COMPATIBLE_PROTOCOL_VERSION,
): WearCompatibility {
    if (peerSchemaUnsupported || (peerMinPeerProtocolVersion != null && localProtocolVersion < peerMinPeerProtocolVersion)) {
        return WearCompatibility.PhoneUpdateRequired(
            phoneVersion = localAppVersion,
            watchVersion = peerAppVersion,
        )
    }
    if ((peerProtocolVersion != null && peerProtocolVersion < localMinPeerProtocolVersion) ||
        (legacyPeerPing && peerProtocolVersion == null)
    ) {
        return WearCompatibility.WatchUpdateRequired(
            phoneVersion = localAppVersion,
            watchVersion = peerAppVersion,
        )
    }
    val versionsDiffer =
        !localAppVersion.isNullOrBlank() &&
            !peerAppVersion.isNullOrBlank() &&
            localAppVersion != peerAppVersion
    return WearCompatibility.Compatible(
        phoneVersion = localAppVersion,
        watchVersion = peerAppVersion,
        versionsDiffer = versionsDiffer,
    )
}
