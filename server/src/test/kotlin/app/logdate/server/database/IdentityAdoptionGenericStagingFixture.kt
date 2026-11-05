@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionGenericCopy
import app.logdate.server.identity.adoption.IdentityAdoptionGenericRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import studio.hypertext.atproto.repo.DagCborCodec
import java.util.Base64
import java.util.HexFormat
import kotlin.test.assertNotNull

internal fun withGenericAdoption(
    deleted: Boolean = false,
    block: suspend IdentityAdoptionGenericFixture.() -> Unit,
) = withIdentityAdoptionAuthorization {
    source.blueskyTransaction {
        prepareStatement(
            "INSERT INTO sync_content(id,user_id,type,content,created_at,last_updated,server_version,device_id,deleted,deleted_at) " +
                "VALUES ('legacy-note',?,'TEXT','opaque source',1000,1700,7,'original-device',?,?)",
        ).use {
            it.setObject(1, operation.binding.accountId.toJavaUUID())
            it.setBoolean(2, deleted)
            it.setObject(3, if (deleted) 1700L else null)
            check(it.executeUpdate() == 1)
        }
    }
    authorize()
    IdentityAdoptionGenericFixture(this).block()
}

internal class IdentityAdoptionGenericFixture(
    val auth: IdentityAdoptionAuthorizedFixture,
) {
    var current = auth.operation
    val store = PostgreSQLIdentityAdoptionGenericStaging(auth.source)

    suspend fun request(
        target: JsonObject? = genericSealed("target"),
        rkey: String = "converted-note",
        kind: IdentityAdoptionSourceKind = IdentityAdoptionSourceKind.SYNC_CONTENT,
        sourceKey: List<String>? = null,
    ): IdentityAdoptionGenericRequest {
        val reader = PostgreSQLIdentityAdoptionSourceInventory(auth.source)
        val page = assertNotNull(reader.page(current, kind, now = auth.clock.now()))
        val item = if (sourceKey == null) page.items.single() else page.items.single { it.key == sourceKey }
        return IdentityAdoptionGenericRequest(
            IdentityAdoptionSourceReference(kind, item.key),
            HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
            rkey,
            target,
            emptyList(),
        )
    }

    suspend fun put(
        value: IdentityAdoptionGenericRequest,
        limit: Long = Long.MAX_VALUE,
    ): IdentityAdoptionGenericCopy? {
        val digest = genericRequestDigest(value)
        val ticket = assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.STAGE_RECORDS, digest, auth.clock.now()))
        return store.put(current, ticket, auth.actionSignature(ticket, current), value, limit, auth.clock.now())
    }

    suspend fun read(value: IdentityAdoptionGenericRequest): IdentityAdoptionGenericCopy? {
        val ticket =
            assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.READ_STAGED, genericReadDigest(value), auth.clock.now()))
        return store.read(current, ticket, auth.actionSignature(ticket, current), value, auth.clock.now())
    }

    suspend fun hold() {
        current =
            (
                auth.operations.transition(
                    current.binding.accountId,
                    current.id,
                    current.revision,
                    IdentityAdoptionPhase.FENCED,
                    auth.clock.now(),
                ) as IdentityAdoptionOutcome.Accepted
            ).value
    }

    suspend fun edit() {
        auth.source.blueskyTransaction {
            createStatement().use { it.executeUpdate("UPDATE sync_content SET content = 'new opaque source',server_version = 8") }
        }
    }

    suspend fun used() = PostgreSQLSpaceStore().storedBytes(current.binding.accountId.toJavaUUID())
}

internal fun genericSealed(label: String) =
    buildJsonObject {
        put("\$type", "app.logdate.crypto.sealed")
        put("version", 1)
        put("keyId", label)
        putJsonObject("nonce") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(12))) }
        putJsonObject("ciphertext") { put("\$bytes", Base64.getEncoder().encodeToString(ByteArray(528))) }
    }

internal fun genericRequestDigest(request: IdentityAdoptionGenericRequest): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.generic-copy")
                put("source", genericSourceValue(request))
                put("sourceDigest", request.sourceDigest)
                put("rkey", request.rkey)
                put("record", request.record ?: JsonNull)
                put("blobs", JsonArray(request.blobs.map(::JsonPrimitive)))
            },
        ),
    )

internal fun genericReadDigest(request: IdentityAdoptionGenericRequest): String =
    stagingHash(
        DagCborCodec.encode(
            buildJsonObject {
                put("version", 1)
                put("purpose", "app.logdate.identity-adoption.generic-read")
                put("source", genericSourceValue(request))
                put("rkey", request.rkey)
            },
        ),
    )

private fun genericSourceValue(request: IdentityAdoptionGenericRequest) =
    buildJsonObject {
        put("kind", request.source.kind.name)
        put("key", JsonArray(request.source.key.map(::JsonPrimitive)))
        put(
            "repositoryRecord",
            request.repositoryRecord?.let { record ->
                buildJsonObject {
                    put("collection", record.collection)
                    put("rkey", record.rkey)
                }
            } ?: JsonNull,
        )
    }
