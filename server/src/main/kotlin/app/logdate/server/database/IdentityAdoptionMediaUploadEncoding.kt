package app.logdate.server.database

import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_MAX_BLOB_BYTES
import app.logdate.server.identity.adoption.IdentityAdoptionMediaUpload
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal fun mediaUploadDigest(upload: IdentityAdoptionMediaUpload): String {
    stagingCheck(Uuid.parseOrNull(upload.uploadId)?.toString() == upload.uploadId, IdentityAdoptionStagingFailure.INVALID_REQUEST)
    stagingCheck(
        upload.source.kind in
            setOf(
                IdentityAdoptionSourceKind.MEDIA,
                IdentityAdoptionSourceKind.SYNC_MEDIA,
                IdentityAdoptionSourceKind.ATPROTO_BLOB,
            ),
        IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
    )
    stagingCheck(
        upload.source.key.size == 1 && upload.source.key.all { it.encodeToByteArray().size in 1..4096 && '\u0000' !in it },
        IdentityAdoptionStagingFailure.INVALID_REQUEST,
    )
    stagingCheck(
        upload.sourceDigest.matches(Regex("[0-9a-f]{64}")) &&
            IdentityAdoptionVerifiedBlob.acceptsCid(upload.cid) &&
            upload.size in 0..IDENTITY_ADOPTION_MAX_BLOB_BYTES,
        IdentityAdoptionStagingFailure.INVALID_REQUEST,
    )
    return stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.media-begin")
                put("uploadId", upload.uploadId)
                put(
                    "source",
                    buildJsonObject {
                        put("kind", upload.source.kind.name)
                        put("key", JsonArray(upload.source.key.map(::JsonPrimitive)))
                    },
                )
                put("sourceDigest", upload.sourceDigest)
                put("cid", upload.cid)
                put("size", upload.size)
            },
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
internal fun mediaUploadActionDigest(
    uploadId: String,
    action: String,
): String {
    stagingCheck(Uuid.parseOrNull(uploadId)?.toString() == uploadId, IdentityAdoptionStagingFailure.INVALID_REQUEST)
    return stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.media-$action")
                put("uploadId", uploadId)
            },
        ),
    )
}

internal fun mediaSourceDigest(
    reference: IdentityAdoptionSourceReference,
    sourceDigest: String,
): String {
    stagingCheck(
        reference.kind in
            setOf(
                IdentityAdoptionSourceKind.SYNC_MEDIA,
                IdentityAdoptionSourceKind.MEDIA,
                IdentityAdoptionSourceKind.ATPROTO_BLOB,
            ),
        IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
    )
    stagingCheck(
        reference.key.size == 1 &&
            reference.key
                .single()
                .encodeToByteArray()
                .size in 1..4096 &&
            '\u0000' !in reference.key.single() &&
            sourceDigest.matches(Regex("[0-9a-f]{64}")),
        IdentityAdoptionStagingFailure.INVALID_REQUEST,
    )
    return stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.media-source")
                put(
                    "source",
                    buildJsonObject {
                        put("kind", reference.kind.name)
                        put("key", JsonArray(reference.key.map(::JsonPrimitive)))
                    },
                )
                put("sourceDigest", sourceDigest)
            },
        ),
    )
}
