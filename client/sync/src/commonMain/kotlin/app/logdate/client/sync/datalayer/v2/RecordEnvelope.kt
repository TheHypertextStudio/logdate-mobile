package app.logdate.client.sync.datalayer.v2

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The kinds of record version 2 moves between devices. */
@Serializable
enum class RecordKind(
    val pathSegment: String,
) {
    NOTE("note"),
    JOURNAL("journal"),
    ASSOCIATION("association"),
    MOMENT("moment"),
    ;

    companion object {
        fun fromPathSegment(segment: String): RecordKind? = entries.firstOrNull { it.pathSegment == segment }
    }
}

/** The device a record was made on. */
@Serializable
enum class RecordOrigin {
    PHONE,
    WATCH,
}

/** Why a record is in the watch's library window. */
@Serializable
enum class WindowReason {
    RECENT,
    ON_THIS_DAY,
    REWIND,
    STARRED,
}

/** How soon a record should cross: URGENT for what someone is waiting on, BULK for backfill. */
@Serializable
enum class DeliveryPriority {
    URGENT,
    NORMAL,
    BULK,
}

/** A body too large for a data item, sent over [WearPaths.bodyChannel]. */
@Serializable
data class BodyRef(
    val sha256: String,
    val sizeBytes: Long,
)

/** A media file that goes with a record and is fetched on its own. */
@Serializable
data class MediaRef(
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
)

/**
 * One record on the Data Layer.
 *
 * @property contentVersion The record's `lastUpdated` in epoch milliseconds; receipts name it, so a
 *   receipt for an older version never clears a newer edit.
 * @property syncVersion The cloud server's version of the record, or 0 when it has none yet.
 * @property enc Reserved for end-to-end encrypted envelopes. Schema 1 never sets it, and a schema 1
 *   reader refuses an envelope that does.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RecordEnvelope(
    val schema: Int = WearProtocol.ENVELOPE_SCHEMA,
    val kind: RecordKind,
    val id: String,
    val contentVersion: Long,
    val syncVersion: Long = 0,
    val origin: RecordOrigin,
    val reasons: Set<WindowReason> = emptySet(),
    val priority: DeliveryPriority = DeliveryPriority.NORMAL,
    val enc: String? = null,
    val body: JsonElement? = null,
    val bodyRef: BodyRef? = null,
    val media: List<MediaRef> = emptyList(),
    val context: PeerDeliveryContext? = null,
    val operationId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val removed: Boolean = false,
)

/** Reads and writes [RecordEnvelope]s, enforcing the inline body limit. */
object RecordEnvelopeCodec {
    /** Throws [IllegalArgumentException] for an envelope with two bodies or an inline body over the limit. */
    fun encode(envelope: RecordEnvelope): String {
        require(envelope.body == null || envelope.bodyRef == null) { "An envelope has its body inline or by reference, not both" }
        val body = envelope.body
        require(body == null || !needsChannel(body)) { "A body over ${WearProtocol.MAX_INLINE_BODY_BYTES} bytes goes over a channel" }
        return WearJson.encode(RecordEnvelope.serializer(), envelope)
    }

    fun decode(text: String): Decoded<RecordEnvelope> {
        val decoded = WearJson.decode(text, WearProtocol.ENVELOPE_SCHEMA, RecordEnvelope.serializer())
        if (decoded !is Decoded.Supported) return decoded
        val envelope = decoded.value
        if (envelope.enc != null) {
            return Decoded.Unsupported(envelope.schema, "Encrypted envelopes need a newer version")
        }
        if (envelope.body != null && envelope.bodyRef != null) {
            return Decoded.Malformed("RecordEnvelope has both an inline body and a body reference")
        }
        return decoded
    }

    fun needsChannel(body: JsonElement): Boolean =
        WearJson.encode(JsonElement.serializer(), body).encodeToByteArray().size > WearProtocol.MAX_INLINE_BODY_BYTES
}
