package app.logdate.server.identity.adoption

internal data class IdentityAdoptionCommitRequest(
    val manifestDigest: String,
    val coverageDigest: String,
)
