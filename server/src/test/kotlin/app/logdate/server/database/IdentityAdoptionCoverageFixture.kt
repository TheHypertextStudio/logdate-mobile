@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionTargetReference
import app.logdate.server.space.SpaceRecordWrite
import app.logdate.server.space.StoredSpaceRecord
import app.logdate.server.space.recordCid
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.HexFormat
import kotlin.test.assertNotNull

internal fun withAdoptionCoverage(
    recordCount: Int = 1,
    withMedia: Boolean = false,
    block: suspend IdentityAdoptionCoverageFixture.() -> Unit,
) = withIdentityAdoptionAuthorization {
    source.blueskyTransaction {
        prepareStatement("UPDATE accounts SET storage_mode = ? WHERE id = ?").use {
            it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            check(it.executeUpdate() == 1)
        }
    }
    val space = SpaceRef.personal(operation.binding.sourceDid).uri
    val media = if (withMedia) IdentityAdoptionCoverageMediaFixture(this) else null
    val blobs =
        media?.blob?.let { blob ->
            PostgreSQLSpaceStore().putBlob(operation.binding.accountId.toJavaUUID(), space, blob)
            listOf(blob.cid)
        } ?: emptyList()
    val originals =
        (0 until recordCount).map { index ->
            val key = if (index == 0) "note" else "note" + index.toString().padStart(2, '0')
            PostgreSQLSpaceStore().put(
                SpaceRecordWrite(
                    operation.binding.accountId.toJavaUUID(),
                    space,
                    operation.binding.sourceDid,
                    key,
                    coverageSealed("source-$key"),
                    blobs,
                    null,
                    false,
                ),
            )
        }
    authorize()
    val held =
        (
            operations.transition(
                operation.binding.accountId,
                operation.id,
                operation.revision,
                IdentityAdoptionPhase.FENCED,
                clock.now(),
            ) as IdentityAdoptionOutcome.Accepted
        ).value
    val targets = originals.associate { original -> original.rkey to stageCoverageRecord(this, held, original, blobs) }
    IdentityAdoptionCoverageFixture(this, held, targets, media).block()
}

internal class IdentityAdoptionCoverageFixture(
    val auth: IdentityAdoptionAuthorizedFixture,
    val held: IdentityAdoptionOperation,
    val targetDigests: Map<String, String>,
    val media: IdentityAdoptionCoverageMediaFixture?,
) {
    suspend fun mapAll(
        exceptRecord: String? = null,
        targetFor: (String) -> String = { it },
    ) {
        mapKind(IdentityAdoptionSourceKind.STORAGE_AUTHORITY, IdentityAdoptionDisposition.STRUCTURAL)
        mapRecords(exceptRecord, targetFor)
        mapKind(IdentityAdoptionSourceKind.SPACE_STATE, IdentityAdoptionDisposition.STRUCTURAL)
        if (media != null) {
            mapKind(IdentityAdoptionSourceKind.SPACE_BLOB, IdentityAdoptionDisposition.MEDIA)
            mapKind(IdentityAdoptionSourceKind.SPACE_REFERENCE, IdentityAdoptionDisposition.REFERENCES)
        }
    }

    suspend fun mapRecords(
        exceptRecord: String? = null,
        targetFor: (String) -> String = { it },
    ) {
        mapKind(IdentityAdoptionSourceKind.SPACE_RECORD, IdentityAdoptionDisposition.CONVERTED, exceptRecord, targetFor)
    }

    suspend fun coverage() = PostgreSQLIdentityAdoptionCoverage(auth.source).read(held, auth.clock.now())

    private suspend fun mapKind(
        kind: IdentityAdoptionSourceKind,
        disposition: IdentityAdoptionDisposition,
        exceptRecord: String? = null,
        targetFor: (String) -> String = { it },
    ) {
        val reader = PostgreSQLIdentityAdoptionSourceInventory(auth.source)
        var cursor: List<String>? = null
        do {
            val page = assertNotNull(reader.page(held, kind, cursor, now = auth.clock.now()))
            for (item in page.items) {
                if (kind == IdentityAdoptionSourceKind.SPACE_RECORD && item.key.last() == exceptRecord) continue
                val targets =
                    if (disposition in setOf(IdentityAdoptionDisposition.CONVERTED, IdentityAdoptionDisposition.REFERENCES)) {
                        val sourceKey = if (kind == IdentityAdoptionSourceKind.SPACE_REFERENCE) item.key[2] else item.key.last()
                        val key = targetFor(sourceKey)
                        listOf(IdentityAdoptionTargetReference(key, targetDigests.getValue(key)))
                    } else {
                        emptyList()
                    }
                val digest = HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item))
                val request =
                    IdentityAdoptionMappingRequest(
                        IdentityAdoptionSourceReference(kind, item.key),
                        digest,
                        disposition,
                        targets,
                        if (disposition ==
                            IdentityAdoptionDisposition.STRUCTURAL
                        ) {
                            emptyList()
                        } else {
                            media?.let { listOf(it.blob.cid) }.orEmpty()
                        },
                    )
                val body = prepareIdentityAdoptionMapping(request).bodyDigest
                val ticket = assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, body, auth.clock.now()))
                val signature = auth.actionSignature(ticket, held)
                assertNotNull(
                    PostgreSQLIdentityAdoptionMappings(auth.source).put(
                        held,
                        ticket,
                        signature,
                        request,
                        Long.MAX_VALUE,
                        auth.clock.now(),
                    ),
                )
            }
            cursor = page.next
        } while (cursor != null)
    }
}

private suspend fun stageCoverageRecord(
    auth: IdentityAdoptionAuthorizedFixture,
    held: IdentityAdoptionOperation,
    original: StoredSpaceRecord,
    blobs: List<String>,
): String {
    val stage =
        IdentityAdoptionStagingRequest(
            original.rkey,
            original.seq,
            stagingHash(recordCid(original.record).second),
            coverageSealed("target-${original.rkey}"),
            blobs,
        )
    val digest = prepareIdentityAdoptionRecord(stage).bodyDigest
    val ticket = assertNotNull(auth.actions.issue(held, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
    val signature = auth.actionSignature(ticket, held)
    val records = PostgreSQLIdentityAdoptionStaging(auth.source)
    return assertNotNull(records.put(held, ticket, signature, stage, Long.MAX_VALUE, auth.clock.now())).targetDigest
}

private fun coverageSealed(label: String) =
    buildJsonObject {
        put("\$type", "app.logdate.crypto.sealed")
        put("version", 1)
        put("keyId", label)
        putJsonObject("nonce") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(12))) }
        putJsonObject("ciphertext") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(528))) }
    }
