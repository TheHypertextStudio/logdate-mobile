package app.logdate.server.identity.adoption

internal data class IdentityAdoptionCopyCoverage(
    val coverage: IdentityAdoptionCoverage,
    val sourceSequence: Long,
) {
    override fun toString(): String = "IdentityAdoptionCopyCoverage()"
}

internal interface IdentityAdoptionCopyAccess {
    val privateMedia: IdentityAdoptionPrivateMedia? get() = null

    suspend fun issue(
        operation: IdentityAdoptionOperation,
        action: IdentityAdoptionAction,
        bodyDigest: String,
    ): IdentityAdoptionActionChallenge?

    suspend fun verifyMedia(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        cid: String,
    ): IdentityAdoptionMediaVerification?

    suspend fun put(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionGenericRequest,
    ): IdentityAdoptionGenericCopy?

    suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionGenericRequest,
    ): IdentityAdoptionGenericCopy?

    suspend fun putMapping(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionMappingRequest,
    ): IdentityAdoptionSourceMapping?

    suspend fun readMapping(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        reference: IdentityAdoptionSourceReference,
    ): IdentityAdoptionSourceMapping?

    suspend fun coverage(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
    ): IdentityAdoptionCopyCoverage?
}
