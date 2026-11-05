package app.logdate.server.identity.adoption

internal interface IdentityAdoptionLifecycle {
    suspend fun issue(
        operation: IdentityAdoptionOperation,
        action: IdentityAdoptionAction,
    ): IdentityAdoptionActionChallenge?

    suspend fun apply(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation>

    /** Only an unconfirmed proposal can be abandoned without its device signature. */
    suspend fun abandon(operation: IdentityAdoptionOperation): IdentityAdoptionOutcome<IdentityAdoptionOperation>
}

internal const val IDENTITY_ADOPTION_EMPTY_BODY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
