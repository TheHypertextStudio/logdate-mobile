@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity

import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcOperation

/** Synthetic preexisting directory history, independent of private account provisioning. */
suspend fun seedPublishedGenesis(
    history: HostedPlcOperationRepository,
    did: String,
    published: MutableMap<String, MutableList<PlcIndexedOperation>>,
) {
    val stored = history.listByDid(did).single()
    check(stored.prevCid == null && published[did].isNullOrEmpty())
    val operation = Json.decodeFromString(PlcOperation.serializer(), stored.operationJson)
    val cid = requireNotNull(stored.cid)
    check(history.markPublicationAttempted(stored.id, stored.identityEpoch))
    check(history.recordPublishedCid(stored.id, cid, stored.identityEpoch))
    published[did] = mutableListOf(PlcIndexedOperation(AtprotoDid.require(did), operation, cid, false, "2026-01-01T00:00:00Z"))
}
