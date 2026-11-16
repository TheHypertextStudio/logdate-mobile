package app.logdate.server.billing

import app.logdate.server.crypto.lowercaseHex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies a Stripe webhook signature without pulling in the full Stripe SDK.
 *
 * Stripe's docs specify the wire format: the `Stripe-Signature` header is a comma-separated list
 * of `key=value` pairs, where `t=<unix-seconds>` and `v1=<hex-HMAC-SHA256>` are the ones we care
 * about. The expected signature is `HMAC-SHA256(secret, "<t>.<rawBody>")`, and Stripe may include
 * multiple `v1` values (for key rotation) — any of them validating is enough.
 *
 * Rejecting a request when signing fails is the whole point of the webhook endpoint: without this
 * check any unauthenticated caller could forge "user X upgraded to Pro" events.
 */
class StripeSignatureVerifier(
    private val secret: String,
    private val toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
) {
    sealed class Verification {
        data object Ok : Verification()

        data class Rejected(
            val reason: String,
        ) : Verification()
    }

    fun verify(
        rawBody: String,
        signatureHeader: String?,
        nowEpochSeconds: Long,
    ): Verification {
        if (signatureHeader.isNullOrBlank()) return Verification.Rejected("Missing Stripe-Signature header")
        val parsed = parseHeader(signatureHeader)
        val timestamp =
            parsed["t"]?.toLongOrNull()
                ?: return Verification.Rejected("Missing or malformed t= in Stripe-Signature")
        val candidates = parsed.filterKeys { it == "v1" }.values.toList()
        if (candidates.isEmpty()) return Verification.Rejected("No v1 signature entries found")

        if (kotlin.math.abs(nowEpochSeconds - timestamp) > toleranceSeconds) {
            return Verification.Rejected("Timestamp outside tolerance window")
        }

        val expected = computeSignature(secret, timestamp, rawBody)
        val matched = candidates.any { constantTimeEquals(expected, it) }
        return if (matched) Verification.Ok else Verification.Rejected("Signature mismatch")
    }

    private fun parseHeader(raw: String): Map<String, String> =
        raw
            .split(',')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0 || eq == pair.length - 1) {
                    null
                } else {
                    pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
                }
            }.toMap()

    private fun computeSignature(
        secret: String,
        timestamp: Long,
        rawBody: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = "$timestamp.$rawBody"
        val bytes = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return lowercaseHex(bytes)
    }

    /** Constant-time string compare — signature equality comparisons must not leak timing. */
    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    companion object {
        const val DEFAULT_TOLERANCE_SECONDS: Long = 5 * 60
    }
}
