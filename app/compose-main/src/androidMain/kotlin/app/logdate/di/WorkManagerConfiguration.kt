package app.logdate.di

import android.content.Context
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import org.koin.androidx.workmanager.factory.KoinWorkerFactory

internal fun logDateWorkManagerConfiguration(): Configuration =
    Configuration
        .Builder()
        .setWorkerFactory(DelegatingWorkerFactory().apply { addFactory(KoinWorkerFactory()) })
        // Restored workers can synchronously wait for encrypted storage in Koin.
        // Leave the CPU dispatcher available to complete configuration/DataStore startup.
        .setWorkerCoroutineContext(Dispatchers.IO)
        .build()

internal fun initializeLogDateWorkManager(context: Context) {
    if (!WorkManager.isInitialized()) {
        WorkManager.initialize(context, logDateWorkManagerConfiguration())
    }
}
