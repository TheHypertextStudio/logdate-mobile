@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceBinary
import app.logdate.server.identity.adoption.IdentityAdoptionSourceItem
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourcePage
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.time.Instant

/** Internal raw input for conversion and verification; never an ordinary journal/list endpoint. */
internal class PostgreSQLIdentityAdoptionSourceInventory(
    private val source: DataSource,
) {
    suspend fun page(
        operation: IdentityAdoptionOperation,
        kind: IdentityAdoptionSourceKind,
        after: List<String>? = null,
        limit: Int = 32,
        now: Instant,
    ): IdentityAdoptionSourcePage? {
        val cursor = after?.toList()
        return withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction { pageLocked(this, operation, kind, cursor, limit, now) }
        }
    }

    internal fun pageLocked(
        connection: Connection,
        operation: IdentityAdoptionOperation,
        kind: IdentityAdoptionSourceKind,
        after: List<String>?,
        limit: Int,
        now: Instant,
    ): IdentityAdoptionSourcePage? {
        val table = kind.sourceTable()
        val cursor = after?.toList()
        stagingCheck(
            limit in 1..32 &&
                (
                    cursor == null ||
                        (cursor.size == table.keys.size && cursor.all { it.encodeToByteArray().size <= 4096 && '\u0000' !in it })
                ),
            IdentityAdoptionStagingFailure.INVALID_REQUEST,
        )
        check(RawJdbcTransactionContext.isActive())
        return with(connection) {
            with(PostgreSQLIdentityAdoptionActions(source)) {
                if (currentKey(operation, now) == null) return null
            }
            requireOriginalRepository(operation)
            createStatement().use { it.execute("SET LOCAL TIME ZONE 'UTC'") }
            val fenced = operation.phase != IdentityAdoptionPhase.PREPARING
            val generation = if (fenced) generation(operation) else 0L
            if (!present(table)) {
                stagingCheck(table.optional, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
                return IdentityAdoptionSourcePage(
                    operation.id,
                    operation.revision,
                    generation,
                    fenced,
                    kind,
                    0,
                    emptyList(),
                    null,
                    present = false,
                )
            }
            val count = count(table, operation)
            val page = read(table, operation, cursor, limit)
            IdentityAdoptionSourcePage(operation.id, operation.revision, generation, fenced, kind, count, page.first, page.second)
        }
    }

    internal fun itemLocked(
        connection: Connection,
        operation: IdentityAdoptionOperation,
        kind: IdentityAdoptionSourceKind,
        key: List<String>,
        now: Instant,
    ): IdentityAdoptionSourceItem? {
        val table = kind.sourceTable()
        val frozen = key.toList()
        stagingCheck(
            frozen.size == table.keys.size && frozen.all { it.encodeToByteArray().size <= 4096 && '\u0000' !in it },
            IdentityAdoptionStagingFailure.INVALID_REQUEST,
        )
        check(RawJdbcTransactionContext.isActive())
        return with(connection) {
            with(PostgreSQLIdentityAdoptionActions(source)) { if (currentKey(operation, now) == null) return null }
            requireOriginalRepository(operation)
            createStatement().use { it.execute("SET LOCAL TIME ZONE 'UTC'") }
            if (!present(table)) {
                stagingCheck(table.optional, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
                return null
            }
            read(table, operation, frozen, 1, exact = true).first.singleOrNull()
        }
    }

    private fun Connection.present(table: IdentityAdoptionSourceTable): Boolean =
        prepareStatement("SELECT to_regclass(?) IS NOT NULL").use {
            it.setString(1, table.name)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getBoolean(1)
            }
        }

    private fun Connection.requireOriginalRepository(operation: IdentityAdoptionOperation) {
        prepareStatement("SELECT repo_did FROM logdate_collection_states WHERE user_id = ?").use {
            it.setObject(1, operation.binding.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                if (rows.next()) {
                    stagingCheck(
                        rows.getString(1) == operation.binding.sourceDid,
                        IdentityAdoptionStagingFailure.SOURCE_CHANGED,
                    )
                }
            }
        }
    }

    private fun Connection.generation(operation: IdentityAdoptionOperation): Long =
        prepareStatement("SELECT hold_generation FROM identity_adoption_operations WHERE id = ? AND account_id = ?").use {
            it.setString(1, operation.id)
            it.setObject(2, operation.binding.accountId.toJavaUUID())
            it.executeQuery().use { rows ->
                stagingCheck(rows.next(), IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                rows.getLong(1).also { value ->
                    stagingCheck(value > 0 && value <= operation.revision, IdentityAdoptionStagingFailure.SOURCE_CHANGED)
                }
            }
        }

    private fun Connection.count(
        table: IdentityAdoptionSourceTable,
        operation: IdentityAdoptionOperation,
    ): Long =
        prepareStatement("SELECT count(*) FROM ${table.from} WHERE ${table.owner} = ?").use {
            it.bindOwner(table, operation)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }

    private fun PreparedStatement.bindOwner(
        table: IdentityAdoptionSourceTable,
        operation: IdentityAdoptionOperation,
    ) {
        if (table.didOwner) setString(1, operation.binding.sourceDid) else setObject(1, operation.binding.accountId.toJavaUUID())
    }

    private fun Connection.read(
        table: IdentityAdoptionSourceTable,
        operation: IdentityAdoptionOperation,
        cursor: List<String>?,
        limit: Int,
        exact: Boolean = false,
    ): Pair<List<IdentityAdoptionSourceItem>, List<String>?> {
        val projection = metadataProjection(table)
        val fields = projection.first
        val nullColumns = projection.second
        val maxBody = table.binaryLimit
        val body = table.binary?.let { "t.\"$it\"" }
        val binary =
            if (body == null) {
                "NULL::bigint AS binary_size, NULL::text AS binary_digest, NULL::bytea AS binary_payload"
            } else {
                val payload = if (table.returnBytes) "CASE WHEN octet_length($body) <= $maxBody THEN $body END" else "NULL::bytea"
                "octet_length($body)::bigint AS binary_size, encode(sha256($body), 'hex') AS binary_digest, $payload AS binary_payload"
            }
        val comparison = if (exact) "=" else ">"
        val seek = if (cursor == null) "" else " AND (${table.keySql}) COLLATE \"C\" $comparison ?::text[] COLLATE \"C\""
        val sql =
            "SELECT ${table.keySql} AS source_key, CASE WHEN octet_length(m.fields::text) <= $MAX_METADATA_BYTES " +
                "THEN m.fields::text END AS metadata, ($nullColumns)::text AS sql_nulls, $binary FROM ${table.from} " +
                "CROSS JOIN LATERAL (SELECT $fields AS fields) m WHERE ${table.owner} = ?$seek " +
                "ORDER BY (${table.keySql}) COLLATE \"C\" LIMIT ?"
        val array = cursor?.let { createArrayOf("text", it.toTypedArray()) }
        try {
            return prepareStatement(sql).use {
                // A transaction-bound PostgreSQL cursor avoids buffering the whole page's binary bodies.
                it.fetchSize = 1
                it.bindOwner(table, operation)
                var index = 2
                if (array != null) it.setArray(index++, array)
                it.setInt(index, limit + 1)
                it.executeQuery().use { rows -> collect(rows, table, limit) }
            }
        } finally {
            array?.free()
        }
    }

    private fun collect(
        rows: ResultSet,
        table: IdentityAdoptionSourceTable,
        limit: Int,
        exact: Boolean = false,
    ): Pair<List<IdentityAdoptionSourceItem>, List<String>?> {
        val items = mutableListOf<IdentityAdoptionSourceItem>()
        var bytes = 0L
        while (rows.next()) {
            if (items.size == limit) return items to items.last().key
            val metadata = rows.getString("metadata")
            stagingCheck(metadata != null, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
            val binary = binary(rows, table)
            val rawNulls = rows.getString("sql_nulls")
            val sqlNulls =
                Json
                    .parseToJsonElement(rawNulls)
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            val size =
                requireNotNull(metadata).encodeToByteArray().size.toLong() + rawNulls.encodeToByteArray().size + (binary?.bytes?.size ?: 0)
            stagingCheck(size <= MAX_PAGE_BYTES, IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
            if (bytes + size > MAX_PAGE_BYTES) return items to items.last().key
            val array = rows.getArray("source_key")
            val key =
                try {
                    (array.array as Array<*>).map { it as String }
                } finally {
                    array.free()
                }
            items += IdentityAdoptionSourceItem(key, Json.parseToJsonElement(metadata).jsonObject, binary, sqlNulls)
            bytes += size
        }
        return items to null
    }

    private fun binary(
        rows: ResultSet,
        table: IdentityAdoptionSourceTable,
    ): IdentityAdoptionSourceBinary? {
        val size = (rows.getObject("binary_size") as? Number)?.toLong()
        if (size == null) {
            stagingCheck(table.name != "atproto_repo_blocks", IdentityAdoptionStagingFailure.SOURCE_CHANGED)
            return null
        }
        stagingCheck(size >= 0 && (!table.returnBytes || size <= table.binaryLimit), IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
        return IdentityAdoptionSourceBinary(
            size,
            rows.getString("binary_digest"),
            if (table.returnBytes) rows.getBytes("binary_payload") else null,
        )
    }

    /** Include future SQL fields too; excluding a known binary column must not project away extensions. */
    private fun Connection.metadataProjection(table: IdentityAdoptionSourceTable): Pair<String, String> =
        prepareStatement(
            "SELECT attname, atttypid = 'bytea'::regtype AS binary FROM pg_attribute " +
                "WHERE attrelid = ?::regclass AND attnum > 0 AND NOT attisdropped ORDER BY attnum",
        ).use {
            it.setString(1, table.name)
            it.executeQuery().use { rows ->
                val fields = mutableListOf<String>()
                val nulls = mutableListOf<String>()
                var foundBinary = table.binary == null
                while (rows.next()) {
                    val name = rows.getString("attname")
                    val literal = "'${name.replace("'", "''")}'"
                    val identifier = "t.\"${name.replace("\"", "\"\"")}\""
                    nulls += "CASE WHEN $identifier IS NULL THEN $literal END"
                    if (name == table.binary) {
                        stagingCheck(rows.getBoolean("binary"), IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
                        foundBinary = true
                    } else {
                        stagingCheck(!rows.getBoolean("binary"), IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
                        fields += "'${name.replace("'", "''")}',t.\"${name.replace("\"", "\"\"")}\""
                    }
                }
                stagingCheck(foundBinary && fields.isNotEmpty(), IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
                val metadata = fields.joinToString(",", "jsonb_build_object(", ")")
                val absent = nulls.joinToString(",", "to_jsonb(array_remove(ARRAY[", "]::text[],NULL))")
                metadata to absent
            }
        }

    private companion object {
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        const val MAX_PAGE_BYTES = 8L * 1024 * 1024
    }
}
