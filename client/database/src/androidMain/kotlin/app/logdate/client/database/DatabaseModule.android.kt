package app.logdate.client.database

import app.logdate.client.database.encryption.DatabasePassphraseProvider
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.device.restore.NativeJournalRestoreAuthority
import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import app.logdate.client.device.restore.NativeRestorePrivacyGuard
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.resumeNativePrivacyRepairUnderDeliveryLock
import app.logdate.client.device.restore.retainedJournalPassphraseFile
import app.logdate.client.device.restore.retainedJournalWorkingKeyName
import app.logdate.client.device.restore.retainedJournalWorkspace
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val databaseModule: Module =
    module {
        single { PassphraseBackupStore(androidContext()) { retainedJournalPassphraseFile(androidContext()) } }
        single {
            DatabasePassphraseProvider(get(), get(), storageKey = { retainedJournalWorkingKeyName(androidContext()) }) {
                val context = androidContext()
                retainedDatabaseNeedsExistingKey(
                    context.getDatabasePath(DATABASE_NAME),
                    NativeJournalRestoreAuthority(context).isSnapshotRequired(),
                )
            }
        }
        single { DatabaseStartupMonitor() }
        single {
            val context = androidContext()
            val startupMonitor: DatabaseStartupMonitor = get()

            Napier.i("Startup: encrypted database initialization starting")

            runCatching {
                runBlocking {
                    openDatabaseAfterNativeRestoreCheck(
                        deliveryGate = get<NativeRestoreDeliveryGate>(),
                        restoreInProgress = { !PostRestoreDetector(context).isNativeRestoreDeliveryComplete() },
                    ) {
                        runBlocking { resumeNativePrivacyRepairUnderDeliveryLock(context) }
                        val workspace = retainedJournalWorkspace(context).resume()
                        if (workspace != null && !context.getDatabasePath(DATABASE_NAME).exists()) {
                            PostRestoreDetector(context).recordRetainedJournalRecovery(workspace.id)
                        }
                        val passphraseProvider = get<DatabasePassphraseProvider>()
                        val candidates = runBlocking { passphraseProvider.getPassphraseCandidates() }
                        runBlocking { verifyNativePrivacyBeforeDatabaseOpen(context, candidates, get<NativeRestorePrivacyGuard>()) }
                        migratePlaintextDatabaseIfNeeded(context, candidates.first())
                        val opened =
                            openDatabaseWithPassphraseCandidates(candidates) { passphrase ->
                                val database =
                                    getRoomDatabase(
                                        getDatabaseBuilder(context, passphrase),
                                        driver = null,
                                    )
                                try {
                                    database.openHelper.writableDatabase
                                    protectDatabaseFile(context)
                                    database
                                } catch (error: Exception) {
                                    database.close()
                                    throw error
                                }
                            }
                        try {
                            runBlocking { passphraseProvider.confirmOpenedDatabasePassphrase(opened.passphrase) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // An accepted key remains in at least one source. Startup can still open
                            // the journal and retry portable-key synchronization on its next launch.
                            Napier.w("Portable database recovery material could not be refreshed; will retry on next launch")
                        }
                        try {
                            runBlocking {
                                get<NativeRestorePrivacyGuard>().verifyBeforeContent {
                                    opened.database.integrityDao().hasRetainedJournalContent()
                                }
                                retainedJournalWorkspace(context).recordWorkingJournalOpened()
                            }
                        } catch (error: Exception) {
                            opened.database.close()
                            throw error
                        }
                        startupMonitor.markReady()
                        Napier.i("Startup: encrypted database opened and protections applied")
                        opened.database
                    }
                }
            }.onFailure { error ->
                startupMonitor.markRecoveryRequired(error)
                Napier.e("Startup: encrypted database unavailable; journal access is paused", error)
            }.getOrThrow()
        }
        includes(daosModule)
    }

internal fun retainedDatabaseNeedsExistingKey(
    file: java.io.File,
    nativeSnapshotRequired: Boolean = false,
): Boolean = nativeSnapshotRequired || (file.exists() && file.length() > 0L && !isPlaintextSqliteDatabase(file))
