package app.logdate.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// TODO: Remove once Uuid can be serialized

/**
 * A custom serializer for [Uuid] objects.
 *
 * This should be used in place of the default serializer for [Uuid] objects. For example:
 *
 * ```kotlin
 * @Serializable
 * data class MyData(
 *    @Serializable(with = UuidSerializer::class)
 *    val id: Uuid
 *    // ...
 * )
 * ```
 */
@OptIn(ExperimentalUuidApi::class)
object UuidSerializer : KSerializer<Uuid> {
    override val descriptor = PrimitiveSerialDescriptor("Uuid", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Uuid,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Uuid = Uuid.parse(decoder.decodeString())
}
