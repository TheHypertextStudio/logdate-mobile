package app.logdate.client.sync

import android.content.Context
import app.logdate.client.LogDateApplication
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Waits for durable storage before resolving watch work; retries early Koin registration races. */
internal suspend fun resolvePhoneServiceKoin(): org.koin.core.Koin? {
    repeat(3) { attempt ->
        try {
            val koin =
                org.koin.java.KoinJavaComponent
                    .getKoin()
            val application = koin.get<Context>().applicationContext as? LogDateApplication
            if (application != null && !application.startup.awaitReady()) return null
            return koin
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (attempt < 3 - 1) {
                Napier.d("Koin not ready (attempt ${attempt + 1}/$3), retrying...")
                delay(500L)
            }
        }
    }
    return null
}
