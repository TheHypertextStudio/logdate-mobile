package app.logdate.server.crypto

import java.security.MessageDigest
import java.util.HexFormat

private val LOWERCASE_HEX: HexFormat = HexFormat.of()

/** The SHA-256 digest of [bytes]. */
internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

/** The SHA-256 digest of [text] as UTF-8. */
internal fun sha256(text: String): ByteArray = sha256(text.encodeToByteArray())

/** The SHA-256 digest of [bytes] in lowercase hex. */
internal fun sha256Hex(bytes: ByteArray): String = lowercaseHex(sha256(bytes))

/** The SHA-256 digest of [text] as UTF-8, in lowercase hex. */
internal fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())

/** [bytes] in lowercase hex, two digits a byte. */
internal fun lowercaseHex(bytes: ByteArray): String = LOWERCASE_HEX.formatHex(bytes)
