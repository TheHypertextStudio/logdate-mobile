package app.logdate.space

import app.logdate.lexicon.app.logdate.crypto.Sealed
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.coroutines.cancellation.CancellationException

/** A complete sealed payload, including fields unknown to this client. */
public class LosslessSealedPayload(
    value: JsonObject,
) {
    public val value: JsonObject = JsonObject(value.toMap())

    init {
        if (listOf("collection", "rkey").any { (value[it] as? JsonPrimitive)?.isString != true } || "record" !in value) {
            throw SealedRecordException("The sealed payload is unsupported")
        }
    }

    public val collection: String get() = value.getValue("collection").jsonPrimitive.content
    public val rkey: String get() = value.getValue("rkey").jsonPrimitive.content
    public val record: JsonElement get() = value.getValue("record")
}

/** Lossless payload entrypoints for authority conversion and retained records. */
public object LosslessSealedRecordCodec {
    public suspend fun seal(
        keys: SealingKeys,
        space: SpaceRef,
        author: String,
        payload: LosslessSealedPayload,
    ): SealedWrite =
        protect {
            SealedPayloadSizing.check(payload.value)
            val encoded = DagCborCodec.encode(payload.value)
            SealedPayloadCbor.validate(encoded)
            val rkey = SealedRecordCodec.sealedRkey(keys, payload.collection, payload.rkey)
            SealedWrite(rkey, SealedRecordCodec.encrypt(keys, space, author, rkey, SealedPadding.pad(encoded)))
        }

    public suspend fun open(
        keys: SealingKeys,
        space: SpaceRef,
        author: String,
        sealedRkey: String,
        record: Sealed,
    ): LosslessSealedPayload =
        protect {
            val bytes = SealedPadding.unpad(SealedRecordCodec.decryptPadded(keys, space, author, sealedRkey, record))
            SealedPayloadCbor.validate(bytes)
            val value = DagCborCodec.decode(bytes) as? JsonObject ?: throw SealedRecordException("The sealed payload is unsupported")
            val payload = LosslessSealedPayload(value)
            if (SealedRecordCodec.sealedRkey(keys, payload.collection, payload.rkey) != sealedRkey) {
                throw SealedRecordException("The sealed key doesn't match the record inside")
            }
            payload
        }

    private suspend fun <T> protect(action: suspend () -> T): T =
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SealedRecordException) {
            throw error
        } catch (_: Exception) {
            throw SealedRecordException("The sealed payload is unsupported")
        }
}
