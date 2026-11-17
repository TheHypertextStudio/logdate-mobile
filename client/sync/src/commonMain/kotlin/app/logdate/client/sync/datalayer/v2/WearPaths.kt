package app.logdate.client.sync.datalayer.v2

import app.logdate.client.sync.peer.delivery.CaptureIdentity
import app.logdate.client.sync.peer.link.LinkWire
import kotlin.uuid.Uuid

/**
 * The Data Layer paths of version 2.
 *
 * None starts with a prefix a version 1 listener filters on (`/logdate/notes`, `/logdate/journals`,
 * `/logdate/associations`, `/logdate/health`, `/logdate/camera`, `/logdate/sync`), so a build without
 * version 2 never wakes for one.
 */
object WearPaths {
    /** Each device's state document, one data item per node. */
    const val STATE = "/logdate/state"

    const val RECEIPTS = "/logdate/v2/receipts"
    const val RECEIPTS_PUSH = "/logdate/v2/receipts/push"
    const val WINDOW_RESYNC = "/logdate/v2/window/resync"

    const val LINK_OFFER = "/logdate/link/offer"
    const val LINK_BUNDLE = "/logdate/link/bundle"

    const val LIVE_RECORDING = "/logdate/live/recording"
    const val LIVE_RECORDING_COMMAND = "/logdate/live/recording/command"
    const val LIVE_PLAYBACK = "/logdate/live/playback"
    const val LIVE_PLAYBACK_COMMAND = "/logdate/live/playback/command"

    const val REMINDERS_DELIVER = "/logdate/reminders/deliver"
    const val REMINDERS_CLAIM = "/logdate/reminders/claim"
    const val PRESENCE = "/logdate/presence"
    const val DIAG_PING = "/logdate/diag/ping"
    const val FAVORITES_SET = "/logdate/favorites/set"

    private const val WINDOW_PREFIX = "/logdate/v2/p2w/window/"
    private const val TOMBSTONE_PREFIX = "/logdate/v2/p2w/tombstone/"
    private const val MEDIA_PREFIX = "/logdate/v2/w2p/media/"
    private const val RECORD_PREFIX = "/logdate/v2/w2p/record/"
    private const val BODY_PREFIX = "/logdate/v2/body/"
    private val LINK_PATH_SEGMENT = Regex("[A-Za-z0-9_-]{43}")
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /** A record in the watch's library window, written by the phone. */
    fun windowItem(
        kind: RecordKind,
        id: Uuid,
    ): String = "$WINDOW_PREFIX${kind.pathSegment}/$id"

    /** A record that left the window or was deleted on the phone. */
    fun windowTombstone(
        kind: RecordKind,
        id: Uuid,
    ): String = "$TOMBSTONE_PREFIX${kind.pathSegment}/$id"

    fun windowItem(
        kind: RecordKind,
        id: Uuid,
        context: PeerDeliveryContext,
    ): String = "${windowItem(kind, id)}/${context.linkPathSegment}"

    fun windowTombstone(
        kind: RecordKind,
        id: Uuid,
        context: PeerDeliveryContext,
    ): String = "${windowTombstone(kind, id)}/${context.linkPathSegment}"

    /** A record made on the watch, on its way to the phone. */
    fun watchRecord(
        kind: RecordKind,
        id: Uuid,
    ): String = "$RECORD_PREFIX${kind.pathSegment}/$id"

    fun watchRecord(
        kind: RecordKind,
        id: Uuid,
        operationId: String,
    ): String {
        require(CaptureIdentity.of(id.toString(), operationId) != null) { "A record needs a valid operation identity" }
        return "${watchRecord(kind, id)}/op/$operationId"
    }

    fun watchRecord(
        kind: RecordKind,
        id: Uuid,
        context: PeerDeliveryContext,
        operationId: String,
    ): String {
        require(CaptureIdentity.of(id.toString(), operationId) != null) { "A record needs a valid operation identity" }
        return "${watchRecord(kind, id)}/${context.linkPathSegment}/op/$operationId"
    }

    /** The channel a body too large for a data item travels on. */
    fun bodyChannel(sha256Hex: String): String {
        require(SHA256_HEX.matches(sha256Hex)) { "A body channel is named by a lowercase hex SHA-256" }
        return "$BODY_PREFIX$sha256Hex"
    }

