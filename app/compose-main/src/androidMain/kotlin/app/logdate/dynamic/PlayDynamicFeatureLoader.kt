package app.logdate.dynamic

import android.content.Context
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Production implementation of [DynamicFeatureLoader] backed by Google Play Feature Delivery.
 */
class PlayDynamicFeatureLoader(
    context: Context,
) : DynamicFeatureLoader {
    private val splitInstallManager = SplitInstallManagerFactory.create(context)

    override fun isInstalled(moduleName: String): Boolean = splitInstallManager.installedModules.contains(moduleName)

    override suspend fun requestInstall(moduleName: String): DynamicInstallResult {
        if (isInstalled(moduleName)) {
            return DynamicInstallResult.AlreadyInstalled
        }

        return try {
            val request =
                SplitInstallRequest
                    .newBuilder()
                    .addModule(moduleName)
                    .build()
            splitInstallManager.startInstall(request).await()
            Napier.i("Dynamic module '$moduleName' install request succeeded")
            DynamicInstallResult.Success
        } catch (e: Exception) {
            Napier.e("Failed to install dynamic module '$moduleName'", e)
            DynamicInstallResult.Failed(e)
        }
    }

    override fun observeInstallState(moduleName: String): Flow<DynamicInstallState> =
        callbackFlow {
            if (isInstalled(moduleName)) {
                trySend(DynamicInstallState.Installed)
                close()
                return@callbackFlow
            }

            val listener =
                SplitInstallStateUpdatedListener { state ->
                    if (moduleName !in state.moduleNames()) return@SplitInstallStateUpdatedListener

                    val mapped =
                        when (state.status()) {
                            SplitInstallSessionStatus.PENDING,
                            SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION,
                            -> {
                                DynamicInstallState.NotInstalled
                            }

                            SplitInstallSessionStatus.DOWNLOADING -> {
                                val total = state.totalBytesToDownload()
                                val downloaded = state.bytesDownloaded()
                                val progress = if (total > 0) downloaded.toFloat() / total else 0f
                                DynamicInstallState.Downloading(progress)
                            }

                            SplitInstallSessionStatus.INSTALLING -> {
                                DynamicInstallState.Downloading(1f)
                            }

                            SplitInstallSessionStatus.INSTALLED -> {
                                DynamicInstallState.Installed
                            }

                            SplitInstallSessionStatus.FAILED,
                            SplitInstallSessionStatus.CANCELED,
                            -> {
                                DynamicInstallState.Failed(
                                    RuntimeException(
                                        "Install of '$moduleName' failed with status ${state.status()}, error ${state.errorCode()}",
                                    ),
                                )
                            }

                            else -> {
                                return@SplitInstallStateUpdatedListener
                            }
                        }

                    trySend(mapped)
                    if (mapped is DynamicInstallState.Installed || mapped is DynamicInstallState.Failed) {
                        close()
                    }
                }

            splitInstallManager.registerListener(listener)
            trySend(DynamicInstallState.NotInstalled)

            awaitClose {
                splitInstallManager.unregisterListener(listener)
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T> loadProvider(providerClassName: String): T? =
        try {
            val clazz = Class.forName(providerClassName)
            val instance = clazz.getDeclaredField("INSTANCE").get(null)
            instance as? T
        } catch (e: ClassNotFoundException) {
            Napier.w("Provider class '$providerClassName' not found — module may not be installed")
            null
        } catch (e: Exception) {
            Napier.e("Failed to load provider '$providerClassName'", e)
            null
        }
}
