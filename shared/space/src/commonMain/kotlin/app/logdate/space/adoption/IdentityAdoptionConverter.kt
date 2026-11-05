package app.logdate.space.adoption

import app.logdate.space.LosslessSealedRecordCodec
import app.logdate.space.SealedWrite
import app.logdate.space.SealingKeys
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonObject

/** Converts a complete wire record without discarding fields through a typed decoder. */
public object IdentityAdoptionConverter {
    public suspend fun reseal(
        keys: SealingKeys,
        source: SpaceRef,
        target: SpaceRef,
        recordKey: String,
        record: JsonObject,
    ): SealedWrite =
        IdentityAdoptionWire.protect {
            IdentityAdoptionReferencePolicy.validateScope(source, target)
            val envelope = IdentityAdoptionWire.envelope(record)
            val payload = LosslessSealedRecordCodec.open(keys, source, source.authority, recordKey, envelope)
            val converted = IdentityAdoptionReferencePolicy.convert(payload, source, target)
            LosslessSealedRecordCodec.seal(keys, target, target.authority, converted)
        }
}
