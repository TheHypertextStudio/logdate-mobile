package app.logdate.server.atproto

import app.logdate.server.crypto.sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Historical synthetic CID helper kept for regression tests around deterministic encoding.
 */
private object SyntheticCid {
    private val json = Json { explicitNulls = true }
    private val base32Alphabet = "abcdefghijklmnopqrstuvwxyz234567".toCharArray()

    fun fromRecord(
        uri: String,
        value: JsonObject,
    ): String {
        val payload = "$uri\n${json.encodeToString(JsonObject.serializer(), value)}".encodeToByteArray()
        val digest = sha256(payload)
        val cidBytes =
            encodeVarint(CID_VERSION) + encodeVarint(RAW_CODEC) + byteArrayOf(SHA256_CODE.toByte(), SHA256_SIZE.toByte()) + digest
        return "b${encodeBase32(cidBytes)}"
    }

    private fun encodeVarint(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) {
                next = next or 0x80
            }
            bytes += next.toByte()
        } while (remaining != 0)
        return bytes.toByteArray()
    }

    private fun encodeBase32(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return ""
        }

        val output = StringBuilder(((bytes.size * 8) + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                output.append(base32Alphabet[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            output.append(base32Alphabet[(buffer shl (5 - bitsLeft)) and 0x1f])
        }
        return output.toString()
    }

    private const val CID_VERSION = 1
    private const val RAW_CODEC = 0x55
    private const val SHA256_CODE = 0x12
    private const val SHA256_SIZE = 32
}
