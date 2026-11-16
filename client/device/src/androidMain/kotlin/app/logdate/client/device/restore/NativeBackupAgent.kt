package app.logdate.client.device.restore

import android.annotation.TargetApi
import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.app.backup.FullRestoreDataInput
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.datastore.featureflags.DataStoreFeatureFlagStore
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.device.crypto.AndroidCryptoManager
import app.logdate.client.device.crypto.AndroidIdentityKeyBackupStore
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.storage.createAndroidSecureStorage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** Records platform callbacks without opening the database or starting application services. */
open class NativeBackupAgent : BackupAgent() {
    protected open val retainedPreferenceNames: Set<String> = emptySet()
    private val nativeRestoreSession =
        NativeRestoreSessionBoundary {
            val detector = PostRestoreDetector(this)
            val interrupted = !detector.isNativeRestoreDeliveryComplete()
            detector.beginNativeRestoreSession(retainPreferences = retainedPreferenceNames.isNotEmpty())
            NativeJournalRestoreAuthority(this).begin(interrupted)
            retainOptionalPreferences { begin(retainedPreferenceNames) }
        }
    private var incomingGeneration: String? = null
    private val incomingRestoreSession =
        NativeRestoreSessionBoundary {
            incomingGeneration =
                kotlin.uuid.Uuid
                    .random()
                    .toString()
        }
    private val callbackLock = Any()
    private val completedReplay by lazy { NativeJournalReplayCustody(this) }
    private val completedRawReplay by lazy { NativeCompletedRestoreReplay(this, completedReplay) }
    private val privacyRepair by lazy { nativePrivacyRepairDelivery(this, ::inspectNativePrivacyRepairCopy) }
    private val restoreFormat = NativeRestoreFormatGuard { noBackupFilesDir }
    private var restoreCompletionRecorded = false
    private var installationStateReset = false

