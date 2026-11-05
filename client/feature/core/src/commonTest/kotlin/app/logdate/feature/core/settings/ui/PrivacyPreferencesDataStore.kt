package app.logdate.feature.core.settings.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.logdate.client.datastore.LogdatePreferencesDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PrivacyPreferencesDataStore(
    val readGate: CompletableDeferred<Unit>? = null,
) : DataStore<Preferences> {
    private var persisted: Preferences = mutablePreferencesOf()
    private val state = MutableStateFlow(Result.success(persisted))
    private val mutex = Mutex()
    var writes = 0
    var reads = 0
    var readFailure = false
    var nextReadGate: CompletableDeferred<Unit>? = null
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    override val data: Flow<Preferences> =
        flow {
            reads++
            readGate?.await()
            nextReadGate?.await()
            if (readFailure) error("private storage detail")
            state.value = Result.success(persisted)
            emitAll(state.map { it.getOrThrow() })
        }

    fun failObservation() {
        state.value = Result.failure(IllegalStateException("private observation detail"))
    }

    fun publishSearchVisibility(enabled: Boolean) {
        persisted = mutablePreferencesOf(LogdatePreferencesDataSource.SYSTEM_SEARCH_VISIBILITY_ENABLED to enabled)
        state.value = Result.success(persisted)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            writes++
            beforeWrite()
            transform(persisted).also {
                persisted = it
                state.value = Result.success(it)
                afterWrite()
            }
        }
}
