package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceRecordKey
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagedOrigin
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec

internal data class PreparedIdentityAdoptionOrigin(
    val value: IdentityAdoptionStagedOrigin,
    val bytes: ByteArray,
)

internal fun prepareIdentityAdoptionOrigin(origin: IdentityAdoptionStagedOrigin): PreparedIdentityAdoptionOrigin {
    val bytes =
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.copy-origin")
                put(
                    "source",
                    buildJsonObject {
                        put("kind", origin.source.kind.name)
                        put("key", JsonArray(origin.source.key.map(::JsonPrimitive)))
                    },
                )
                put("sourceDigest", origin.sourceDigest)
                put("sourceCollection", origin.sourceRecord.collection)
                put("sourceRkey", origin.sourceRecord.rkey)
                put("sourceVersion", origin.sourceVersion)
                put("sourceDeletedAt", origin.sourceDeletedAt?.let(::JsonPrimitive) ?: JsonNull)
                put("targetRkey", origin.targetRkey)
                put("targetSequence", origin.targetSequence)
                put("targetDigest", origin.targetDigest)
            },
        )
    stagingCheck(bytes.size <= 65536 && origin.sourceVersion >= 0 && origin.targetSequence > 0, IdentityAdoptionStagingFailure.CONFLICT)
    return PreparedIdentityAdoptionOrigin(origin.copy(bodyDigest = stagingHash(bytes), storageBytes = bytes.size.toLong()), bytes)
}

internal fun decodeIdentityAdoptionOrigin(bytes: ByteArray): PreparedIdentityAdoptionOrigin =
    try {
        val value = DagCborCodec.decode(bytes) as JsonObject

        fun text(element: JsonElement?): String {
            val primitive = element as? JsonPrimitive
            check(primitive != null && primitive.isString)
            return primitive.content
        }

        fun number(key: String): Long {
            val primitive = value[key] as JsonPrimitive
            check(!primitive.isString)
            return requireNotNull(primitive.longOrNull)
        }
        val source = value.getValue("source") as JsonObject
        val origin =
            IdentityAdoptionStagedOrigin(
                IdentityAdoptionSourceReference(
                    IdentityAdoptionSourceKind.valueOf(text(source["kind"])),
                    (source.getValue("key") as JsonArray).map(::text),
                ),
                text(value["sourceDigest"]),
                IdentityAdoptionSourceRecordKey(text(value["sourceCollection"]), text(value["sourceRkey"])),
                number("sourceVersion"),
                value["sourceDeletedAt"].takeUnless { it == JsonNull }?.let(::text),
                text(value["targetRkey"]),
                number("targetSequence"),
                text(value["targetDigest"]),
                "",
                0,
            )
        prepareIdentityAdoptionOrigin(origin).also {
            stagingCheck(it.bytes.contentEquals(bytes), IdentityAdoptionStagingFailure.CONFLICT)
        }
    } catch (_: Exception) {
        throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.CONFLICT)
    }
