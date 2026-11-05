package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec

internal data class PreparedIdentityAdoptionMapping(
    val request: IdentityAdoptionMappingRequest,
    val key: ByteArray,
    val keyHash: String,
    val body: ByteArray,
    val bodyDigest: String,
) {
    val storageBytes: Long get() = key.size.toLong() + body.size
}

internal fun prepareIdentityAdoptionMapping(request: IdentityAdoptionMappingRequest): PreparedIdentityAdoptionMapping {
    val snapshot =
        request.copy(
            source = request.source.copy(key = request.source.key.toList()),
            targets = request.targets.toList(),
            media = request.media.toList(),
        )
    validateMapping(snapshot)
    val key = sourceMappingKey(snapshot.source)
    val body =
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.source-mapping")
                put(
                    "source",
                    buildJsonObject {
                        put("kind", snapshot.source.kind.name)
                        put("key", JsonArray(snapshot.source.key.map(::JsonPrimitive)))
                        put("digest", snapshot.sourceDigest)
                    },
                )
                put("disposition", snapshot.disposition.name)
                put(
                    "targets",
                    JsonArray(
                        snapshot.targets.map { target ->
                            buildJsonObject {
                                put("rkey", target.rkey)
                                put("digest", target.digest)
                            }
                        },
                    ),
                )
                put("media", JsonArray(snapshot.media.map(::JsonPrimitive)))
            },
        )
    stagingCheck(key.size <= 32768 && body.size <= 131072, IdentityAdoptionStagingFailure.INVALID_REQUEST)
    return PreparedIdentityAdoptionMapping(snapshot, key, stagingHash(key), body, stagingHash(body))
}

internal fun sourceMappingKey(source: IdentityAdoptionSourceReference): ByteArray =
    DagCborCodec.encode(JsonArray(source.key.map(::JsonPrimitive)))

private fun validateMapping(request: IdentityAdoptionMappingRequest) {
    val boundedKeys = request.source.key.all { part -> part.encodeToByteArray().size <= 4096 && '\u0000' !in part }
    stagingCheck(request.source.key.size in 1..4 && boundedKeys, IdentityAdoptionStagingFailure.INVALID_REQUEST)
    stagingCheck(request.sourceDigest.matches(Regex("[0-9a-f]{64}")), IdentityAdoptionStagingFailure.INVALID_REQUEST)
    stagingCheck(request.targets.size <= 64 && request.media.size <= 128, IdentityAdoptionStagingFailure.INVALID_REQUEST)
    val keys = request.targets.map { it.rkey }
    stagingCheck(keys == keys.distinct().sorted(), IdentityAdoptionStagingFailure.INVALID_REQUEST)
    for (target in request.targets) {
        val validKey = target.rkey.matches(Regex("[A-Za-z0-9._:~-]{1,512}")) && target.rkey !in setOf(".", "..")
        stagingCheck(validKey && target.digest.matches(Regex("[0-9a-f]{64}")), IdentityAdoptionStagingFailure.INVALID_REQUEST)
    }
    val boundedMedia = request.media.all { it.length in 1..255 && it.none(Char::isWhitespace) }
    stagingCheck(request.media == request.media.distinct().sorted() && boundedMedia, IdentityAdoptionStagingFailure.INVALID_REQUEST)
}

internal fun decodeIdentityAdoptionMapping(bytes: ByteArray): PreparedIdentityAdoptionMapping =
    try {
        val root = DagCborCodec.decode(bytes) as JsonObject
        val expectedKeys = setOf("version", "purpose", "source", "disposition", "targets", "media")
        stagingCheck(root.keys == expectedKeys, IdentityAdoptionStagingFailure.CONFLICT)

        fun text(value: JsonElement?): String {
            val primitive = value as? JsonPrimitive
            check(primitive != null && primitive.isString)
            return primitive.content
        }
        val expectedPurpose = "app.logdate.identity-adoption.source-mapping"
        stagingCheck(
            root["version"] == JsonPrimitive(1) && text(root["purpose"]) == expectedPurpose,
            IdentityAdoptionStagingFailure.CONFLICT,
        )
        val source = root.getValue("source") as JsonObject
        stagingCheck(source.keys == setOf("kind", "key", "digest"), IdentityAdoptionStagingFailure.CONFLICT)
        val reference =
            IdentityAdoptionSourceReference(
                IdentityAdoptionSourceKind.valueOf(text(source["kind"])),
                (source.getValue("key") as JsonArray).map(::text),
            )
        val targets =
            (root.getValue("targets") as JsonArray).map {
                val value = it as JsonObject
                stagingCheck(value.keys == setOf("rkey", "digest"), IdentityAdoptionStagingFailure.CONFLICT)
                IdentityAdoptionTargetReference(text(value["rkey"]), text(value["digest"]))
            }
        val request =
            IdentityAdoptionMappingRequest(
                reference,
                text(source["digest"]),
                IdentityAdoptionDisposition.valueOf(text(root["disposition"])),
                targets,
                (root.getValue("media") as JsonArray).map(::text),
            )
        prepareIdentityAdoptionMapping(request).also { stagingCheck(it.body.contentEquals(bytes), IdentityAdoptionStagingFailure.CONFLICT) }
    } catch (_: Exception) {
        throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.CONFLICT)
    }
