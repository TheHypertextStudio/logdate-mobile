package studio.hypertext.atproto.identity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.syntax.Handle

/**
 * AT Protocol DID document.
 *
 * @property context DID JSON-LD context values.
 * @property id DID the document describes.
 * @property alsoKnownAs Alternate names associated with the identity.
 * @property verificationMethod Verification methods published by the DID document.
 * @property service Service endpoints published by the DID document.
 */
@Serializable public data class DidDocument(
    @SerialName("@context")
    val context: List<String> = listOf("https://www.w3.org/ns/did/v1"),
    val id: AtprotoDid,
    val alsoKnownAs: List<String> = emptyList(),
    val verificationMethod: List<VerificationMethod> = emptyList(),
    val service: List<Service> = emptyList(),
)

/** The first syntactically valid at:// handle is the document's claimed handle. */
public fun DidDocument.claimedHandle(): Handle? =
    alsoKnownAs.firstNotNullOfOrNull { alias ->
        if (alias.startsWith("at://", ignoreCase = true)) Handle.parse(alias.substring(5)).getOrNull() else null
    }

/**
 * DID verification method entry.
 *
 * @property id Verification method identifier.
 * @property type Verification method type.
 * @property controller DID that controls the verification method.
 * @property publicKeyMultibase Optional multibase-encoded public key value.
 */
@Serializable public data class VerificationMethod(
    val id: String,
    val type: String,
    val controller: AtprotoDid,
    val publicKeyMultibase: String? = null,
)

/**
 * DID service entry.
 *
 * @property id Service identifier.
 * @property type Service type.
 * @property serviceEndpoint Service endpoint URL.
 */
@Serializable public data class Service(
    val id: String,
    val type: String,
    val serviceEndpoint: String,
)
