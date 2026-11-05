package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.space.decodeRecord
import app.logdate.server.space.recordCid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec

internal data class PreparedIdentityAdoptionGeneric(
    val request: IdentityAdoptionGenericRequest,
    val targetBytes: ByteArray?,
    val blobBytes: ByteArray,
    val digest: String,
    val readDigest: String,
)

internal fun prepareIdentityAdoptionGeneric(request: IdentityAdoptionGenericRequest): PreparedIdentityAdoptionGeneric {
    val frozen = request.copy(source = request.source.copy(key = request.source.key.toList()), blobs = request.blobs.toList())
    val bounded = frozen.source.key.size in 1..4 && frozen.source.key.all { it.encodeToByteArray().size <= 4096 && '\u0000' !in it }
    stagingCheck(bounded && frozen.sourceDigest.matches(Regex("[0-9a-f]{64}")), IdentityAdoptionStagingFailure.INVALID_REQUEST)
    val key = frozen.rkey
    stagingCheck(key.matches(Regex("[A-Za-z0-9._:~-]{1,512}")) && key !in setOf(".", ".."), IdentityAdoptionStagingFailure.INVALID_REQUEST)
    val validBlobs = frozen.blobs.size <= 128 && frozen.blobs.all { it.length in 1..255 && it.none(Char::isWhitespace) }
    stagingCheck(validBlobs && frozen.blobs == frozen.blobs.distinct().sorted(), IdentityAdoptionStagingFailure.INVALID_REQUEST)
    val bytes =
        frozen.record?.let {
            stagingEnvelope(it, IdentityAdoptionStagingFailure.INVALID_REQUEST)
            recordCid(it).second
        }
    val snapshot = frozen.copy(record = bytes?.let(::decodeRecord))
    val blobs = DagCborCodec.encode(JsonArray(snapshot.blobs.map(::JsonPrimitive)))
    return PreparedIdentityAdoptionGeneric(snapshot, bytes, blobs, genericBody(snapshot, false), genericBody(snapshot, true))
}

private fun genericBody(
    request: IdentityAdoptionGenericRequest,
    read: Boolean,
): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", if (read) "app.logdate.identity-adoption.generic-read" else "app.logdate.identity-adoption.generic-copy")
                put(
                    "source",
                    buildJsonObject {
                        put("kind", request.source.kind.name)
                        put("key", JsonArray(request.source.key.map(::JsonPrimitive)))
                        put(
                            "repositoryRecord",
                            request.repositoryRecord?.let { record ->
                                buildJsonObject {
                                    put("collection", record.collection)
                                    put("rkey", record.rkey)
                                }
                            } ?: JsonNull,
                        )
                    },
                )
                put("rkey", request.rkey)
                if (!read) {
                    put("sourceDigest", request.sourceDigest)
                    put("record", request.record ?: JsonNull)
                    put("blobs", JsonArray(request.blobs.map(::JsonPrimitive)))
                }
            },
        ),
    )
