package app.logdate.server.identity.adoption

import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.database.DatabaseConfig
import app.logdate.server.database.requireIdentityAdoptionSchema
import javax.sql.DataSource

internal fun createIdentityAdoptionRuntime(
    isDatabaseAvailable: Boolean,
    bluesky: BlueskyOAuthRuntime?,
    readEnv: (String) -> String? = System::getenv,
    sourceProvider: () -> DataSource? = { DatabaseConfig.initializedDataSource },
    checkSchema: (DataSource) -> Unit = ::requireIdentityAdoptionSchema,
    build: (DataSource, BlueskyOAuthRuntime) -> IdentityAdoptionRuntime,
): IdentityAdoptionRuntime? {
    if (readEnv("LOGDATE_IDENTITY_ADOPTION_ENABLED") != "true") return null
    require(isDatabaseAvailable) { "Identity adoption requires the application database" }
    require(readEnv("LOGDATE_ENCRYPTED_ACCOUNT_KEYS_ENABLED") == "true") { "Identity adoption requires encrypted account keys" }
    require(readEnv("LOGDATE_PERSONAL_SPACE_ENABLED") == "true") { "Identity adoption requires personal-space storage" }
    val oauth = requireNotNull(bluesky) { "Identity adoption requires protected Bluesky sign-in" }
    val source = requireNotNull(sourceProvider()) { "Identity adoption requires the initialized application database" }
    checkSchema(source)
    return build(source, oauth)
}
