package app.logdate.server.bluesky

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.syntax.Handle
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

internal interface BlueskySignInProvider {
    suspend fun authorizeDid(
        did: AtprotoDid,
        attemptId: String,
    ): String = error("Reconnection is unavailable")

    suspend fun authorize(
        handle: Handle,
        attemptId: String,
    ): String

    suspend fun callback(
        parameters: Map<String, String>,
        acceptIdentity: suspend (VerifiedBlueskyCallback) -> Unit,
    )
}

internal data class VerifiedBlueskyCallback(
    val did: AtprotoDid,
    val attemptId: String?,
)

internal data class BlueskySignInStart(
    val attemptId: String,
    val authorizationUrl: String,
) {
    override fun toString(): String = "BlueskySignInStart()"
}

@Serializable
internal enum class BlueskySignInFailureKind { DECLINED, EXPIRED, UNAVAILABLE }

internal class BlueskySignInFailure(
    val kind: BlueskySignInFailureKind,
    verifiedAttemptId: String? = null,
) : IllegalStateException(
        when (kind) {
            BlueskySignInFailureKind.DECLINED -> "Bluesky sign-in was canceled"
            BlueskySignInFailureKind.EXPIRED -> "This sign-in request has expired"
            BlueskySignInFailureKind.UNAVAILABLE -> "Bluesky sign-in is unavailable"
        },
    ) {
    val verifiedAttemptId: String? = verifiedAttemptId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }
}

/** Provider authentication returns an app-bound identity; enrollment still requires a passkey. */
internal class BlueskySignInService(
    private val provider: BlueskySignInProvider,
    private val attempts: BlueskySignInAttemptStore,
    private val clock: Clock = Clock.System,
) {
    private val identifier = Regex("[A-Za-z0-9_-]{43}")

    suspend fun start(
        handle: Handle,
        challenge: String,
    ): BlueskySignInStart {
        require(identifier.matches(challenge)) { "The sign-in proof is invalid" }
        return available {
            val id = Base64.getUrlEncoder().withoutPadding().encodeToString(JvmOAuthCrypto.randomBytes(32))
            attempts.create(id, challenge, clock.now() + 5.minutes)
            BlueskySignInStart(id, provider.authorize(handle, id))
        }
    }

    suspend fun callback(parameters: Map<String, String>): String =
        available {
            var accepted: String? = null
            try {
                provider.callback(parameters) { verified ->
                    check(accepted == null)
                    val id = verified.attemptId
                    if (id == null || !identifier.matches(id) || !attempts.complete(id, verified.did)) {
                        throw BlueskySignInFailure(BlueskySignInFailureKind.EXPIRED)
                    }
                    accepted = id
                }
            } catch (failure: BlueskySignInFailure) {
                val id = failure.verifiedAttemptId
                if (id != null) {
                    if (!attempts.fail(id, failure.kind)) throw BlueskySignInFailure(BlueskySignInFailureKind.EXPIRED)
                    throw BlueskySignInFailure(failure.kind, id)
                }
                throw failure
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                throw BlueskySignInFailure(BlueskySignInFailureKind.UNAVAILABLE, accepted)
            }
            checkNotNull(accepted)
        }

    suspend fun exchange(
        id: String,
        verifier: String,
    ): VerifiedBlueskySignIn? =
        available {
            attempts.consume(id, verifier)
        }

    private suspend fun <T> available(block: suspend () -> T): T =
        try {
            block()
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: BlueskySignInFailure) {
            // Cleanup may attach provider failures as suppressed errors; retain only the category.
            throw BlueskySignInFailure(failure.kind, failure.verifiedAttemptId)
        } catch (_: Exception) {
            // Provider responses can contain tokens or private keys. Keep only a bounded category.
            Napier.w("Bluesky sign-in could not be completed")
            throw BlueskySignInFailure(BlueskySignInFailureKind.UNAVAILABLE)
        }
}
