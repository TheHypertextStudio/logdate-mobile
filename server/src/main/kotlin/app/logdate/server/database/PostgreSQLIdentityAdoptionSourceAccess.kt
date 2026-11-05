package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceAccess
import app.logdate.server.identity.adoption.IdentityAdoptionSourcePage
import app.logdate.server.identity.adoption.IdentityAdoptionSourceQuery
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import javax.sql.DataSource
import kotlin.time.Clock

internal class PostgreSQLIdentityAdoptionSourceAccess(
    private val source: DataSource,
    private val clock: Clock = Clock.System,
) : IdentityAdoptionSourceAccess {
    override suspend fun issue(
        operation: IdentityAdoptionOperation,
        query: IdentityAdoptionSourceQuery,
    ): IdentityAdoptionActionChallenge? {
        val prepared = PreparedIdentityAdoptionSourceQuery(query)
        return PostgreSQLIdentityAdoptionActions(
            source,
            clock,
        ).issue(operation, IdentityAdoptionAction.READ_STAGED, prepared.digest, clock.now())
    }

    override suspend fun read(
        operation: IdentityAdoptionOperation,
        query: IdentityAdoptionSourceQuery,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
    ): IdentityAdoptionSourcePage? {
        val prepared = PreparedIdentityAdoptionSourceQuery(query)
        if (challenge.action != IdentityAdoptionAction.READ_STAGED || challenge.bodyDigest != prepared.digest) return null
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        val now = clock.now()
        return actions.consume(operation, challenge, signature, now) {
            val page =
                PostgreSQLIdentityAdoptionSourceInventory(source).pageLocked(
                    this,
                    operation,
                    prepared.query.kind,
                    prepared.query.after,
                    prepared.query.limit,
                    now,
                )
            stagingCheck(page != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            stagingCheck(with(actions) { currentKey(operation, clock.now()) } != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            requireNotNull(page)
        }
    }
}
