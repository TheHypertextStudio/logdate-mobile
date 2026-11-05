package app.logdate.space.adoption

import app.logdate.space.LosslessSealedPayload
import app.logdate.space.SpaceRef
import app.logdate.space.SpaceTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Rewrites schema-defined identity fields only, retaining the complete original payload. */
public object IdentityAdoptionReferencePolicy {
    public fun convert(
        payload: LosslessSealedPayload,
        source: SpaceRef,
        target: SpaceRef,
    ): LosslessSealedPayload {
        validateScope(source, target)
        val field =
            when (payload.collection) {
                "app.logdate.people.person" -> "connection"
                "app.logdate.graph.connection", "app.logdate.graph.circleMember",
                "app.logdate.graph.block", "app.logdate.graph.mute",
                -> "subject"
                "app.logdate.share.item" -> "author"
                "app.logdate.crypto.sealed", "app.logdate.crypto.keyDeclaration", "app.logdate.crypto.wrappedKey",
                "app.logdate.space.member", "app.logdate.space.groupProfile",
                -> unsupported()
                else -> null
            }
        if (field == null && payload.collection != "app.logdate.share.share") return payload
        val fields = (payload.record as? JsonObject)?.toMutableMap() ?: invalid()
        if (field != null) {
            val optional = payload.collection == "app.logdate.people.person"
            if (!(optional && (fields[field] == null || fields[field] == JsonNull))) {
                fields[field] = identity(fields[field], source, target)
            }
        }
        if (payload.collection == "app.logdate.share.share") {
            val audience = fields["audience"] as? JsonArray ?: invalid()
            fields["audience"] = JsonArray(audience.map { convertAudience(it, source, target) })
        }
        return LosslessSealedPayload(JsonObject(payload.value + ("record" to JsonObject(fields))))
    }

    internal fun validateScope(
        source: SpaceRef,
        target: SpaceRef,
    ) {
        if (source.authority == target.authority ||
            listOf(source, target).any {
                SpaceRef.parse(it.uri) != it || it.type != SpaceTypes.PERSONAL || it.skey != "self"
            }
        ) {
            throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_BINDING)
        }
    }

    private fun identity(
        value: JsonElement?,
        source: SpaceRef,
        target: SpaceRef,
    ): JsonPrimitive {
        val did = string(value) ?: invalid()
        if (SpaceRef.parse(SpaceRef.personal(did).uri) == null) invalid()
        return JsonPrimitive(if (did == source.authority) target.authority else did)
    }

    private fun convertAudience(
        value: JsonElement,
        source: SpaceRef,
        target: SpaceRef,
    ): JsonObject {
        val fields = (value as? JsonObject)?.toMutableMap() ?: invalid()
        when (string(fields["\$type"])) {
            "app.logdate.share.share#personAudience" -> fields["subject"] = identity(fields["subject"], source, target)
            "app.logdate.share.share#circleAudience" -> Unit
            "app.logdate.share.share#groupAudience" -> {
                val uri = string(fields["space"]) ?: invalid()
                val space = SpaceRef.parse(uri) ?: invalid()
                if (space.type != "app.logdate.space.group" || space.authority == source.authority) unsupported()
            }
            else -> unsupported()
        }
        return JsonObject(fields)
    }

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun invalid(): Nothing = throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)

    private fun unsupported(): Nothing = throw IdentityAdoptionException(IdentityAdoptionFailure.UNSUPPORTED_RECORD)
}
