package app.logdate.server.sync

import app.logdate.server.database.requireLegacyCollectionWrite
import app.logdate.server.migration.SpaceMigrationConfig
import app.logdate.shared.model.sync.LocationHistoryRecord
import app.logdate.shared.model.sync.LocationHistoryUpload
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock

object LocationHistoryVersionsTable : Table("location_history_versions") {
    val userId = javaUUID("user_id")
    val version = long("version")
    override val primaryKey = PrimaryKey(userId)
}

object LocationHistoryTable : Table("location_history") {
    val userId = javaUUID("user_id")
    val id = varchar("id", 128)
    val recordType = varchar("record_type", 32)
    val payload = text("payload").nullable()
    val payloadSchemaVersion = integer("payload_schema_version")
    val deviceId = varchar("device_id", 128)
    val deviceVersion = long("device_version")
    val serverVersion = long("server_version")
    val deleted = bool("deleted")
    override val primaryKey = PrimaryKey(userId, id)

    init {
        index(false, userId, serverVersion)
    }
}

/** The per-account row lock serializes version allocation and batch conflict checks across servers. */
class DbLocationHistoryRepository(
    private val migrationConfig: SpaceMigrationConfig = SpaceMigrationConfig(),
    private val clock: Clock = Clock.System,
) : LocationHistoryRepository {
    override fun upload(
        userId: UUID,
        records: List<LocationHistoryUpload>,
    ): List<LocationHistoryRecord> =
        transaction {
            requireLegacyCollectionWrite(userId, migrationConfig, clock.now())
            validateLocationHistoryBatch(records)
            LocationHistoryVersionsTable.insertIgnore {
                it[LocationHistoryVersionsTable.userId] = userId
                it[version] = 0
            }
            var version =
                LocationHistoryVersionsTable
                    .selectAll()
                    .where { LocationHistoryVersionsTable.userId eq userId }
                    .forUpdate()
                    .single()[LocationHistoryVersionsTable.version]
            val existing =
                records.associate { upload ->
                    upload.id to
                        LocationHistoryTable
                            .selectAll()
                            .where {
                                (LocationHistoryTable.userId eq userId) and (LocationHistoryTable.id eq upload.id)
                            }.singleOrNull()
                            ?.toLocationHistoryRecord()
                }
            records.forEach { validateLocationHistoryVersion(existing[it.id], it) }
            val results =
                records.map { upload ->
                    val previous = existing[upload.id]
                    if (previous != null && previous.matches(upload)) {
                        previous
                    } else {
                        val record = upload.toRecord(++version)
                        LocationHistoryTable.deleteWhere {
                            (LocationHistoryTable.userId eq userId) and (id eq upload.id)
                        }
                        LocationHistoryTable.insert {
                            it[LocationHistoryTable.userId] = userId
                            it[id] = record.id
                            it[recordType] = record.recordType
                            it[payload] = record.payload
                            it[payloadSchemaVersion] = record.payloadSchemaVersion
                            it[deviceId] = record.deviceId
                            it[deviceVersion] = record.deviceVersion
                            it[serverVersion] = record.serverVersion
                            it[deleted] = record.deleted
                        }
                        record
                    }
                }
            LocationHistoryVersionsTable.update({ LocationHistoryVersionsTable.userId eq userId }) {
                it[LocationHistoryVersionsTable.version] = version
            }
            results
        }

    override fun changes(
        userId: UUID,
        since: Long,
        limit: Int,
    ) = transaction {
        require(since >= 0 && limit in 1..100)
        val records =
            LocationHistoryTable
                .selectAll()
                .where {
                    (LocationHistoryTable.userId eq userId) and (LocationHistoryTable.serverVersion greater since)
                }.orderBy(LocationHistoryTable.serverVersion to SortOrder.ASC)
                .limit(changePageReadSize(limit))
                .map { it.toLocationHistoryRecord() }
        locationHistoryPage(records, since, limit)
    }
}

private fun ResultRow.toLocationHistoryRecord() =
    LocationHistoryRecord(
        this[LocationHistoryTable.id],
        this[LocationHistoryTable.recordType],
        this[LocationHistoryTable.payload],
        this[LocationHistoryTable.payloadSchemaVersion],
        this[LocationHistoryTable.deviceId],
        this[LocationHistoryTable.deviceVersion],
        this[LocationHistoryTable.serverVersion],
        this[LocationHistoryTable.deleted],
    )