    fun parseWindowItem(path: String): RecordPath? = parseWindow(path, WINDOW_PREFIX)

    fun parseWindowTombstone(path: String): RecordPath? = parseWindow(path, TOMBSTONE_PREFIX)

    fun parseWatchRecord(path: String): RecordPath? {
        if (!path.startsWith(RECORD_PREFIX)) return null
        val parts = path.removePrefix(RECORD_PREFIX).split('/')
        if (parts.size == 2) return parse(path, RECORD_PREFIX)
        if (parts.size == 5) return parseRecordMedia(MEDIA_PREFIX + path.removePrefix(RECORD_PREFIX))
        if (parts.size != 4 || parts[2] != "op") return null
        val record = parse(RECORD_PREFIX + parts.take(2).joinToString("/"), RECORD_PREFIX) ?: return null
        val identity = CaptureIdentity.of(record.id.toString(), parts[3]) ?: return null
        return record.copy(operationId = identity.operationId)
    }

    /** The channel the media file of a record made on the watch travels on. */
    fun recordMedia(
        kind: RecordKind,
        id: Uuid,
    ): String = "$MEDIA_PREFIX${kind.pathSegment}/$id"

    fun recordMedia(
        kind: RecordKind,
        id: Uuid,
        context: PeerDeliveryContext,
    ): String = "${recordMedia(kind, id)}/${context.linkPathSegment}"

    fun recordMedia(
        kind: RecordKind,
        id: Uuid,
        context: PeerDeliveryContext,
        operationId: String,
    ): String {
        require(CaptureIdentity.of(id.toString(), operationId) != null) { "A recording needs a valid operation identity" }
        return "${recordMedia(kind, id, context)}/op/$operationId"
    }

    fun parseRecordMedia(path: String): RecordPath? {
        if (!path.startsWith(MEDIA_PREFIX)) return null
        val parts = path.removePrefix(MEDIA_PREFIX).split('/')
        if (parts.size == 2) return parse(path, MEDIA_PREFIX)
        if (parts.size !in listOf(3, 5) || !LINK_PATH_SEGMENT.matches(parts[2])) return null
        if (parts.size == 5 && parts[3] != "op") return null
        val linkId = parts[2].replace('-', '+').replace('_', '/') + "="
        val hash = LinkWire.decodeKey(linkId) ?: return null
        if (hash.size != 32 || LinkWire.encodeKey(hash) != linkId) return null
        val record = parse("$MEDIA_PREFIX${parts[0]}/${parts[1]}", MEDIA_PREFIX) ?: return null
        val operation = parts.getOrNull(4)
        if (operation != null && CaptureIdentity.of(record.id.toString(), operation) == null) return null
        return record.copy(linkId = linkId, operationId = operation)
    }

    private fun parseWindow(
        path: String,
        prefix: String,
    ): RecordPath? {
        if (!path.startsWith(prefix)) return null
        val parts = path.removePrefix(prefix).split('/')
        if (parts.size == 2) return parse(path, prefix)
        if (parts.size != 3 || !LINK_PATH_SEGMENT.matches(parts[2])) return null
        val record = parse(prefix + parts.take(2).joinToString("/"), prefix) ?: return null
        val linkId = parts[2].replace('-', '+').replace('_', '/') + "="
        val bytes = LinkWire.decodeKey(linkId) ?: return null
        if (LinkWire.encodeKey(bytes) != linkId) return null
        return record.copy(linkId = linkId)
    }

    private fun parse(
        path: String,
        prefix: String,
    ): RecordPath? {
        if (!path.startsWith(prefix)) return null
        val parts = path.removePrefix(prefix).split('/')
        if (parts.size != 2) return null
        val kind = RecordKind.fromPathSegment(parts[0]) ?: return null
        val id = runCatching { Uuid.parse(parts[1]) }.getOrNull() ?: return null
        return RecordPath(kind, id)
    }
}

/** The kind and id a record path names. */
data class RecordPath(
    val kind: RecordKind,
    val id: Uuid,
    val linkId: String? = null,
    val operationId: String? = null,
)
