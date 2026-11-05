package app.logdate.server.identity.adoption

internal interface IdentityAdoptionFinalization {
    suspend fun issue(
        operation: IdentityAdoptionOperation,
        action: IdentityAdoptionAction,
        commitments: IdentityAdoptionCommitRequest,
    ): IdentityAdoptionActionChallenge?

    suspend fun verify(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionVerificationRequest,
    ): IdentityAdoptionVerifiedCopy?

    suspend fun commit(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionCommitRequest,
    ): IdentityAdoptionReceipt?
}
