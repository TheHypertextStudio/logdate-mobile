package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionSourceQuery
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.DagCborCodec

internal class PreparedIdentityAdoptionSourceQuery(
    value: IdentityAdoptionSourceQuery,
) {
    val query = value.copy(after = value.after?.toList())
    val digest: String

    init {
        val keys =
            query.kind
                .sourceTable()
                .keys.size
        val cursor = query.after
        stagingCheck(
            query.limit in 1..32 &&
                (
                    cursor == null ||
                        (cursor.size == keys && cursor.all { it.encodeToByteArray().size <= 4096 && '\u0000' !in it })
                ),
            IdentityAdoptionStagingFailure.INVALID_REQUEST,
        )
        digest =
            stagingHash(
                DagCborCodec.encode(
                    buildJsonObject {
                        put("version", 1)
                        put("purpose", "app.logdate.identity-adoption.source-page")
                        put("kind", query.kind.name)
                        put("after", cursor?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                        put("limit", query.limit)
                    },
                ),
            )
    }
}
