package app.logdate.space

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import studio.hypertext.atproto.repo.Cid
import kotlin.io.encoding.Base64

/** Bounds encoding before the general codec can allocate an aggregate output or recurse too deeply. */
internal object SealedPayloadSizing {
    fun check(value: JsonElement) {
        size(value, 0)
    }

    private fun size(
        value: JsonElement,
        depth: Int,
    ): Long {
        if (depth > SealedPayloadCbor.MAX_DEPTH) fail()
        return when (value) {
            JsonNull -> 1L
            is JsonPrimitive -> primitiveSize(value)
            is JsonArray -> value.fold(head(value.size.toULong())) { total, child -> add(total, size(child, depth + 1)) }
            is JsonObject -> objectSize(value, depth)
        }
    }

    private fun primitiveSize(value: JsonPrimitive): Long {
        if (value.isString) return textSize(value.content)
        if (value.booleanOrNull != null) return 1
        val integer = value.longOrNull ?: fail()
        return head((if (integer < 0) -(integer + 1) else integer).toULong())
    }

    private fun objectSize(
        value: JsonObject,
        depth: Int,
    ): Long {
        if (value.size == 1 && "\$bytes" in value) {
            val encoded = string(value.getValue("\$bytes"))
            val bytes = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(encoded)
            return add(head(bytes.size.toULong()), bytes.size.toLong())
        }
        if (value.size == 1 && "\$link" in value) {
            val encoded = string(value.getValue("\$link"))
            val cid = Cid.require(encoded)
            val bytes = cid.toBytes()
            if (cid.value != encoded || Cid.fromBytes(bytes).value != encoded) fail()
            val count = bytes.size.toLong() + 1
            return add(2 + head(count.toULong()), count)
        }
        return value.entries.fold(head(value.size.toULong())) { total, entry ->
            add(add(total, textSize(entry.key)), size(entry.value, depth + 1))
        }
    }

    private fun string(value: JsonElement): String {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
        if (text.length > SealedPadding.MAX_CIPHERTEXT * 2) fail()
        return text
    }

    private fun textSize(text: String): Long {
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        return add(head(bytes.size.toULong()), bytes.size.toLong())
    }

    private fun head(value: ULong): Long =
        when {
            value < 24uL -> 1
            value <= 255uL -> 2
            value <= 65535uL -> 3
            value <= 0xffffffffuL -> 5
            else -> 9
        }

    private fun add(
        left: Long,
        right: Long,
    ): Long {
        if (right > SealedPadding.MAX_CIPHERTEXT - left) fail()
        return left + right
    }

    private fun fail(): Nothing = throw SealedRecordException("The sealed payload is unsupported")
}
