package app.logdate.server.oauth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock

/** Issues the same short-lived DPoP challenges on replicas sharing a server secret. */
class OAuthNonceService(
    private val clock: Clock = Clock.System,
    sharedSecret: String? = null,
) {
    private val key =
        SecretKeySpec(
            sharedSecret?.encodeToByteArray() ?: ByteArray(32).also(SecureRandom()::nextBytes),
            HMAC_ALGORITHM,
        )

    fun currentNonce(): String = nonceFor(currentWindow())

    fun challenge(): OAuthNonceChallenge {
        val window = currentWindow()
        return OAuthNonceChallenge(current = nonceFor(window), previous = nonceFor(window - 1))
    }

    private fun currentWindow(): Long = Math.floorDiv(clock.now().epochSeconds, WINDOW_SECONDS)

    private fun nonceFor(window: Long): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        val nonce = mac.doFinal("logdate/atproto/oauth/dpop/nonce/v1/$window".encodeToByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
    }

    private companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"

        // The current and previous windows together keep every challenge valid for at most five minutes.
        private const val WINDOW_SECONDS = 150L
    }
}

class OAuthNonceChallenge internal constructor(
    val current: String,
    private val previous: String? = null,
) {
    internal fun accepts(nonce: String?): Boolean = nonce != null && (nonce == current || nonce == previous)
}
