package app.logdate.server.atproto

import java.security.MessageDigest
import java.security.SecureRandom

/** A generated credential for a single third-party app. Its value is returned once. */
internal class AtprotoAppPassword private constructor(
    val value: String,
    val verifier: String,
) {
    override fun toString(): String = "AtprotoAppPassword([redacted])"

    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
        private val format = Regex("[a-z2-7]{4}(-[a-z2-7]{4}){3}")

        fun generate(random: SecureRandom = SecureRandom()): AtprotoAppPassword {
            val value =
                buildString(16) {
                    repeat(16) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
                }.chunked(4).joinToString("-")
            return AtprotoAppPassword(value, digest(value))
        }

        fun verifier(value: String): String? = value.takeIf(format::matches)?.let(::digest)

        // The input is generated uniformly with 80 bits of entropy and never comes from a person.
        private fun digest(value: String): String =
            java.util.HexFormat
                .of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
    }
}
