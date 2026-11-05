package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionStagingRequest
import app.logdate.server.space.checkSealedRecord
import app.logdate.server.space.decodeRecord
import app.logdate.server.space.recordCid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.security.MessageDigest
import java.util.HexFormat

/** Detached bytes are captured before suspension; a mutable caller object cannot rebind a signed request. */
internal data class PreparedIdentityAdoptionRecord(
    val rkey: String,
    val sourceVersion: Long,
    val sourceDigest: String,
    val targetBytes: ByteArray?,
    val targetCid: String?,
    val blobCids: List<String>,
    val blobBytes: ByteArray,
    val bodyDigest: String,
)

internal fun prepareIdentityAdoptionRecord(request: IdentityAdoptionStagingRequest): PreparedIdentityAdoptionRecord {
    stagingCheck(
        request.rkey.matches(Regex("[A-Za-z0-9._:~-]{1,512}")) &&
            request.rkey !in setOf(".", "..") &&
            request.sourceVersion >= 0 &&
            request.sourceDigest.matches(Regex("[0-9a-f]{64}")),
        IdentityAdoptionStagingFailure.INVALID_REQUEST,
    )
    val blobs = request.blobs.toList()
    stagingCheck(
        blobs == blobs.distinct().sorted() && blobs.all { it.length in 1..255 && it.none(Char::isWhitespace) },
        IdentityAdoptionStagingFailure.INVALID_REQUEST,
    )
    val record =
        request.record?.let {
            stagingEnvelope(it, IdentityAdoptionStagingFailure.INVALID_REQUEST)
            recordCid(it)
        }
    val target = record?.second?.let(::decodeRecord)
    val blobValue = JsonArray(blobs.map(::JsonPrimitive))
    val body =
        buildJsonObject {
            put("version", 1)
            put("rkey", request.rkey)
            put("sourceVersion", request.sourceVersion)
            put("sourceDigest", request.sourceDigest)
            put("record", target ?: JsonNull)
            put("blobs", blobValue)
        }
    return PreparedIdentityAdoptionRecord(
        request.rkey,
        request.sourceVersion,
        request.sourceDigest,
        record?.second,
        record?.first,
        blobs,
        DagCborCodec.encode(blobValue),
        stagingHash(DagCborCodec.encode(body)),
    )
}

internal fun stagingEnvelope(
    record: JsonObject,
    failure: IdentityAdoptionStagingFailure,
) {
    val version = record["version"] as? JsonPrimitive
    stagingCheck(
        record.keys == setOf("\$type", "version", "keyId", "nonce", "ciphertext") &&
            version?.isString == false &&
            version?.longOrNull == 1L &&
            checkSealedRecord(record) == null,
        failure,
    )
}

internal fun stagingHash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun stagingCheck(
    valid: Boolean,
    failure: IdentityAdoptionStagingFailure,
) {
    if (!valid) throw IdentityAdoptionStagingException(failure)
}
