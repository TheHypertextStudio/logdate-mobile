package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionGenericCopy
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceRecordKey
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant

internal class PostgreSQLIdentityAdoptionGenericStaging(
    private val source: DataSource,
    private val clock: Clock? = null,
) {
    suspend fun put(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionGenericRequest,
        limit: Long,
        now: Instant,
    ): IdentityAdoptionGenericCopy? {
        val prepared = prepareIdentityAdoptionGeneric(request)
        stagingCheck(limit >= 0, IdentityAdoptionStagingFailure.INVALID_REQUEST)
        if (challenge.action != IdentityAdoptionAction.STAGE_RECORDS || challenge.bodyDigest != prepared.digest) return null
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        return actions.consume(operation, challenge, signature, now) {
            val profile = identityAdoptionGenericSource(source, operation, prepared.request, now)
            val result = IdentityAdoptionGenericWriter(this, operation, generation(operation), prepared, profile, limit, now).put()
            stagingCheck(
                with(actions) { currentKey(operation, clock?.now() ?: now) } != null,
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            result
        }
    }

    suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        request: IdentityAdoptionGenericRequest,
        now: Instant,
    ): IdentityAdoptionGenericCopy? {
        val prepared = prepareIdentityAdoptionGeneric(request)
        if (challenge.action != IdentityAdoptionAction.READ_STAGED || challenge.bodyDigest != prepared.readDigest) return null
        val actions = PostgreSQLIdentityAdoptionActions(source, clock)
        return actions.consume(operation, challenge, signature, now) {
            val rows = IdentityAdoptionGenericRows(this, operation, generation(operation))
            val origin =
                rows.origin(prepared.request.source, genericLogicalKey(prepared.request), prepared.request.rkey) ?: return@consume null
            if (origin.targetRkey != request.rkey) return@consume null
            val record = rows.target(origin.targetRkey)
            stagingCheck(record != null && record.targetDigest == origin.targetDigest, IdentityAdoptionStagingFailure.CONFLICT)
            val candidate = requireNotNull(record)
            val sequence = candidate.targetSequence ?: candidate.sourceVersion
            stagingCheck(sequence == origin.targetSequence, IdentityAdoptionStagingFailure.CONFLICT)
            stagingCheck(
                with(actions) { currentKey(operation, clock?.now() ?: now) } != null,
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            IdentityAdoptionGenericCopy(candidate, origin)
        }
    }

    private fun Connection.generation(operation: IdentityAdoptionOperation): Long {
        if (operation.phase == IdentityAdoptionPhase.PREPARING) return operation.revision
        return prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id = ?").use {
            it.setString(1, operation.id)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1).also { value -> check(value > 0) }
            }
        }
    }
}

internal fun genericLogicalKey(request: IdentityAdoptionGenericRequest): IdentityAdoptionSourceRecordKey =
    when (request.source.kind) {
        IdentityAdoptionSourceKind.REPO_BLOCK -> requireNotNull(request.repositoryRecord)
        IdentityAdoptionSourceKind.SPACE_RECORD -> IdentityAdoptionSourceRecordKey("app.logdate.crypto.sealed", request.source.key[2])
        else ->
            IdentityAdoptionSourceRecordKey(
                "app.logdate.adoption." +
                    request.source.kind.name
                        .lowercase()
                        .replace("_", ""),
                stagingHash(
                    DagCborCodec.encode(
                        buildJsonObject {
                            put("version", 1)
                            put("purpose", "app.logdate.identity-adoption.source-component")
                            put("kind", request.source.kind.name)
                            put("key", JsonArray(request.source.key.map(::JsonPrimitive)))
                            put("targetRkey", request.rkey)
                        },
                    ),
                ),
            )
    }

internal fun sameGenericTarget(
    record: IdentityAdoptionStagedRecord,
    prepared: PreparedIdentityAdoptionGeneric,
    profile: IdentityAdoptionGenericSource,
): Boolean {
    if (record.record != prepared.request.record || record.blobs != prepared.request.blobs) return false
    if (profile.deletedAt == null) return true
    return record.tombstone?.get("deletedAt") == JsonPrimitive(profile.deletedAt)
}
