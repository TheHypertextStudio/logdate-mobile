@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionCopyAccess
import app.logdate.server.identity.adoption.IdentityAdoptionCopyCoverage
import app.logdate.server.identity.adoption.IdentityAdoptionGenericCopy
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMediaService
import app.logdate.server.identity.adoption.IdentityAdoptionMediaVerification
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceMapping
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class PostgreSQLIdentityAdoptionCopyAccess(
    private val source: DataSource,
    private val quota: suspend (Uuid) -> Long,
    private val clock: Clock = Clock.System,
    private val media: IdentityAdoptionMediaService? = null,
    override val privateMedia: app.logdate.server.identity.adoption.IdentityAdoptionPrivateMedia? = null,
) : IdentityAdoptionCopyAccess {
    override suspend fun issue(
        operation: IdentityAdoptionOperation,
        action: IdentityAdoptionAction,
        bodyDigest: String,
    ): IdentityAdoptionActionChallenge? {
        if (action !in
            setOf(
                IdentityAdoptionAction.STAGE_RECORDS,
                IdentityAdoptionAction.READ_STAGED,
                IdentityAdoptionAction.BEGIN_BLOB,
            )
        ) {
            return null
        }
        if (action == IdentityAdoptionAction.BEGIN_BLOB && media == null && privateMedia == null) return null
        return PostgreSQLIdentityAdoptionActions(source, clock).issue(operation, action, bodyDigest, clock.now())
    }

    override suspend fun verifyMedia(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        cid: String,
    ): IdentityAdoptionMediaVerification? = media?.verify(operation, challenge, signature, cid)

    override suspend fun put(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionGenericRequest,
    ): IdentityAdoptionGenericCopy? {
        val limit = quota(operation.binding.accountId)
        return PostgreSQLIdentityAdoptionGenericStaging(source, clock).put(operation, challenge, signature, request, limit, clock.now())
    }

    override suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionGenericRequest,
    ): IdentityAdoptionGenericCopy? =
        PostgreSQLIdentityAdoptionGenericStaging(source, clock).read(operation, challenge, signature, request, clock.now())

    override suspend fun putMapping(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionMappingRequest,
    ): IdentityAdoptionSourceMapping? {
        val limit = quota(operation.binding.accountId)
        return PostgreSQLIdentityAdoptionMappings(source, clock).put(operation, challenge, signature, request, limit, clock.now())
    }

    override suspend fun readMapping(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        reference: IdentityAdoptionSourceReference,
    ): IdentityAdoptionSourceMapping? =
        PostgreSQLIdentityAdoptionMappings(source, clock).read(
            operation,
            challenge,
            signature,
            reference,
            clock.now(),
        )

    override suspend fun coverage(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
    ): IdentityAdoptionCopyCoverage? {
        if (challenge.action != IdentityAdoptionAction.READ_STAGED || challenge.bodyDigest != COVERAGE_DIGEST) return null
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        val now = clock.now()
        return actions.consume(operation, challenge, signature, now) {
            val coverage = PostgreSQLIdentityAdoptionCoverage(source).readLocked(this, operation, now)
            stagingCheck(coverage != null, IdentityAdoptionStagingFailure.CONFLICT)
            val value = requireNotNull(coverage)
            val sequence = IdentityAdoptionManifestInventory(this, operation, value.source.generation).sourceSequence()
            stagingCheck(with(actions) { currentKey(operation, clock.now()) } != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            IdentityAdoptionCopyCoverage(value, sequence)
        }
    }

    private companion object {
        val COVERAGE_DIGEST =
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("purpose", "app.logdate.identity-adoption.coverage-read")
                    },
                ),
            )
    }
}
