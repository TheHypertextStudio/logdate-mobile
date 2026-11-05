@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionRepositoryRecord
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagedOrigin
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Instant

internal data class IdentityAdoptionOriginCoverage(
    val count: Long,
    val digest: String,
)

internal class IdentityAdoptionCoverageOrigins(
    private val source: DataSource,
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
    private val generation: Long,
    private val now: Instant,
) {
    private val seen = mutableSetOf<String>()
    private val repositoryRecords = mutableSetOf<IdentityAdoptionRepositoryRecord>()
    private val hash =
        MessageDigest.getInstance("SHA-256").apply {
            update("app.logdate.identity-adoption.copy-origins|1\u0000".encodeToByteArray())
        }

    fun require(
        request: IdentityAdoptionMappingRequest,
        targets: List<IdentityAdoptionStagedRecord>,
    ) {
        when (request.disposition) {
            IdentityAdoptionDisposition.CONVERTED -> records(request.source, request.sourceDigest, targets)
            IdentityAdoptionDisposition.REFERENCES -> {
                val key = request.source.key
                stagingCheck(
                    request.source.kind == IdentityAdoptionSourceKind.SPACE_REFERENCE && key.size == 4,
                    IdentityAdoptionStagingFailure.CONFLICT,
                )
                stagingCheck(request.media == listOf(key[3]), IdentityAdoptionStagingFailure.CONFLICT)
                records(IdentityAdoptionSourceReference(IdentityAdoptionSourceKind.SPACE_RECORD, key.take(3)), null, targets)
            }
            else -> Unit
        }
    }

    fun finish(expectedRepositoryRecords: List<IdentityAdoptionRepositoryRecord>): IdentityAdoptionOriginCoverage {
        stagingCheck(repositoryRecords == expectedRepositoryRecords.toSet(), IdentityAdoptionStagingFailure.CONFLICT)
        val rows = IdentityAdoptionCoverageRows(connection, operation, generation)
        stagingCheck(
            rows.count("identity_adoption_staged_origins", "copy_generation") == seen.size.toLong(),
            IdentityAdoptionStagingFailure.CONFLICT,
        )
        return IdentityAdoptionOriginCoverage(seen.size.toLong(), HexFormat.of().formatHex(hash.digest()))
    }

    private fun records(
        reference: IdentityAdoptionSourceReference,
        expectedDigest: String?,
        targets: List<IdentityAdoptionStagedRecord>,
    ) {
        for (target in targets) {
            val origins = origins(reference, target.rkey)
            if (origins.isEmpty()) {
                stagingCheck(
                    reference.kind == IdentityAdoptionSourceKind.SPACE_RECORD && target.targetSequence == null,
                    IdentityAdoptionStagingFailure.CONFLICT,
                )
                connection.requireIdentityAdoptionPersonalProvenance(operation, reference, listOf(target))
            } else {
                for (origin in origins) validate(reference, expectedDigest, target, origin)
            }
        }
    }

    private fun validate(
        reference: IdentityAdoptionSourceReference,
        expectedDigest: String?,
        target: IdentityAdoptionStagedRecord,
        origin: IdentityAdoptionStagedOrigin,
    ) {
        val sequence = target.targetSequence ?: target.sourceVersion
        stagingCheck(
            origin.source == reference &&
                origin.targetRkey == target.rkey &&
                origin.targetSequence == sequence &&
                origin.targetDigest == target.targetDigest &&
                (expectedDigest == null || expectedDigest == origin.sourceDigest),
            IdentityAdoptionStagingFailure.CONFLICT,
        )
        val repository = if (reference.kind == IdentityAdoptionSourceKind.REPO_BLOCK) origin.sourceRecord else null
        val request = IdentityAdoptionGenericRequest(reference, origin.sourceDigest, target.rkey, target.record, target.blobs, repository)
        val profile = connection.identityAdoptionGenericSource(source, operation, request, now)
        stagingCheck(
            profile.record == origin.sourceRecord &&
                profile.version == origin.sourceVersion &&
                profile.deletedAt == origin.sourceDeletedAt,
            IdentityAdoptionStagingFailure.SOURCE_CHANGED,
        )
        if (repository != null) {
            repositoryRecords.add(IdentityAdoptionRepositoryRecord(repository.collection, repository.rkey, reference.key.single()))
        }
        if (seen.add(origin.bodyDigest)) hash.update(HexFormat.of().parseHex(origin.bodyDigest))
    }

    private fun origins(
        reference: IdentityAdoptionSourceReference,
        rkey: String,
    ): List<IdentityAdoptionStagedOrigin> =
        connection
            .prepareStatement(
                "SELECT * FROM identity_adoption_staged_origins WHERE operation_id = ? AND account_id = ? " +
                    "AND copy_generation = ? AND target_rkey = ? AND source_kind = ? AND source_key_hash = ? " +
                    "ORDER BY source_collection COLLATE \"C\", source_rkey COLLATE \"C\"",
            ).use {
                it.setString(1, operation.id)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                it.setLong(3, generation)
                it.setString(4, rkey)
                it.setString(5, reference.kind.name)
                it.setString(6, stagingHash(sourceMappingKey(reference)))
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.originView()) } }
            }
}
