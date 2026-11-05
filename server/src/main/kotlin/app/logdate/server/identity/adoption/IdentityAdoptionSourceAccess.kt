package app.logdate.server.identity.adoption

internal data class IdentityAdoptionSourceQuery(
    val kind: IdentityAdoptionSourceKind,
    val after: List<String>? = null,
    val limit: Int = 32,
) {
    override fun toString(): String = "IdentityAdoptionSourceQuery()"
}

internal interface IdentityAdoptionSourceAccess {
    suspend fun issue(
        operation: IdentityAdoptionOperation,
        query: IdentityAdoptionSourceQuery,
    ): IdentityAdoptionActionChallenge?

    suspend fun read(
        operation: IdentityAdoptionOperation,
        query: IdentityAdoptionSourceQuery,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
    ): IdentityAdoptionSourcePage?
}
