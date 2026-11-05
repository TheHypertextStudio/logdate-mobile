package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionGenericCopy
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionStagedOrigin
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.space.decodeRecord
import app.logdate.server.space.recordCid
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.sql.Connection
import kotlin.time.Instant

internal class IdentityAdoptionGenericWriter(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
    generation: Long,
    private val prepared: PreparedIdentityAdoptionGeneric,
    private val profile: IdentityAdoptionGenericSource,
    private val limit: Long,
    private val now: Instant,
) {
    private val rows = IdentityAdoptionGenericRows(connection, operation, generation)
    private val request = prepared.request

    fun put(): IdentityAdoptionGenericCopy {
        val oldOrigin = rows.origin(request.source, profile.record, request.rkey)
        stagingCheck(oldOrigin == null || oldOrigin.targetRkey == request.rkey, IdentityAdoptionStagingFailure.CONFLICT)
        var record = rows.target(request.rkey)
        if (oldOrigin != null && oldOrigin.sourceDigest != request.sourceDigest) {
            stagingCheck(operation.phase == IdentityAdoptionPhase.PREPARING, IdentityAdoptionStagingFailure.CONFLICT)
            val current = requireNotNull(record)
            if (rows.originCount(current.rkey) == 1L) {
                rows.deleteRecord(current.rkey)
                record = null
            } else {
                stagingCheck(sameGenericTarget(current, prepared, profile), IdentityAdoptionStagingFailure.CONFLICT)
                rows.deleteOrigin(oldOrigin)
            }
        }
        if (record != null) stagingCheck(sameGenericTarget(record, prepared, profile), IdentityAdoptionStagingFailure.CONFLICT)
        val priorTime = if (record == null) reuseEarlier() else null
        val candidate = record ?: candidate(priorTime)
        val origin =
            prepareIdentityAdoptionOrigin(
                IdentityAdoptionStagedOrigin(
                    request.source,
                    request.sourceDigest,
                    profile.record,
                    profile.version,
                    profile.deletedAt,
                    request.rkey,
                    candidate.targetSequence ?: candidate.sourceVersion,
                    candidate.targetDigest,
                    "",
                    0,
                ),
            )
        val currentOrigin = rows.origin(request.source, profile.record, request.rkey)
        if (currentOrigin != null) {
            stagingCheck(currentOrigin == origin.value, IdentityAdoptionStagingFailure.CONFLICT)
            return IdentityAdoptionGenericCopy(candidate, currentOrigin)
        }
        val bytes = candidate.record?.let { recordCid(it).second } ?: DagCborCodec.encode(requireNotNull(candidate.tombstone))
        val growth = origin.bytes.size.toLong() + if (record == null) bytes.size.toLong() else 0L
        connection.requireIdentityAdoptionStagingRoom(operation, growth, limit, now)
        if (record == null) rows.insertRecord(candidate, bytes, prepared.blobBytes, now)
        rows.insertOrigin(origin)
        return IdentityAdoptionGenericCopy(candidate, origin.value)
    }

    private fun reuseEarlier(): String? {
        val earlier = rows.earlier(request.source, profile.record, request.rkey) ?: return null
        val origin = earlier.origin(request.source, profile.record, request.rkey) ?: return null
        if (origin.sourceDigest != request.sourceDigest || origin.targetRkey != request.rkey) return null
        val candidate = earlier.target(request.rkey) ?: return null
        if (!sameGenericTarget(candidate, prepared, profile) || earlier.originCount(request.rkey) != 1L) return null
        val time = (candidate.tombstone?.get("deletedAt") as? JsonPrimitive)?.content
        earlier.deleteRecord(request.rkey)
        return time
    }

    private fun candidate(priorTime: String?): IdentityAdoptionStagedRecord {
        val sequence = rows.sequence()
        val marker =
            if (profile.deleted) {
                buildJsonObject {
                    put("deleted", true)
                    put("rkey", request.rkey)
                    put("seq", sequence)
                    put("deletedAt", profile.deletedAt ?: priorTime ?: now.toString())
                }
            } else {
                null
            }
        val bytes = prepared.targetBytes ?: DagCborCodec.encode(requireNotNull(marker))
        return IdentityAdoptionStagedRecord(
            rows.generation,
            request.rkey,
            profile.record.collection,
            profile.record.rkey,
            profile.version,
            request.sourceDigest,
            stagingHash(bytes),
            prepared.request.record?.let { recordCid(it).first },
            prepared.targetBytes?.let(::decodeRecord),
            request.blobs,
            marker,
            sequence,
        )
    }
}
