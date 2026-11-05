package app.logdate.server.identity

import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcUnsignedOperation

/**
 * Raised when the PLC directory accepted an operation but its audit log does not show it, so the
 * outcome is unknown. Checking the identity's status again shows whether it arrived.
 */
class PlcPublicationUnconfirmedException(
    message: String,
) : IllegalStateException(message)

/**
 * Signed hosted PLC identity material.
 */
data class ProvisionedPlcIdentity(
    val did: String,
    val publicKeyMultibase: String,
    val publicKeyDidKey: String,
    val operation: PlcOperation,
    val handle: String,
)

/**
 * Signed hosted PLC rotation material after a successful update operation.
 */
data class RotatedPlcIdentity(
    val did: String,
    val previousPublicKeyMultibase: String,
    val publicKeyMultibase: String,
    val publicKeyDidKey: String,
    val operation: PlcOperation,
)

/**
 * Hosted PLC recovery-key update result.
 */
data class UpdatedPlcRecoveryKey(
    val did: String,
    val recoveryDidKey: String,
    val operation: PlcOperation,
)

/**
 * Unsigned hosted PLC recovery import payload that a first-party client can sign locally.
 */
data class PreparedPlcRecoveryOperation(
    val did: String,
    val recoveryDidKey: String,
    val nextSigningKeyDidKey: String,
    val unsignedOperation: PlcUnsignedOperation,
    val signingPayload: ByteArray,
)

/** The newest operation in an identity's history and the CID the next operation names as `prev`. */
data class PlcOperationHead(
    val cid: String,
    val operation: PlcOperation,
)
