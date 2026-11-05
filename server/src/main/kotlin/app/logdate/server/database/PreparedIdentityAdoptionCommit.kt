package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionCommitRequest
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.util.Base64

internal class PreparedIdentityAdoptionCommit(
    val request: IdentityAdoptionCommitRequest,
    signature: String,
) {
    val bodyDigest: String
    val signatureDigest: String?

    init {
        stagingCheck(
            listOf(request.manifestDigest, request.coverageDigest).all { it.matches(Regex("[0-9a-f]{64}")) },
            IdentityAdoptionStagingFailure.INVALID_REQUEST,
        )
        bodyDigest = identityAdoptionCommitDigest(request)
        signatureDigest =
            if (signature.length == 86 && signature.matches(Regex("[A-Za-z0-9_-]{86}"))) {
                runCatching {
                    Base64
                        .getUrlDecoder()
                        .decode(signature)
                        .takeIf { it.size == 64 }
                        ?.let(::stagingHash)
                }.getOrNull()
            } else {
                null
            }
    }
}

internal fun identityAdoptionCommitDigest(request: IdentityAdoptionCommitRequest): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.commit")
                put("manifestDigest", request.manifestDigest)
                put("coverageDigest", request.coverageDigest)
            },
        ),
    )
