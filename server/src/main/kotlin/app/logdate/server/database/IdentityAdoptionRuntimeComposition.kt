@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.bluesky.BlueskySignInAttemptStore
import app.logdate.server.bluesky.BlueskySignInProvider
import app.logdate.server.crypto.EncryptionService
import app.logdate.server.identity.adoption.IdentityAdoptionAuthorizations
import app.logdate.server.identity.adoption.IdentityAdoptionConfirmationService
import app.logdate.server.identity.adoption.IdentityAdoptionMaintenance
import app.logdate.server.identity.adoption.IdentityAdoptionMediaService
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthService
import app.logdate.server.identity.adoption.IdentityAdoptionPasskeys
import app.logdate.server.identity.adoption.IdentityAdoptionProofs
import app.logdate.server.identity.adoption.IdentityAdoptionRuntime
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.transfer.TransferSlots
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal fun composeIdentityAdoptionRuntime(
    source: DataSource,
    provider: BlueskySignInProvider,
    attempts: BlueskySignInAttemptStore,
    webAuthn: WebAuthnPasskeyService,
    origin: String,
    storage: LogDateBlobStorage,
    quota: suspend (Uuid) -> Long,
    encryption: EncryptionService,
    transfers: TransferSlots,
    clock: Clock = Clock.System,
): IdentityAdoptionRuntime {
    val repository = PostgreSQLIdentityAdoptionRepository(source, clock)
    val decisions = PostgreSQLIdentityAdoptionDecisions(source)
    val proofs = IdentityAdoptionProofs(decisions, IdentityAdoptionPasskeys(webAuthn), clock)
    val authorizations = IdentityAdoptionAuthorizations(proofs, PostgreSQLIdentityAdoptionAuthorizations(source), clock)
    val cleanup = PostgreSQLIdentityAdoptionMediaCleanup(source, storage, clock)
    val stagingCleanup = PostgreSQLIdentityAdoptionStagingCleanup(source, clock)
    val media = PostgreSQLIdentityAdoptionPrivateMedia(source, storage, quota, clock, encryption, transfers)
    return IdentityAdoptionRuntime(
        repository,
        IdentityAdoptionOAuthService(provider, attempts, repository, PostgreSQLIdentityAdoptionBrowserCancellation(source), clock),
        IdentityAdoptionConfirmationService(proofs, decisions, authorizations, origin, clock),
        PostgreSQLIdentityAdoptionLifecycle(source, clock),
        PostgreSQLIdentityAdoptionFinalization(source, quota, clock),
        PostgreSQLIdentityAdoptionSourceAccess(source, clock),
        PostgreSQLIdentityAdoptionCopyAccess(
            source,
            quota,
            clock,
            IdentityAdoptionMediaService(PostgreSQLIdentityAdoptionBlobs(source, clock), storage, clock),
            media,
        ),
        IdentityAdoptionMaintenance(cleanup::pending, cleanup::cleanup),
        IdentityAdoptionMaintenance(stagingCleanup::pending, stagingCleanup::cleanup),
    )
}
