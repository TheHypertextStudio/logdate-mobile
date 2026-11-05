@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.space.adoption.IdentityAdoptionBlobInventory
import app.logdate.space.adoption.IdentityAdoptionBlobSummary
import app.logdate.space.adoption.IdentityAdoptionRecordEvidence
import app.logdate.space.adoption.IdentityAdoptionRecordInventory
import app.logdate.space.adoption.IdentityAdoptionRecordSummary
import app.logdate.space.adoption.IdentityAdoptionWireKey
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.repo.Cid
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.HexFormat

/** Independent server reconstruction of the existing shared manifest inventory framing. */
internal class IdentityAdoptionManifestInventory(
    private val connection: Connection,
    private val operation: IdentityAdoptionOperation,
    private val generation: Long,
) {
    fun records(): IdentityAdoptionRecordSummary =
        IdentityAdoptionRecordInventory().use { inventory ->
            connection
                .prepareStatement(
                    "SELECT * FROM identity_adoption_staged_records WHERE operation_id = ? AND account_id = ? " +
                        "AND hold_generation = ? ORDER BY source_collection COLLATE \"C\", source_rkey COLLATE \"C\", rkey COLLATE \"C\"",
                ).use {
                    it.bind()
                    it.fetchSize = 1
                    it.executeQuery().use { rows ->
                        while (rows.next()) {
                            val record = rows.identityAdoptionStagedRecord()
                            inventory.append(
                                IdentityAdoptionRecordEvidence(
                                    IdentityAdoptionWireKey(record.sourceCollection, record.sourceRkey),
                                    IdentityAdoptionWireKey("app.logdate.crypto.sealed", record.rkey),
                                    record.sourceVersion,
                                    Bytes(HexFormat.of().parseHex(record.sourceDigest)),
                                    Bytes(HexFormat.of().parseHex(record.targetDigest)),
                                    record.record == null,
                                ),
                            )
                        }
                    }
                }
            inventory.finish()
        }

    fun blobs(): IdentityAdoptionBlobSummary =
        IdentityAdoptionBlobInventory().use { inventory ->
            connection
                .prepareStatement(
                    "SELECT cid,size_bytes FROM identity_adoption_staged_blobs WHERE operation_id = ? AND account_id = ? " +
                        "AND hold_generation = ? AND verified_at IS NOT NULL ORDER BY cid COLLATE \"C\"",
                ).use {
                    it.bind()
                    it.fetchSize = 1
                    it.executeQuery().use { rows ->
                        while (rows.next()) {
                            val cid = rows.getString("cid")
                            stagingCheck(IdentityAdoptionVerifiedBlob.acceptsCid(cid), IdentityAdoptionStagingFailure.CONFLICT)
                            inventory.append(cid, rows.getLong("size_bytes"), Bytes(Cid.require(cid).toBytes().copyOfRange(4, 36)))
                        }
                    }
                }
            inventory.finish()
        }

    /** A conservative maximum of the actual current independent source cursors and record versions. */
    fun sourceSequence(): Long =
        VERSIONS.maxOf { (kind, column) ->
            val table = kind.sourceTable()
            connection.prepareStatement("SELECT COALESCE(MAX(t.\"$column\"),0) FROM ${table.from} WHERE ${table.owner} = ?").use {
                if (table.didOwner) {
                    it.setString(1, operation.binding.sourceDid)
                } else {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                }
                it.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1).also { value -> stagingCheck(value >= 0, IdentityAdoptionStagingFailure.CONFLICT) }
                }
            }
        }

    private fun PreparedStatement.bind() {
        setString(1, operation.id)
        setObject(2, operation.binding.accountId.toJavaUUID())
        setLong(3, generation)
    }

    private companion object {
        val VERSIONS =
            listOf(
                IdentityAdoptionSourceKind.SPACE_STATE to "last_seq",
                IdentityAdoptionSourceKind.SPACE_RECORD to "seq",
                IdentityAdoptionSourceKind.COLLECTION_STATE to "last_version",
                IdentityAdoptionSourceKind.COLLECTION_RECORD to "server_version",
                IdentityAdoptionSourceKind.SYNC_CONTENT to "server_version",
                IdentityAdoptionSourceKind.SYNC_JOURNAL to "server_version",
                IdentityAdoptionSourceKind.SYNC_ASSOCIATION to "server_version",
                IdentityAdoptionSourceKind.SYNC_MEDIA to "server_version",
                IdentityAdoptionSourceKind.LOCATION to "server_version",
                IdentityAdoptionSourceKind.LOCATION_VERSION to "version",
                IdentityAdoptionSourceKind.REPO_HEAD to "revision",
            )
    }
}
