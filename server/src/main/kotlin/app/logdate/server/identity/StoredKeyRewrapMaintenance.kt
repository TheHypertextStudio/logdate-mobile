package app.logdate.server.identity

import app.logdate.server.config.AtprotoSigningKeyKek
import app.logdate.server.config.AtprotoSigningKeyKekDecryptOnly
import app.logdate.server.logging.KeyCustodyNotices
import app.logdate.server.oauth.OAuthSigningKeyRepository
import io.github.aakira.napier.Napier
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.minutes

/**
 * How long after startup stored keys are re-encrypted. An instance of the previous release can't
 * read the current format, so the upgrade waits until a rollout has replaced those instances.
 */
private val REWRAP_DELAY = 15.minutes

/**
 * Re-encrypts every stored signing key under [AtprotoSigningKeyKek] in the current format once,
 * [REWRAP_DELAY] after startup, reading keys still encrypted with [AtprotoSigningKeyKekDecryptOnly]
 * while it is set, and logs the outcome as one of the [KeyCustodyNotices]. See
 * server/docs/environment-variables.md for the order in which a deployment changes the two secrets.
 */
internal fun Application.installStoredKeyRewrap() {
    val signingKeys by inject<SigningKeyRepository>()
    val oauthSigningKeys by inject<OAuthSigningKeyRepository>()
    val rewrap =
        StoredKeyRewrap(
            stores = mapOf(AtRestKeyContext.SIGNING_KEYS to signingKeys, AtRestKeyContext.OAUTH_SIGNING_KEYS to oauthSigningKeys),
            cipher = AtRestKeyCipher(AtprotoSigningKeyKek.resolve(), listOfNotNull(AtprotoSigningKeyKekDecryptOnly.resolve())),
        )
    val job =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(REWRAP_DELAY)
            try {
                val report = rewrap.run()
                when {
                    report.unreadable.isNotEmpty() -> Napier.e(KeyCustodyNotices.SIGNING_KEYS_UNREADABLE)
                    report.rewrapped > 0 -> Napier.i(KeyCustodyNotices.SIGNING_KEYS_REWRAPPED)
                    else -> Napier.i(KeyCustodyNotices.SIGNING_KEYS_CURRENT)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Napier.e(KeyCustodyNotices.SIGNING_KEYS_REWRAP_FAILED, exception)
            }
        }
    monitor.subscribe(ApplicationStopped) { job.cancel() }
}
