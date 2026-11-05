package app.logdate.server.logdate

import io.github.aakira.napier.Napier
import kotlin.coroutines.cancellation.CancellationException

/** An absent object completes a durable deletion retry; a provider failure keeps its claim. */
internal fun LogDateBlobStorage.deleteLegacyObject(path: String): Boolean =
    try {
        deleteBlobIfExists(path)
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        Napier.w("Object deletion was not confirmed")
        false
    }
