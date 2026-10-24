package app.logdate.client.data.account

import app.logdate.client.networking.HostedPlcOperationDataDto
import app.logdate.client.repository.account.IdentityRecoveryException
import app.logdate.client.repository.account.IdentityRecoveryFailure
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.plc.PlcEncoding
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcUnsignedOperation

/**
 * Checks, before the recovery key signs anything, that the operation the server prepared is exactly
 * the change the person asked for: replace the server's key and nothing else.
 *
 * The recovery key ranks above the server's key, and an operation it signs cannot be undone by a
 * lower key, so a server that is compromised or wrong must not be able to get it to sign something
 * else, such as an operation that drops the recovery key or moves the handle. The device decodes the
 * operation and requires:
 *
 * - its canonical encoding to be exactly the bytes it is asked to sign;
 * - `prev` to be the head of the identity's history;
 * - the rotation keys to be this device's recovery key first, then the new server key, and nothing else;
 * - the verification method to be the new server key;
 * - the handle and services to be unchanged from the head, and the handle to be the identity's.
 */
internal object RecoveryOperationCheck {
    private val strictJson = Json { ignoreUnknownKeys = false }
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * Throws [IdentityRecoveryException] with [IdentityRecoveryFailure.UNEXPECTED_CHANGE] unless
     * [unsignedOperationJson], whose canonical encoding must be [payload], asks [recoveryDidKey] to
     * sign exactly the replacement of the server's key with [nextKey].
     */
    fun requireExpected(
        unsignedOperationJson: String,
        nextKey: String,
        payload: ByteArray,
        history: List<HostedPlcOperationDataDto>,
        recoveryDidKey: String,
        handle: String,
    ) {
        val operation =
            runCatching { strictJson.decodeFromString(PlcUnsignedOperation.serializer(), unsignedOperationJson) }
                .getOrElse { refuse("the operation could not be read", it) }
        val head = head(history)
        when {
            !PlcEncoding.encodeUnsigned(operation).contentEquals(payload) -> refuse("the bytes to sign are not the operation shown")

            operation.type != PlcOperation.TYPE -> refuse("the operation is a ${operation.type}")

            operation.prev != head.cid -> refuse("the operation does not follow the identity's latest change")

            operation.rotationKeys != listOf(recoveryDidKey, nextKey) -> refuse("the operation changes the rotation keys")

            operation.verificationMethods !=
                mapOf(
                    ATPROTO_VERIFICATION_METHOD to nextKey,
                )
            -> refuse("the operation changes the signing key")

            operation.alsoKnownAs != head.operation.alsoKnownAs -> refuse("the operation changes the handle")

            operation.alsoKnownAs != listOf("at://$handle") -> refuse("the operation names another handle")

            operation.services != head.operation.services -> refuse("the operation changes the services")
        }
    }

    private fun head(history: List<HostedPlcOperationDataDto>): Link {
        val links =
            history.map { row ->
                val operation =
                    runCatching { lenientJson.decodeFromString(PlcOperation.serializer(), row.operationJson) }
                        .getOrElse { refuse("the identity's history could not be read", it) }
                Link(cid = row.cid ?: PlcEncoding.operationCid(operation), operation = operation)
            }
        val followers = links.groupBy { it.operation.prev }
        var current = followers[null]?.singleOrNull() ?: refuse("the identity's history has no single start")
        repeat(links.size) {
            val next = followers[current.cid] ?: return current
            current = next.singleOrNull() ?: refuse("the identity's history forks")
        }
        refuse("the identity's history loops")
    }

    private fun refuse(
        reason: String,
        cause: Throwable? = null,
    ): Nothing = throw IdentityRecoveryException(IdentityRecoveryFailure.UNEXPECTED_CHANGE, "Refused to sign: $reason", cause)

    private class Link(
        val cid: String,
        val operation: PlcOperation,
    )

    private const val ATPROTO_VERIFICATION_METHOD = "atproto"
}
