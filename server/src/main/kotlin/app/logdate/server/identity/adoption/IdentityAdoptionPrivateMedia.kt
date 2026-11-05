package app.logdate.server.identity.adoption

import app.logdate.server.logdate.LogDateBlobStream
import java.io.InputStream

internal data class IdentityAdoptionMediaUpload(
    val uploadId: String,
    val source: IdentityAdoptionSourceReference,
    val sourceDigest: String,
    val cid: String,
    val size: Long,
)

internal data class IdentityAdoptionPrivateMediaReceipt(
    val uploadId: String,
    val generation: Long,
    val cid: String,
    val size: Long,
    val state: String,
)

internal interface IdentityAdoptionPrivateMedia {
    val transfers: app.logdate.server.transfer.TransferSlots

    suspend fun readSource(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        reference: IdentityAdoptionSourceReference,
        sourceDigest: String,
    ): IdentityAdoptionMediaSourceStream?

    suspend fun begin(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        upload: IdentityAdoptionMediaUpload,
    ): IdentityAdoptionPrivateMediaReceipt?

    suspend fun write(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
        length: Long,
        input: InputStream,
    ): IdentityAdoptionPrivateMediaReceipt?

    suspend fun verify(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
    ): IdentityAdoptionMediaVerification?

    suspend fun discard(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
    ): IdentityAdoptionPrivateMediaReceipt?

    suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
    ): LogDateBlobStream?
}
