@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec
import java.security.MessageDigest
import java.util.HexFormat

/** Typed lexical JSON evidence preserves unknown decimal tokens without floating-point coercion. */
internal object IdentityAdoptionSourceEvidence {
    fun item(
        kind: IdentityAdoptionSourceKind,
        item: IdentityAdoptionSourceItem,
    ): ByteArray = hash(canonicalItem(kind, item))

    fun canonicalItem(
        kind: IdentityAdoptionSourceKind,
        item: IdentityAdoptionSourceItem,
    ): ByteArray =
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("kind", kind.name)
                put("key", JsonArray(item.key.map(::JsonPrimitive)))
                put("fields", typed(item.fields, 0))
                put("sqlNullColumns", JsonObject(item.sqlNullColumns.associateWith { JsonPrimitive(true) }))
                put(
                    "binary",
                    item.binary?.let { body ->
                        buildJsonObject {
                            put("size", body.size)
                            put("digest", body.digest)
                        }
                    } ?: JsonNull,
                )
            },
        )

    fun summary(
        kind: IdentityAdoptionSourceKind,
        present: Boolean,
        count: Long,
        items: ByteArray,
    ): IdentityAdoptionSourceSummary {
        val digest =
            hex(
                hash(
                    DagCborCodec.encode(
                        buildJsonObject {
                            put("version", 1)
                            put("kind", kind.name)
                            put("present", present)
                            put("count", count)
                            put("items", hex(items))
                        },
                    ),
                ),
            )
        return IdentityAdoptionSourceSummary(kind, present, count, digest)
    }

    fun census(
        operation: IdentityAdoptionOperation,
        generation: Long,
        fenced: Boolean,
        sources: List<IdentityAdoptionSourceSummary>,
        repository: IdentityAdoptionRepositoryInventory,
    ): String =
        hex(
            hash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("operationId", operation.id)
                        put("accountId", operation.binding.accountId.toString())
                        put("deviceId", operation.binding.deviceId)
                        put("sourceDid", operation.binding.sourceDid)
                        put("targetDid", operation.binding.targetDid)
                        put("sourceEpoch", operation.binding.sourceEpoch)
                        put("revision", operation.revision)
                        put("phase", operation.phase.name)
                        put("generation", generation)
                        put("fenced", fenced)
                        put(
                            "sources",
                            JsonArray(
                                sources.map { summary ->
                                    buildJsonObject {
                                        put("kind", summary.kind.name)
                                        put("present", summary.present)
                                        put("count", summary.count)
                                        put("digest", summary.digest)
                                    }
                                },
                            ),
                        )
                        put("repository", repositoryValue(repository))
                    },
                ),
            ),
        )

    private fun repositoryValue(repository: IdentityAdoptionRepositoryInventory): JsonObject =
        buildJsonObject {
            put(
                "records",
                JsonArray(
                    repository.records.map { row ->
                        buildJsonObject {
                            put("collection", row.collection)
                            put("rkey", row.rkey)
                            put("cid", row.cid)
                        }
                    },
                ),
            )
            put(
                "blocks",
                JsonArray(
                    repository.blocks.map { block ->
                        buildJsonObject {
                            put("cid", block.cid)
                            put(
                                "roles",
                                JsonArray(
                                    block.roles
                                        .map { it.name }
                                        .sorted()
                                        .map(::JsonPrimitive),
                                ),
                            )
                        }
                    },
                ),
            )
        }

    private fun typed(
        value: JsonElement,
        depth: Int,
    ): JsonElement {
        if (depth > 128) throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
        val tag: String
        val content: JsonElement?
        when (value) {
            JsonNull -> {
                tag = "null"
                content = null
            }
            is JsonObject -> {
                tag = "object"
                content = JsonObject(value.mapValues { typed(it.value, depth + 1) })
            }
            is JsonArray -> {
                tag = "array"
                content = JsonArray(value.map { typed(it, depth + 1) })
            }
            is JsonPrimitive ->
                when {
                    value.isString -> {
                        tag = "string"
                        content = value
                    }
                    value.booleanOrNull != null -> {
                        tag = "boolean"
                        content = value
                    }
                    else -> {
                        tag = "number"
                        content = JsonPrimitive(value.content)
                    }
                }
        }
        return JsonArray(listOfNotNull(JsonPrimitive(tag), content))
    }

    private fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
}
