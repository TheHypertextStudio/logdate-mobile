package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.logdate.LogDateBlobWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import studio.hypertext.atproto.repo.Cid
import java.io.InputStream
import java.security.MessageDigest

internal suspend fun streamIdentityAdoptionMedia(
    writer: LogDateBlobWriter,
    input: InputStream,
    cid: String,
    length: Long,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    var size = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - size + 1).toInt())
        if (count < 0) break
        if (count == 0) continue
        size += count
        stagingCheck(size <= length, IdentityAdoptionStagingFailure.INVALID_REQUEST)
        digest.update(buffer, 0, count)
        writer.output.write(buffer, 0, count)
    }
    val actual = Cid.fromBytes(byteArrayOf(1, 0x55, 0x12, 0x20) + digest.digest()).value
    stagingCheck(size == length && actual == cid, IdentityAdoptionStagingFailure.INVALID_REQUEST)
}
