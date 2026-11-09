package app.logdate.client.launch

import android.app.Application
import app.logdate.client.activity.PhoneActivityAwareness
import app.logdate.client.ambient.AmbientPromptScheduler
import app.logdate.client.ambient.AmbientPromptSchedulingObserver
import app.logdate.client.bubble.RecordingBubbleNotifier
import app.logdate.client.calendar.CalendarImportScheduler
import app.logdate.client.data.account.RestoreCredentialLifecycle
import app.logdate.client.data.notes.StoredMediaReferenceMigrationLauncher
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.recommendation.AmbientPromptTriggerContext
import app.logdate.client.domain.reminders.ReminderSignalObserver
import app.logdate.client.events.EventInferenceScheduler
import app.logdate.client.feature.widgets.publishWidgetPreviews
import app.logdate.client.location.tracking.LocationTrackingManager
import app.logdate.client.notifications.LiveUpdatesSwitch
import app.logdate.client.notifications.LogDateNotificationRegistrar
import app.logdate.client.notifications.RecordingBubbleSwitch
import app.logdate.client.platform.PlatformComponentToggler
import app.logdate.client.rewind.RewindGenerationScheduler
import app.logdate.client.shortcuts.DynamicShortcutRefreshObserver
import app.logdate.client.shortcuts.DynamicShortcutScheduler
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.v2.PhoneStatePublisher
import app.logdate.client.sync.v2.link.PhoneLinkInitiator
import app.logdate.client.sync.v2.receipts.WatchReceiptNotifier
import app.logdate.client.sync.v2.window.WatchWindowPublisher
import app.logdate.client.tiles.RecordVoiceTileUpdater
import app.logdate.client.tiles.RecordingWindowTracker
import app.logdate.client.watch.presence.WatchPresenceRegistration
import app.logdate.feature.core.settings.account.recovery.AutomaticAccountRecovery
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.Koin

/** Starts the app's device-owned work from either the normal process or backup-agent process. */
internal class AndroidApplicationRuntime(
    private val application: Application,
    private val koin: Koin,
) {
    fun start() {
        isolated("Failed to start restore credential cleanup reconciliation") {
            resolve<RestoreCredentialLifecycle>().start(resolve<CoroutineScope>())
        }
        preserveExistingIdentityBackup()
        publishWidgetPreviewsInBackground()
        isolated("Failed to register notification channels on app startup") {
            LogDateNotificationRegistrar(application).registerAllPhoneChannels()
        }
        isolated("Failed to start the media reference migration") {
            resolve<StoredMediaReferenceMigrationLauncher>().start()
        }
        isolated("Failed to initialize ambient prompt scheduling") { startAmbientScheduling() }
        isolated("Failed to observe reminder signals") {
            resolve<ReminderSignalObserver>().start()
        }
        isolated("Failed to start setting up account recovery") {
            resolve<AutomaticAccountRecovery>().start()
        }
        isolated("Failed to start location tracking manager on app startup") {
            resolve<LocationTrackingManager>().startTracking()
        }
        isolated("Failed to initialize rewind generation scheduling") {
            val rewindScheduler = RewindGenerationScheduler(application)
            rewindScheduler.schedulePeriodicGeneration()
            rewindScheduler.disableAnnualGeneration()
            rewindScheduler.enqueueImmediateCheck()
        }
        isolated("Failed to initialize event inference scheduling") {
            val scheduler = EventInferenceScheduler(application)
            if (FeatureFlag.EVENTS.availableForLaunch) scheduler.schedulePeriodicInference() else scheduler.disableForLaunch()
        }
        isolated("Failed to initialize calendar import scheduling") {
            val scheduler = CalendarImportScheduler(application)
            if (FeatureFlag.EVENTS.availableForLaunch) scheduler.schedulePeriodicImport() else scheduler.disableForLaunch()
        }
        isolated("Failed to initialize dynamic shortcut scheduling") {
            val shortcutScheduler = resolve<DynamicShortcutScheduler>()
            shortcutScheduler.schedulePeriodicRefresh()
            shortcutScheduler.enqueueImmediateRefresh()
            resolve<DynamicShortcutRefreshObserver>().start()
        }
        isolated("Failed to start syncing flag-gated components") {
            resolve<PlatformComponentToggler>().start(resolve<CoroutineScope>())
            resolve<RecordVoiceTileUpdater>().start(resolve<CoroutineScope>())
            resolve<RecordingWindowTracker>().start(application, resolve<CoroutineScope>())
            resolve<RecordingBubbleNotifier>().start(resolve<CoroutineScope>())
            resolve<LiveUpdatesSwitch>()
            resolve<RecordingBubbleSwitch>()
        }
        startPhoneStatePublishing()
        startActivityAwareness()
        startWatchLinking()
        startWatchReceipts()
        runCatching { resolve<WatchWindowPublisher>().start(resolve<CoroutineScope>()) }
            .onFailure { Napier.w("Failed to start publishing watch libraries", it) }
    }

    private fun startAmbientScheduling() {
        resolve<AmbientPromptSchedulingObserver>().start()
        val scheduler = resolve<AmbientPromptScheduler>()
        resolve<CoroutineScope>().launch {
            try {
                scheduler.enqueueImmediateEvaluation(AmbientPromptTriggerContext.PERIODIC)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Ambient prompt evaluation will reconcile on the next startup", error)
            }
        }
    }

    private fun preserveExistingIdentityBackup() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { resolve<IdentityKeyManager>().ensureExistingIdentityBackup() }
                .onFailure { Napier.w("Native encryption recovery material needs attention", it) }
        }
    }

    private fun publishWidgetPreviewsInBackground() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            publishWidgetPreviews(application)
        }
    }

    private inline fun <reified T : Any> resolve(): T = koin.get()

    private inline fun isolated(
        failure: String,
        start: () -> Unit,
    ) {
        runCatching(start).onFailure { error -> Napier.w(failure, error) }
    }

    private fun startWatchReceipts() {
        runCatching {
            resolve<WatchReceiptNotifier>().start(
                resolve<CoroutineScope>(),
                resolve<SyncMetadataService>().observePendingCount(),
            )
        }.onFailure { error ->
            Napier.w("Failed to start telling watches which notes are backed up", error)
        }
    }

    private fun startWatchLinking() {
        runCatching {
            resolve<PhoneLinkInitiator>().start(resolve<CoroutineScope>())
        }.onFailure { error ->
            Napier.w("Failed to start linking this phone's watches", error)
        }
    }

    private fun startActivityAwareness() {
        runCatching {
            resolve<PhoneActivityAwareness>().start(resolve<CoroutineScope>())
        }.onFailure { error ->
            Napier.w("Failed to start following the phone's activity", error)
        }
    }

    private fun startPhoneStatePublishing() {
        runCatching {
            resolve<PhoneStatePublisher>().start(resolve<CoroutineScope>())
            resolve<WatchPresenceRegistration>().start(resolve<CoroutineScope>())
        }.onFailure { error ->
            Napier.w("Failed to start publishing this phone's state to its watches", error)
        }
    }
}
