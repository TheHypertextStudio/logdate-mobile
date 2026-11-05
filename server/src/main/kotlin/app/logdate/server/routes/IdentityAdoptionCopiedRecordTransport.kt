package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagedOrigin
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.shared.model.identity.IdentityAdoptionCopiedRecordView
import app.logdate.shared.model.identity.IdentityAdoptionCopyOriginView
import app.logdate.shared.model.identity.IdentityAdoptionCopyTombstoneView
import app.logdate.shared.model.identity.IdentityAdoptionLogicalRecordView
import app.logdate.shared.model.identity.IdentityAdoptionSealedCopyView
import app.logdate.shared.model.identity.IdentityAdoptionSourceKindView
import app.logdate.shared.model.identity.IdentityAdoptionSourceReferenceView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

internal fun IdentityAdoptionSourceReference.wire() =
    IdentityAdoptionSourceReferenceView(
        IdentityAdoptionSourceKindView.valueOf(kind.name),
        key,
    )

internal fun IdentityAdoptionStagedOrigin.wire() =
    IdentityAdoptionCopyOriginView(
        source.wire(),
        sourceDigest,
        IdentityAdoptionLogicalRecordView(sourceRecord.collection, sourceRecord.rkey),
        sourceVersion,
        sourceDeletedAt,
        targetRkey,
        targetSequence,
        targetDigest,
        bodyDigest,
        storageBytes,
    )

internal fun IdentityAdoptionStagedRecord.wire() =
    IdentityAdoptionCopiedRecordView(
        generation,
        rkey,
        sourceCollection,
        sourceRkey,
        sourceVersion,
        sourceDigest,
        targetDigest,
        cid,
        record?.sealedWire(),
        blobs,
        tombstone?.let {
            IdentityAdoptionCopyTombstoneView(
                it.getValue("rkey").jsonPrimitive.content,
                requireNotNull(it.getValue("seq").jsonPrimitive.longOrNull),
                it.getValue("deletedAt").jsonPrimitive.content,
            )
        },
        targetSequence ?: sourceVersion,
    )

private fun JsonObject.sealedWire(): IdentityAdoptionSealedCopyView {
    fun body(key: String): String {
        val bytes =
            Base64.getDecoder().decode(
                getValue(key)
                    .jsonObject
                    .getValue("$" + "bytes")
                    .jsonPrimitive.content,
            )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
    return IdentityAdoptionSealedCopyView(
        requireNotNull(getValue("version").jsonPrimitive.longOrNull),
        getValue("keyId").jsonPrimitive.content,
        body("nonce"),
        body("ciphertext"),
    )
}
