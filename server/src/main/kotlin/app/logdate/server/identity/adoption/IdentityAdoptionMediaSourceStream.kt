package app.logdate.server.identity.adoption

import app.logdate.server.logdate.LogDateBlobStream

internal data class IdentityAdoptionMediaSourceStream(
    val stream: LogDateBlobStream,
    val encoding: String,
    val sha256: String,
    val sourceDigest: String,
)
