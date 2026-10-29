package app.logdate.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Where the account's AT Protocol identity is hosted. Signing in never moves its repository. */
@Serializable(with = AccountIdentityKindSerializer::class)
enum class AccountIdentityKind(
    val wireValue: String,
) {
    HOSTED("hosted"),
    EXTERNAL("external"),
    UNRECOGNIZED("unrecognized"),
    ;

    companion object {
        fun fromWireValue(value: String): AccountIdentityKind =
            entries.firstOrNull { it != UNRECOGNIZED && it.wireValue == value } ?: UNRECOGNIZED
    }
}

/** Unknown kinds remain readable, but cannot enter the hosted identity lifecycle. */
object AccountIdentityKindSerializer : KSerializer<AccountIdentityKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.logdate.shared.model.AccountIdentityKind", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: AccountIdentityKind,
    ) = encoder.encodeString(value.wireValue)

    override fun deserialize(decoder: Decoder): AccountIdentityKind = AccountIdentityKind.fromWireValue(decoder.decodeString())
}
