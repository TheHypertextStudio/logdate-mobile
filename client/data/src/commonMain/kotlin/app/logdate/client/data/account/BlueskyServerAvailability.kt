package app.logdate.client.data.account

import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.ServerDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class BlueskyServerAvailability(
    private val config: LogDateConfigRepository,
    private val enabled: suspend () -> Boolean,
    private val discover: suspend (String) -> Result<ServerDescriptor>,
) {
    private val mutex = Mutex()

    suspend fun prepare(): Boolean =
        mutex.withLock {
            if (!enabled()) return@withLock false
            val origin = config.getCurrentBackendUrl()
            if (!Regex("https://[^/?#@\\s]+").matches(origin)) return@withLock false
            if (config.getCurrentServerDescriptor() == null) {
                val descriptor =
                    try {
                        discover(origin).getOrNull()
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        null
                    } ?: return@withLock false
                if (config.getCurrentBackendUrl() != origin || !enabled()) return@withLock false
                if (descriptor.serverOrigin != origin || descriptor.apiBaseUrl != "$origin/api/v1") return@withLock false
                if (config.getCurrentServerDescriptor() == null) config.updateServerDescriptor(descriptor)
            }
            config.getCurrentBackendUrl() == origin &&
                blueskySignInAvailable(enabled(), origin, config.getCurrentServerDescriptor())
        }
}
