package app.logdate.server.openapi.schemadocs

import app.logdate.server.openapi.SchemaDoc
import app.logdate.server.openapi.schemadocs.AuthSchemaDocs.ISO_TIME
import app.logdate.server.openapi.schemadocs.AuthSchemaDocs.SUCCESS_FLAG

/** Identity and recovery schemas share the account API's existing registry. */
internal object AuthIdentitySchemaDocs {
    val docs: Map<String, SchemaDoc> =
        mapOf(
            // ---- Identities ---------------------------------------------------------------------
            "IdentityListResponse" to
                SchemaDoc(
                    "The account's linked sign-in identities.",
                    mapOf("success" to SUCCESS_FLAG, "data" to "One entry per identity."),
                ),
            "IdentityView" to
                SchemaDoc(
                    "One way the person can sign in.",
                    mapOf(
                        "provider" to "`passkey` or `google`.",
                        "providerSubject" to
                            "The provider's stable ID for this identity: a credential ID for passkeys, Google's subject for Google.",
                        "email" to "Email the identity carries, or `null`.",
                        "emailVerified" to "Whether that email was verified by the provider.",
                        "createdAt" to "When the identity was linked. $ISO_TIME",
                        "lastSignInAt" to "When it last signed in, or `null` if never. $ISO_TIME",
                    ),
                ),
            // ---- AT Protocol identity ---------------------------------------------------------
            "IdentityStatusResponse" to
                SchemaDoc("The account's AT Protocol identity.", mapOf("success" to SUCCESS_FLAG, "data" to "The identity.")),
            "IdentityStatusData" to
                SchemaDoc(
                    "An AT Protocol identity and the public half of its keys.",
                    mapOf(
                        "did" to "The decentralized identifier, `did:plc:…` or `did:web:…`.",
                        "handle" to "The human-readable handle that resolves to the DID.",
                        "signingKeyPublicMultibase" to "The current signing key's public half, multibase-encoded.",
                        "signingKeyDidKey" to "The same public key as a `did:key`.",
                        "plcRecoveryDidKey" to "The registered PLC recovery key as a `did:key`, or `null` if none.",
                        "plcOperationCount" to "How many PLC operations this server has published for the identity.",
                    ),
                ),
            "ExportSigningKeyRequest" to
                SchemaDoc(
                    "Asks for an encrypted export.",
                    mapOf("passphrase" to "Passphrase to encrypt the private key with. Not stored."),
                ),
            "ExportSigningKeyResponse" to
                SchemaDoc("An encrypted signing-key export.", mapOf("success" to SUCCESS_FLAG, "data" to "The export.")),
            "ExportSigningKeyData" to
                SchemaDoc(
                    "The identity the export belongs to and the export itself.",
                    mapOf("did" to "The identity's DID.", "handle" to "The identity's handle.", "exportedKey" to "The encrypted key."),
                ),
            "SigningKeyService.ExportedSigningKey" to
                SchemaDoc(
                    "A private signing key encrypted under a passphrase. Safe to store; useless without the passphrase.",
                    mapOf(
                        "algorithm" to "The key's curve, such as `secp256k1`.",
                        "publicKeyMultibase" to "The public half, multibase-encoded.",
                        "publicKeyDidKey" to "The public half as a `did:key`.",
                        "encryptedPrivateKey" to "The private key encrypted with AES-GCM, base64.",
                        "salt" to "Salt used to derive the encryption key from the passphrase, base64.",
                        "iv" to "AES-GCM initialization vector, base64.",
                        "kdf" to "Key-derivation function used, `PBKDF2WithHmacSHA256`.",
                        "iterations" to "PBKDF2 iteration count.",
                    ),
                ),
            "RotateSigningKeyRequest" to
                SchemaDoc("Asks for a rotation.", mapOf("passphrase" to "Passphrase to encrypt the new key's export with. Not stored.")),
            "RotateSigningKeyResponse" to
                SchemaDoc("The result of a rotation.", mapOf("success" to SUCCESS_FLAG, "data" to "The new key and what it replaced.")),
            "RotateSigningKeyData" to
                SchemaDoc(
                    "The new key's export and the key it replaced.",
                    mapOf(
                        "did" to "The identity's DID.",
                        "handle" to "The identity's handle.",
                        "previousPublicKeyDidKey" to "The public key that was in use before the rotation, as a `did:key`.",
                        "exportedKey" to "The new key, encrypted under the passphrase.",
                    ),
                ),
            "ImportSigningKeyRequest" to
                SchemaDoc(
                    "An export to install.",
                    mapOf(
                        "passphrase" to "The passphrase the export was encrypted with.",
                        "exportedKey" to "The export from **Export the signing key**.",
                    ),
                ),
            "ImportSigningKeyResponse" to
                SchemaDoc("The result of an import.", mapOf("success" to SUCCESS_FLAG, "data" to "The installed key.")),
            "ImportSigningKeyData" to
                SchemaDoc(
                    "The identity and the key now in use.",
                    mapOf(
                        "did" to "The identity's DID.",
                        "handle" to "The identity's handle.",
                        "publicKeyDidKey" to "The installed public key as a `did:key`.",
                    ),
                ),
            "PrepareRecoverySigningKeyImportRequest" to
                SchemaDoc(
                    "An export to install through the recovery flow.",
                    mapOf("passphrase" to "The passphrase the export was encrypted with.", "exportedKey" to "The export to install."),
                ),
            "PrepareRecoverySigningKeyImportResponse" to
                SchemaDoc("The operation to sign.", mapOf("success" to SUCCESS_FLAG, "data" to "What to sign, and with which key.")),
            "PrepareRecoverySigningKeyImportData" to
                SchemaDoc(
                    "An unsigned PLC operation and the bytes the recovery key must sign.",
                    mapOf(
                        "did" to "The identity's DID.",
                        "handle" to "The identity's handle.",
                        "recoveryDidKey" to "The registered recovery key that must produce the signature.",
                        "nextPublicKeyDidKey" to "The public key the operation will install.",
                        "unsignedOperationJson" to "The PLC operation as JSON, for inspection.",
                        "signingPayloadBase64Url" to "The exact bytes to sign with the recovery key, base64url.",
                    ),
                ),
            "CompleteRecoverySigningKeyImportRequest" to
                SchemaDoc(
                    "The signed recovery import.",
                    mapOf(
                        "passphrase" to "The same passphrase as in the prepare step.",
                        "exportedKey" to "The same export as in the prepare step.",
                        "signature" to "The recovery key's signature over `signingPayloadBase64Url`, base64url.",
                    ),
                ),
            "HostedPlcOperationsResponse" to
                SchemaDoc("The identity's published PLC history.", mapOf("success" to SUCCESS_FLAG, "data" to "Operations, oldest first.")),
            "HostedPlcOperationData" to
                SchemaDoc(
                    "One PLC operation this server published.",
                    mapOf(
                        "did" to "The identity the operation belongs to.",
                        "cid" to "Content identifier of the operation in the PLC directory, or `null` if not yet known.",
                        "prevCid" to "The operation this one builds on, or `null` for the first.",
                        "operationType" to "`plc_operation` for creations and key changes; `plc_tombstone` for deactivation.",
                        "operationJson" to "The operation as published, JSON-encoded.",
                        "createdAt" to "When the server published it. $ISO_TIME",
                    ),
                ),
            // ---- DID documents ---------------------------------------------------------------
            "DidDocument" to
                SchemaDoc(
                    "A W3C DID document: the public keys and services that speak for an identity.",
                    mapOf(
                        "@context" to "JSON-LD contexts; always includes `https://www.w3.org/ns/did/v1`.",
                        "id" to "The DID this document describes.",
                        "alsoKnownAs" to "Other names for the identity, such as `at://willie.logdate.app`.",
                        "verificationMethod" to "Public keys that can sign for the identity.",
                        "service" to "Services acting for the identity, such as its personal data server.",
                    ),
                ),
            "VerificationMethod" to
                SchemaDoc(
                    "One public key of a DID.",
                    mapOf(
                        "id" to "Key identifier, the DID plus a fragment such as `#atproto`.",
                        "type" to "Key format; `Multikey` for AT Protocol.",
                        "controller" to "The DID that controls the key.",
                        "publicKeyMultibase" to "The public key, multibase-encoded.",
                    ),
                ),
            "Service" to
                SchemaDoc(
                    "One service endpoint of a DID.",
                    mapOf(
                        "id" to "Service identifier, such as `#atproto_pds`.",
                        "type" to "Service type; `AtprotoPersonalDataServer` for the data server.",
                        "serviceEndpoint" to "The service's base URL.",
                    ),
                ),
        )
}