    override fun onCreate() {
        super.onCreate()
        runBlocking(Dispatchers.IO) { createAndroidInstallationWatchDataStore(this@NativeBackupAgent).data.first() }
    }

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) = Unit

    override fun onRestore(
        data: BackupDataInput,
        appVersionCode: Int,
        newState: ParcelFileDescriptor,
    ) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (data.transportFlags and FLAG_CROSS_PLATFORM_DATA_TRANSFER_IOS != 0) {
            val enabled =
                FeatureFlag.IOS_SYSTEM_TRANSFER.availableForLaunch &&
                    runBlocking(Dispatchers.IO) {
                        DataStoreFeatureFlagStore(createAndroidMainDataStore(this@NativeBackupAgent))
                            .isEnabled(FeatureFlag.IOS_SYSTEM_TRANSFER)
                    }
            requireNativeIosExport(data.transportFlags, enabled)
            runBlocking {
                AndroidNativeRestoreDeliveryGate.withLock {
                    requireRetainedJournalBackupReady(this@NativeBackupAgent, deviceTransfer = true)
                    backupNativeIosTransfer(data)
                }
            }
            return
        }
        requireNativeAndroidExport(data.transportFlags)
        runBlocking {
            AndroidNativeRestoreDeliveryGate.withLock {
                requireRetainedJournalBackupReady(
                    this@NativeBackupAgent,
                    deviceTransfer = data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0,
                )
                if (data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER == 0) requireCloudJournalRecoverability()
                try {
                    runBlocking {
                        IdentityKeyManager(
                            createAndroidSecureStorage(this@NativeBackupAgent),
                            AndroidCryptoManager(),
                            AndroidIdentityKeyBackupStore(this@NativeBackupAgent),
                        ).ensureExistingIdentityBackup()
                    }
                } catch (error: Exception) {
                    throw IOException("Native encryption recovery material could not be prepared", error)
                }
                // Backup may run before the first normal launch after an upgrade.
                if (PostRestoreDetector(
                        this@NativeBackupAgent,
                    ).hasMissingNativePrivacyPayload()
                ) {
                    throw NativeRestorePrivacyMetadataRequiredException()
                }
                runBlocking(Dispatchers.IO) {
                    NativeRestorePrivacyPolicy.prepareBackup(createAndroidMainDataStore(this@NativeBackupAgent)) { snapshot ->
                        canEstablishNativePrivacyDefaults(this@NativeBackupAgent, snapshot)
                    }
                }
                try {
                    if (data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0) prepareNativeJournalTransferBackup()
                    super.onFullBackup(data)
                    if (data.transportFlags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0) backupSelectedNativeMedia(data)
                } finally {
                    finishNativeJournalTransferBackup()
                }
            }
        }
    }

    override fun onRestoreFinished() {
        AndroidNativeRestoreDeliveryGate.beginRestoreCompletion()
        try {
            if (synchronized(callbackLock) { restoreFormat.isRejected() }) {
                super.onRestoreFinished()
                return
            }
            if (finishProtectedNativeDelivery()) {
                super.onRestoreFinished()
                return
            }
            if (completedReplay.hasJournalInputs()) {
                if (NativeJournalRestoreAuthority(this).completedReceipt() == null && !wasNativeJournalTransferStaged()) {
                    restoreNativeJournalTransferSnapshot()
                }
                if (wasNativeJournalTransferStaged()) {
                    finishStagedNativeJournalTransfer()
                    return
                }
                if (!completedReplay.finish()) scheduleRetainedJournalReconciliationSafely()
                super.onRestoreFinished()
                return
            }
            val duplicateCompletion =
                synchronized(callbackLock) {
                    if (restoreCompletionRecorded) {
                        true
                    } else if (!nativeRestoreSession.isStarted() && PostRestoreDetector(this).hasCompletedNativeRestoreDelivery()) {
                        restoreCompletionRecorded = true
                        true
                    } else {
                        beginNativeRestoreSessionOnce(resumeFinalization = true)
                        false
                    }
                }
            if (duplicateCompletion) {
                val retainedCameraSource = finishNativeJournalTransferRestore()
                super.onRestoreFinished()
                retryAccountRecovery()
                if ((retainedCameraSource || completedReplay.hasInputs()) && scheduleRetainedJournalReconciliationSafely()) {
                    retainedNativeCameraReconciliationScheduled()
                }
                return
            }
            val journalInstalled = restoreNativeJournalTransferSnapshot()
            if (wasNativeJournalTransferStaged()) {
                finishStagedNativeJournalTransfer()
                return
            }
            completeFreshNativeRestore(journalInstalled)
        } finally {
            nativeRestoreSession.finish()
            incomingRestoreSession.finish()
            AndroidNativeRestoreDeliveryGate.endRestore()
        }
    }

    private fun completeFreshNativeRestore(journalInstalled: Boolean) {
        if (journalInstalled) {
            PostRestoreDetector(this).markNativeRestorePayloadReceived(databaseDelivered = true)
            PostRestoreDetector(this).markNativePrivacyPayloadReceived()
        }
        super.onRestoreFinished()
        val finalDelivery = restoredPreferences().recordFinalDelivery(retainedPreferenceNames)
        retainOptionalPreferences { finish(retainedPreferenceNames, finalDelivery) }
        val detector = PostRestoreDetector(this)
        val preferences = createAndroidMainDataStore(this)
        val admission = detector.reserveAccountRecoveryForCompletion()
        runBlocking(Dispatchers.IO) {
            withTimeout(15_000) { retainAccountRecoveryBeforeCompletion(preferences, admission) }
        }
        detector.recordRestoreCompletedUnderDeliveryLock()
        val retainedCameraSource = finishNativeJournalTransferRestore()
        synchronized(callbackLock) { restoreCompletionRecorded = true }
        try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(15_000) {
                    retainAccountRecovery(preferences, freshDelivery = true)
                    prepareRestoredInstallationState(preferences)
                    restoreAccount(preferences)
                }
            }
        } catch (_: Exception) {
            Napier.w("Background native account recovery remains pending")
        }
        if ((retainedCameraSource || completedReplay.hasInputs()) && scheduleRetainedJournalReconciliationSafely()) {
            retainedNativeCameraReconciliationScheduled()
        }
    }

    private fun finishProtectedNativeDelivery(): Boolean {
        if (privacyRepair.finishIfRequired()) {
            if (completedReplay.hasInputs()) scheduleRetainedJournalReconciliationSafely()
            return true
        }
        finishCompletedNativeJournalCleanup()
        if (finishRetainedNativeDelivery(this, callbackLock)) {
            // A late delivery must not reset the accepted working installation or its account.
            scheduleRetainedJournalReconciliationSafely()
            return true
        }
        return completedRawReplay.finishIfRequired(::scheduleRetainedJournalReconciliationSafely)
    }

    private fun finishStagedNativeJournalTransfer() {
        super.onRestoreFinished()
        val detector = PostRestoreDetector(this)
        detector.markNativeRestorePayloadReceived(databaseDelivered = true)
        detector.markNativePrivacyPayloadReceived()
        detector.recordRestoreCompletedUnderDeliveryLock()
        synchronized(callbackLock) { restoreCompletionRecorded = true }
        scheduleRetainedJournalReconciliationSafely()
    }

    override fun onRestoreFile(
        data: ParcelFileDescriptor,
        size: Long,
        destination: File,
        type: Int,
        mode: Long,
        mtime: Long,
    ) {
        restoreNativeFile(destination, data, size, fileType = type) { descriptor, target ->
            super.onRestoreFile(descriptor, size, target, type, mode, mtime)
        }
    }

    @TargetApi(Build.VERSION_CODES.BAKLAVA)
    override fun onRestoreFile(data: FullRestoreDataInput) {
        restoreNativeFile(
            data.destination,
            data.data,
            data.size,
            fileType = data.type,
            nativeFormat =
                data.transportFlags and FLAG_CROSS_PLATFORM_DATA_TRANSFER_IOS == 0 && data.contentVersion.isEmpty(),
        ) { descriptor, target ->
            super.onRestoreFile(descriptor, data.size, target, data.type, data.mode, data.modificationTimeSeconds)
        }
    }

    private fun restoreNativeFile(
        destination: File,
        data: ParcelFileDescriptor,
        size: Long,
        nativeFormat: Boolean? = null,
        fileType: Int = TYPE_FILE,
        restore: (ParcelFileDescriptor, File) -> Unit,
    ) {
        AndroidNativeRestoreDeliveryGate.beginRestoreCallback()
        try {
            synchronized(callbackLock) {
                restoreFormat.requireNativeFile(nativeFormat)
                if (privacyRepair.captureIfRequired(fileType, { retainedNativeDestination(this, destination) }) { cohort, path ->
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(data.fileDescriptor)).use { input ->
                            completedReplay.captureInCohort(cohort, path, size, input)
                        }
                    }
                ) {
                    return@synchronized
                }
                finishCompletedNativeJournalCleanup()
                restoreCompletionRecorded = false
                installationStateReset = false
                val workspace = retainedJournalWorkspace(this)
                if (workspace.prepareNativeDelivery()) {
                    if (fileType == TYPE_DIRECTORY) return@synchronized
                    if (fileType != TYPE_FILE) throw IOException("Native journal delivery must contain regular files")
                    incomingRestoreSession.beginIfNeeded()
                    val cohort = workspace.beginIncomingDelivery(checkNotNull(incomingGeneration))
                    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(data.fileDescriptor)).use { input ->
                        workspace.captureIncomingFile(cohort, retainedNativeDestination(this, destination), size, input)
                    }
                    scheduleRetainedJournalReconciliationSafely()
                    return@synchronized
                }
                if (completedRawReplay.shouldCapture() ||
                    NativeJournalRestoreAuthority(this).completedReceipt() != null ||
                    completedReplay.hasJournalInputs()
                ) {
                    if (fileType == TYPE_DIRECTORY) return@synchronized
                    if (fileType != TYPE_FILE) throw IOException("Native replay must contain regular files")
                    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(data.fileDescriptor)).use { input ->
                        completedReplay.capture(retainedNativeDestination(this, destination), size, input)
                    }
                    return@synchronized
                }
                beginNativeRestoreSessionOnce()
                restoreNativeFileWithCurrentAuthority(destination, data, size, fileType, restore)
            }
        } finally {
            AndroidNativeRestoreDeliveryGate.endRestoreCallback()
        }
    }

    private fun restoreNativeFileWithCurrentAuthority(
        destination: File,
        data: ParcelFileDescriptor,
        size: Long,
        fileType: Int,
        restore: (ParcelFileDescriptor, File) -> Unit,
    ) {
        val authority = NativeJournalRestoreAuthority(this)
        if (authority.requiresCustodyForIncoming()) {
            if (fileType == TYPE_DIRECTORY) return
            if (fileType != TYPE_FILE) throw IOException("Native incoming delivery must contain regular files")
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(data.fileDescriptor)).use { input ->
                completedReplay.capture(retainedNativeDestination(this, destination), size, input)
            }
            scheduleRetainedJournalReconciliationSafely()
            return
        }
        if (retainSelectedNativeOriginal(fileType, { retainedNativeDestination(this, destination) }) { incomingPath ->
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(data.fileDescriptor)).use { input ->
                    completedReplay.capture(incomingPath, size, input)
                }
            }
        ) {
            return
        }
        if (fileType == TYPE_FILE &&
            pinDirectNativeCameraOriginal(filesDir, retainedNativeDestination(this, destination), size) {
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(data.fileDescriptor))
            }
        ) {
            return
        }
        val frameworkDatabase =
            listOf("logdate", "logdate-wal", "logdate-shm", "logdate-journal")
                .any { destination.canonicalFile == getDatabasePath(it).canonicalFile }
        if (frameworkDatabase) {
            check(fileType == TYPE_FILE) { "Native database delivery must be a regular file" }
            authority.requireFrameworkDatabaseWrite(destination)
        }
        val transferRoot = File(filesDir, "native_journal_transfer").canonicalFile.toPath()
        if (destination.canonicalFile.toPath().startsWith(transferRoot)) {
            NativeJournalRestoreAuthority(this).requireSnapshot()
        }
        val preferenceDelivered =
            restoredPreferences().restore(
                destination,
                retainedPreferenceNames,
                size,
                { readNativePreferencePayload(data, size) },
                { bytes -> restoreNativePreferencePayload(this, bytes) { restore(it, destination) } },
                { drainDuplicateNativePreference(data, size) },
            )
        if (!preferenceDelivered && destination.canonicalFile == File(filesDir, "logdate.preferences_pb").canonicalFile) {
            restoreMainPreferences(this, data, size) { descriptor -> restore(descriptor, destination) }
            PostRestoreDetector(this).markNativePrivacyPayloadReceived()
        } else if (!preferenceDelivered) {
            restore(data, destination)
        }
        if (frameworkDatabase) authority.recordFrameworkDatabaseWrite(destination)
        authority.recordFrameworkPortableState()
        retainOptionalPreferences { capture(destination, retainedPreferenceNames) }
        val databaseDelivered =
            runCatching { destination.canonicalFile == getDatabasePath("logdate").canonicalFile }
                .getOrDefault(false)
        PostRestoreDetector(this).markNativeRestorePayloadReceived(databaseDelivered)
    }

    private fun restoredPreferences() = NativeRestoredPreferenceFiles(this, ::replayPreference)

    private fun replayPreference(
        destination: File,
        bytes: ByteArray,
    ) {
        restoreNativePreferencePayload(this, bytes) { descriptor ->
            val privateMode = (OsConstants.S_IRUSR or OsConstants.S_IWUSR).toLong()
            super.onRestoreFile(descriptor, bytes.size.toLong(), destination, TYPE_FILE, privateMode, 0L)
        }
    }

    override fun onDestroy() {
        finishNativeRestoreAgent(
            schedule = {
                if (retainedJournalWorkspace(this).canStageNativeDelivery()) {
                    scheduleRetainedJournalReconciliationSafely()
                }
            },
            finishNativeSession = nativeRestoreSession::finish,
            finishIncomingSession = incomingRestoreSession::finish,
            destroy = { super.onDestroy() },
        )
    }

    private fun beginNativeRestoreSessionOnce(resumeFinalization: Boolean = false) =
        nativeRestoreSession.beginIfNeeded {
            resumeFinalization && NativeRestoredPreferenceFiles(this).canResumeFinalization(retainedPreferenceNames)
        }

    private fun retainOptionalPreferences(operation: NativeRestoredPreferenceFiles.() -> Unit) {
        try {
            NativeRestoredPreferenceFiles(this).operation()
        } catch (_: Exception) {
            Napier.w("Optional restored preference retention remains pending")
        }
    }

    private fun retryAccountRecovery() {
        try {
            val preferences = createAndroidMainDataStore(this)
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(15_000) {
                    retainAccountRecovery(preferences, freshDelivery = false)
                    prepareRestoredInstallationState(preferences)
                    restoreAccount(preferences)
                }
            }
        } catch (_: Exception) {
            Napier.w("Background native account recovery remains pending")
        }
    }

    private suspend fun prepareRestoredInstallationState(preferences: DataStore<Preferences>) {
        if (synchronized(callbackLock) { installationStateReset }) return
        prepareNativeRestoredInstallationState(this, preferences)
        synchronized(callbackLock) { installationStateReset = true }
    }

    private fun scheduleRetainedJournalReconciliationSafely(): Boolean =
        scheduleRetainedNativeJournalSafely(::scheduleRetainedJournalReconciliation)

    /** Phone agents must validate the retained SQLCipher copy without opening their application graph. */
    protected open fun inspectNativePrivacyRepairCopy(files: Map<String, File>) {
        check(files.keys.none { it.startsWith("database/") }) { "Native privacy repair has no encrypted journal validator" }
    }

    protected open fun scheduleRetainedJournalReconciliation(): Boolean = false

    /** Concrete agents must validate their encrypted journal before replacing a cloud backup. */
    protected open fun requireCloudJournalRecoverability(): Unit =
        throw IOException("Native cloud backup has no journal recovery validator")

    protected open fun prepareNativeJournalTransferBackup() = Unit

    protected open fun backupSelectedNativeMedia(data: FullBackupDataOutput) = Unit

    /** The iOS payload is selected and emitted independently of Android Auto Backup traversal. */
    protected open fun backupNativeIosTransfer(data: FullBackupDataOutput): Unit = throw IOException("Native iOS transfer is unavailable")

    protected open fun finishNativeJournalTransferBackup() = Unit

    protected open fun restoreNativeJournalTransferSnapshot(): Boolean = false

    /** A complete source snapshot was retained for merge because a local journal had prior authority. */
    protected open fun wasNativeJournalTransferStaged(): Boolean = false

    protected open fun finishNativeJournalTransferRestore(): Boolean = false

    protected open fun retainedNativeCameraReconciliationScheduled() = Unit

    private fun finishCompletedNativeJournalCleanup() {
        val authority = NativeJournalRestoreAuthority(this)
        if (PostRestoreDetector(this).hasCompletedNativeRestoreDelivery() &&
            (authority.installedReceipt() != null || authority.cleanupReceipt() != null)
        ) {
            val retainedCameraSource = finishNativeJournalTransferRestore()
            if (retainedCameraSource) {
                retryAccountRecovery()
                if (scheduleRetainedJournalReconciliationSafely()) retainedNativeCameraReconciliationScheduled()
            }
        }
    }

    protected fun restoreNativeJournalPreferences(file: File) {
        restoreVerifiedNativeJournalPreferences(this, file)
    }

    protected open suspend fun retainAccountRecoveryBeforeCompletion(
        preferences: DataStore<Preferences>,
        deliveryId: String,
    ) = Unit

    protected open suspend fun retainAccountRecovery(
        preferences: DataStore<Preferences>,
        freshDelivery: Boolean,
    ) = Unit

    protected open suspend fun restoreAccount(preferences: DataStore<Preferences>) = Unit

    override fun onQuotaExceeded(
        backupDataBytes: Long,
        quotaBytes: Long,
    ) {
        super.onQuotaExceeded(backupDataBytes, quotaBytes)
        NativeBackupQuotaStore(noBackupFilesDir).record(backupDataBytes, quotaBytes)
        Napier.w("Android native backup exceeded the transport quota")
    }
}
