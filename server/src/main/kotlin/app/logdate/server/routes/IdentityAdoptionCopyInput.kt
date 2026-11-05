package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceRecordKey
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingException
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.shared.model.identity.IdentityAdoptionCopyMappingView
import app.logdate.shared.model.identity.IdentityAdoptionCopyReadRequest
import app.logdate.shared.model.identity.IdentityAdoptionCopyRecordView
import app.logdate.shared.model.identity.IdentityAdoptionLogicalRecordView
import app.logdate.shared.model.identity.IdentityAdoptionSealedCopyView
import app.logdate.shared.model.identity.IdentityAdoptionSourceReferenceView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

internal fun IdentityAdoptionSourceReferenceView.domain() =
    IdentityAdoptionSourceReference(
        IdentityAdoptionSourceKind.valueOf(kind.name),
        key.toList(),
    )

internal fun IdentityAdoptionLogicalRecordView.domain() = IdentityAdoptionSourceRecordKey(collection, rkey)

internal fun IdentityAdoptionCopyRecordView.domain() =
    IdentityAdoptionGenericRequest(
        source.domain(),
        sourceDigest,
        rkey,
        record?.domain(),
        blobs.toList(),
        repositoryRecord?.domain(),
    )

internal fun IdentityAdoptionCopyReadRequest.domain() =
    IdentityAdoptionGenericRequest(
        source.domain(),
        sourceDigest,
        rkey,
        null,
        emptyList(),
        repositoryRecord?.domain(),
    )

internal fun IdentityAdoptionCopyMappingView.domain() =
    IdentityAdoptionMappingRequest(
        source.domain(),
        sourceDigest,
        IdentityAdoptionDisposition.valueOf(disposition.name),
        targets.map { IdentityAdoptionTargetReference(it.rkey, it.digest) },
        media.toList(),
    )

/** Fixed envelope fields avoid running a general recursive CBOR decoder on request bodies. */
private fun IdentityAdoptionSealedCopyView.domain(): JsonObject {
    val nonceBytes = copyInputBytes(nonce, 12)
    val cipherBytes = copyInputBytes(ciphertext, 4_000_000)
    if (nonceBytes.size != 12) throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.INVALID_REQUEST)
    return buildJsonObject {
        put("$" + "type", "app.logdate.crypto.sealed")
        put("version", version)
        put("keyId", keyId)
        putJsonObject("nonce") { put("$" + "bytes", Base64.getEncoder().encodeToString(nonceBytes)) }
        putJsonObject("ciphertext") { put("$" + "bytes", Base64.getEncoder().encodeToString(cipherBytes)) }
    }
}

private fun copyInputBytes(
    text: String,
    maximum: Int,
): ByteArray {
    if (text.length > (maximum * 8 + 5) / 6 || !text.matches(Regex("[A-Za-z0-9_-]+"))) {
        throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.INVALID_REQUEST)
    }
    val bytes =
        try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.INVALID_REQUEST)
        }
    if (bytes.size > maximum || Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != text) {
        throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.INVALID_REQUEST)
    }
    return bytes
}
