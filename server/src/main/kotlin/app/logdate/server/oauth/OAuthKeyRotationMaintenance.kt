package app.logdate.server.oauth

import app.logdate.server.logging.KeyCustodyNotices
import io.github.aakira.napier.Napier
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private val ROTATION_CHECK_INTERVAL = 5.minutes

/**
 * Moves OAuth signing key rotation forward on a schedule, see [OAuthKeyService.rotateIfDue], and
 * forgets expired single-use values such as DPoP proof IDs, see [OAuthReplayStore.purgeExpired].
 */
internal fun Application.installOAuthKeyRotation() {
    val keyService by inject<OAuthKeyService>()
    val replayStore by inject<OAuthReplayStore>()
    val job =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                delay(ROTATION_CHECK_INTERVAL)
                try {
                    replayStore.purgeExpired(Clock.System.now())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Napier.e("Could not remove expired OAuth single-use values", exception)
                }
                try {
                    when (keyService.rotateIfDue()) {
                        OAuthKeyRotationStep.STAGED -> Napier.i(KeyCustodyNotices.OAUTH_KEY_STAGED)
                        OAuthKeyRotationStep.PROMOTED -> Napier.i(KeyCustodyNotices.OAUTH_KEY_PROMOTED)
                        OAuthKeyRotationStep.RETIRED -> Napier.i(KeyCustodyNotices.OAUTH_KEY_RETIRED)
                        OAuthKeyRotationStep.NONE -> Unit
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Napier.e(KeyCustodyNotices.OAUTH_KEY_ROTATION_FAILED, exception)
                }
            }
        }
    monitor.subscribe(ApplicationStopped) { job.cancel() }
}
