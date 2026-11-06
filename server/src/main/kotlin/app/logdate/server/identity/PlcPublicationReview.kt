package app.logdate.server.identity

import kotlinx.serialization.Serializable
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.plc.PlcEncoding
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcOperations
import studio.hypertext.atproto.syntax.Handle
import java.util.Base64

/** The exact signed operations that may be bound to one passkey confirmation. */
internal class PlcPublicationReview private constructor(
    val did: AtprotoDid,
    private val chain: List<ReviewedOperation>,
) {
    val operationCids: List<String> get() = chain.map(ReviewedOperation::cid)
    val signingKeyMultibase: String get() =
        chain
            .last()
            .operation.verificationMethods
            .getValue("atproto")
            .removePrefix("did:key:")
    val recoveryDidKey: String get() =
        chain
            .last()
            .operation.rotationKeys
            .first()

    val history: PlcPublicationHistory
        get() {
            val operations = operations()
            val changes = operations.zipWithNext()
            val currentUsername = operations.last().username()
            return PlcPublicationHistory(
                updates = changes.size,
                signingChanges = changes.count { (before, after) -> before.verificationMethods != after.verificationMethods },
                recoveryChanges = changes.count { (before, after) -> before.rotationKeys != after.rotationKeys },
                previousUsernames = operations.map(PlcOperation::username).filterNot { it == currentUsername }.distinct(),
            )
        }

    fun operations(): List<PlcOperation> = chain.map(ReviewedOperation::operation)

    /**
     * A caller may pass an empty audit log only after an authoritative lookup found no operations.
     * A directory read failure is an uncertain outcome and must be retried as a read.
     */
    fun reconcile(auditLog: List<PlcIndexedOperation>): PlcPublicationProgress {
        if (auditLog.size > chain.size) return PlcPublicationProgress.Conflict
        auditLog.forEachIndexed { index, entry ->
            val reviewed = chain[index]
            if (entry.did != did || entry.nullified || entry.cid != reviewed.cid || entry.operation != reviewed.operation) {
                return PlcPublicationProgress.Conflict
            }
        }
        return chain
            .getOrNull(auditLog.size)
            ?.let { PlcPublicationProgress.Next(it.operation) }
            ?: PlcPublicationProgress.Confirmed
    }

    fun matchesDocument(document: DidDocument): Boolean {
        val head = chain.last().operation
        val expectedPds = head.services.getValue("atproto_pds")
        val key = document.verificationMethod.singleOrNull { it.id == "$did#atproto" }
        val pds = document.service.singleOrNull { it.id == "#atproto_pds" || it.id == "$did#atproto_pds" }
        return document.id == did && document.alsoKnownAs == head.alsoKnownAs &&
            key != null && key.controller == did && key.type == "Multikey" && key.publicKeyMultibase == signingKeyMultibase &&
            pds != null && pds.serviceEndpoint == expectedPds.endpoint && pds.type == expectedPds.type
    }

    private data class ReviewedOperation(
        val cid: String,
        val operation: PlcOperation,
    )

    companion object {
        fun prepare(
            did: AtprotoDid,
            handle: String,
            pdsEndpoint: String,
            signingDidKey: String,
            recoveryDidKey: String,
            serverRotationDidKey: String,
            operations: List<PlcOperation>,
        ): PlcPublicationReview {
            val finalKeys = listOf(recoveryDidKey, serverRotationDidKey)
            require((finalKeys + signingDidKey).distinct().size == 3) {
                "Publication requires distinct recovery, server rotation, and repository signing keys"
            }
            require(
                (finalKeys + signingDidKey).all { key ->
                    key.startsWith("did:key:") &&
                        runCatching {
                            EcKeySupport.decodePublicKey(key.removePrefix("did:key:"))
                        }.isSuccess
                },
            ) { "Publication requires valid EC keys" }
            val reviewedIdentity = PlcOperations.atprotoGenesis(handle, pdsEndpoint, signingDidKey, finalKeys)
            val followers = operations.groupBy(PlcOperation::prev)
            val genesis = requireNotNull(followers[null]?.singleOrNull()) { "The private chain must have exactly one genesis" }
            require(PlcEncoding.deriveDid(genesis) == did) { "The stored genesis must derive the reviewed DID" }
            val chain =
                buildList {
                    var operation = genesis
                    var authorizedKeys = genesis.rotationKeys
                    repeat(operations.size) {
                        require(operation.type == PlcOperation.TYPE && verifies(operation, authorizedKeys)) {
                            "The private chain contains an invalid operation signature"
                        }
                        require(operation.services == reviewedIdentity.services) {
                            "Every reviewed operation must name the reviewed PDS"
                        }
                        operation.username()
                        val cid = PlcEncoding.operationCid(operation)
                        add(ReviewedOperation(cid, operation))
                        val next = followers[cid].orEmpty()
                        if (next.isEmpty()) return@buildList
                        authorizedKeys = operation.rotationKeys
                        operation = requireNotNull(next.singleOrNull()) { "The private operation history forks" }
                    }
                }
            require(chain.size == operations.size) { "The private chain contains unreachable operations" }
            val head = chain.last().operation
            require(head.alsoKnownAs == reviewedIdentity.alsoKnownAs) { "The current operation must name the reviewed handle" }
            require(head.rotationKeys == finalKeys && head.verificationMethods == reviewedIdentity.verificationMethods) {
                "The current operation must name the reviewed signing key and place the recovery key first"
            }
            return PlcPublicationReview(did, chain)
        }

        private fun verifies(
            operation: PlcOperation,
            authorizedKeys: List<String>,
        ): Boolean {
            val signature = runCatching { Base64.getUrlDecoder().decode(requireNotNull(operation.sig)) }.getOrNull() ?: return false
            val payload = PlcEncoding.encodeUnsigned(operation.unsigned())
            return authorizedKeys.any { key ->
                if (!key.startsWith("did:key:")) return@any false
                runCatching {
                    val decoded = EcKeySupport.decodePublicKey(key.removePrefix("did:key:"))
                    EcKeySupport.hasLowS(signature, decoded.curve) &&
                        EcKeySupport.verifySha256(decoded.publicKey, decoded.curve, payload, signature)
                }.getOrDefault(false)
            }
        }
    }
}

private fun PlcOperation.username(): String {
    val alias = requireNotNull(alsoKnownAs.singleOrNull()) { "The reviewed operation must name one username" }
    require(alias.startsWith("at://")) { "The reviewed username must use an AT Protocol alias" }
    val username = alias.removePrefix("at://")
    require(Handle.require(username).toString() == username) { "The reviewed username must be canonical" }
    return username
}

@Serializable
internal data class PlcPublicationHistory(
    val updates: Int,
    val signingChanges: Int,
    val recoveryChanges: Int,
    val previousUsernames: List<String> = emptyList(),
)

internal sealed interface PlcPublicationProgress {
    data class Next(
        val operation: PlcOperation,
    ) : PlcPublicationProgress

    data object Confirmed : PlcPublicationProgress

    data object Conflict : PlcPublicationProgress
}
