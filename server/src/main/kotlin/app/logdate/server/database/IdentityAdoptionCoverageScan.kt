@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionCoverage
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingPolicy
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceCensus
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceItem
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagedRecord
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.security.MessageDigest
import java.sql.Connection
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Instant

/** Streams source pages and destination bytes; retains keys and graph metadata rather than all ciphertext. */
internal class IdentityAdoptionCoverageScan(
    private val source: DataSource,
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
    private val census: IdentityAdoptionSourceCensus,
    private val now: Instant,
) {
    private val mappings =
        MessageDigest.getInstance("SHA-256").apply {
            update("app.logdate.identity-adoption.source-mappings|1\u0000".encodeToByteArray())
        }
    private val targets = mutableSetOf<String>()
    private val media = mutableSetOf<String>()
    private val roles = census.repository.blocks.associate { it.cid to it.roles }
    private val authority = connection.identityAdoptionStorageAuthority(source, operation, now)
    private var count = 0L
    private val rows = IdentityAdoptionCoverageRows(connection, operation, census.generation)
    private val origins = IdentityAdoptionCoverageOrigins(source, connection, operation, census.generation, now)

    fun read(): IdentityAdoptionCoverage {
        for (kind in IdentityAdoptionSourceKind.entries) scan(kind)
        stagingCheck(rows.count("identity_adoption_source_mappings", "copy_generation") == count, IdentityAdoptionStagingFailure.CONFLICT)
        val records = rows.verifyTargets(targets, media)
        val originCoverage = origins.finish(authority.activeRepositoryRecords(census.repository))
        stagingCheck(
            rows.count("identity_adoption_staged_blobs", "hold_generation") == media.size.toLong(),
            IdentityAdoptionStagingFailure.CONFLICT,
        )
        val mappingDigest = HexFormat.of().formatHex(mappings.digest())
        val mediaDigest = mediaDigest()
        val value =
            IdentityAdoptionCoverage(
                census,
                count,
                mappingDigest,
                records.count,
                media.size.toLong(),
                mediaDigest,
                originCoverage.count,
                originCoverage.digest,
                records.digest,
                "",
            )
        return value.copy(digest = identityAdoptionCoverageDigest(value))
    }

    private fun scan(kind: IdentityAdoptionSourceKind) {
        val reader = PostgreSQLIdentityAdoptionSourceInventory(source)
        val expected = census.sources.single { it.kind == kind }
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("app.logdate.identity-adoption.source-items|1\u0000".encodeToByteArray())
        var cursor: List<String>? = null
        var seen = 0L
        do {
            val page = reader.pageLocked(connection, operation, kind, cursor, 32, now)
            stagingCheck(page != null, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            val current = requireNotNull(page)
            stagingCheck(
                current.count == expected.count &&
                    current.present == expected.present &&
                    current.generation == census.generation &&
                    current.fenced,
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            for (item in current.items) {
                hash.update(IdentityAdoptionSourceEvidence.item(kind, item))
                accept(kind, item)
                seen = Math.addExact(seen, 1)
            }
            stagingCheck(
                current.next == null || (current.items.isNotEmpty() && current.next != cursor),
                IdentityAdoptionStagingFailure.SOURCE_CHANGED,
            )
            cursor = current.next
        } while (cursor != null)
        val observed = IdentityAdoptionSourceEvidence.summary(kind, expected.present, seen, hash.digest())
        stagingCheck(observed == expected, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
    }

    private fun accept(
        kind: IdentityAdoptionSourceKind,
        item: IdentityAdoptionSourceItem,
    ) {
        val mapping = rows.mapping(IdentityAdoptionSourceReference(kind, item.key))
        stagingCheck(mapping != null, IdentityAdoptionStagingFailure.CONFLICT)
        val value = requireNotNull(mapping)
        val request = value.request
        connection.requireMediaUploadMapping(operation, request)
        val digest = HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item))
        stagingCheck(request.sourceDigest == digest, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
        val selected: List<IdentityAdoptionStagedRecord> =
            request.targets.map { target ->
                val record = rows.target(target.rkey)
                stagingCheck(record != null && record.targetDigest == target.digest, IdentityAdoptionStagingFailure.CONFLICT)
                requireNotNull(record)
            }
        origins.require(request, selected)
        val referenced = request.disposition != IdentityAdoptionDisposition.RETAINED_HISTORY || rows.hasReferences(request.source)
        val repositoryRoles = if (kind == IdentityAdoptionSourceKind.REPO_BLOCK) roles[item.key.single()] else null
        val retired =
            authority.retainsSource(kind, item) ||
                (kind == IdentityAdoptionSourceKind.REPO_BLOCK && authority.retainsRepositoryBlock(item.key.single(), census.repository))
        IdentityAdoptionMappingPolicy.final(request, item, selected, referenced, repositoryRoles, retired)
        request.targets.forEach { targets += it.rkey }
        media += request.media
        mappings.update(HexFormat.of().parseHex(value.bodyDigest))
        count = Math.addExact(count, 1)
    }

    private fun mediaDigest(): String {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update("app.logdate.identity-adoption.verified-media|1\u0000".encodeToByteArray())
        val blobs = PostgreSQLIdentityAdoptionBlobs(source)
        for (cid in media.sorted()) {
            val admission = blobs.inspectLocked(connection, operation, census.generation, cid, now)
            stagingCheck(admission != null, IdentityAdoptionStagingFailure.CONFLICT)
            val value = requireNotNull(admission)
            val body =
                DagCborCodec.encode(
                    buildJsonObject {
                        put("cid", value.cid)
                        put("size", value.size)
                        put("mimeType", value.mimeType)
                        put("sourceSpace", value.sourceSpace)
                        put("storagePath", value.storagePath)
                    },
                )
            hash.update(HexFormat.of().parseHex(stagingHash(body)))
        }
        return HexFormat.of().formatHex(hash.digest())
    }
}
