package app.logdate.di

import android.Manifest
import android.content.pm.PackageManager
import app.logdate.client.activity.PhoneActivityAwareness
import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.createAndroidPhoneActivityConsentStore
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.datastore.featureflags.PHONE_ACTIVITY_CONSENT_STORE
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.domain.activity.ActivityPresenter
import app.logdate.client.domain.activity.ActivityTracker
import app.logdate.client.domain.activity.CaptureActivitySource
import app.logdate.client.domain.activity.CurrentCaptureActivity
import app.logdate.client.domain.activity.KeyValueActivityLogStore
import app.logdate.client.domain.activity.PhoneActivityRegistration
import app.logdate.client.domain.activity.PhoneActivityRegistrationState
import app.logdate.client.domain.activity.SuggestionOutcomes
import app.logdate.client.domain.location.ObserveLocationStopsUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.recommendation.GetMemoryRecallUseCase
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.domain.reminders.CampfireFadeDetector
import app.logdate.client.domain.reminders.LearnedReminderHours
import app.logdate.client.domain.reminders.LivePhoneReminderSnapshotSource
import app.logdate.client.domain.reminders.NovelPlaceArrivalDetector
import app.logdate.client.domain.reminders.PhoneReminderSnapshotSource
import app.logdate.client.domain.reminders.SupplementalReminderSource
import app.logdate.client.domain.reminders.WindDownTimeResolver
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.client.domain.watch.WatchPreferencesRepository
import app.logdate.client.health.AndroidSleepRepository
import app.logdate.client.health.SleepRepository
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.permissions.PermissionType
import app.logdate.client.reminders.PhoneReminderEnvironmentReader
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.moments.MarkedMomentRepository
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.PresenceState
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Instant

val phoneReminderSignalsModule =
    module {
        single<FeatureFlagStore>(named(PHONE_ACTIVITY_CONSENT_STORE)) {
            val context = androidContext()
            val restore = PostRestoreDetector(context)
            createAndroidPhoneActivityConsentStore(context, portable = get()) {
                !restore.hasNativeRestoreHistory() &&
                    !restore.shouldValidatePrivacyMetadata() &&
                    context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
            }
        }
        single {
            val watches = get<PeerStateStore>().latest(PeerRole.WATCH).map { it?.watch }
            ActivityTracker(
                store = KeyValueActivityLogStore(get<KeyValueStorage>()),
                peer = watches.map { it?.activity },
                // Health Services reports sleep only while the watch is worn, so only a watch known to be
                // off the wrist is set aside.
                watchWorn = watches.map { it?.presence?.state != PresenceState.OFF_WRIST },
                isPhone = true,
                enabled = get<PhoneActivityRegistration>().state.map { it == PhoneActivityRegistrationState.Linked },
            )
        }
        single { SuggestionOutcomes(get<KeyValueStorage>()) }
        single<CaptureActivitySource> {
            CurrentCaptureActivity(
                agreed = get<ActivityTracker>().agreed,
                enabled = get<PhoneActivityRegistration>().state.map { it == PhoneActivityRegistrationState.Linked },
                outcomes = get<SuggestionOutcomes>().outcomes,
            )
        }
        single {
            val notes = get<JournalNotesRepository>()
            ActivityPresenter(
                agreed = get<ActivityTracker>().agreed,
                outcomes = get(),
                capturedSince = { since -> notes.observeNotesInRange(since, Instant.DISTANT_FUTURE).map { it.isNotEmpty() } },
            )
        }
        single {
            PhoneActivityAwareness(
                context = androidContext(),
                tracker = { get() },
                flags = get(named(PHONE_ACTIVITY_CONSENT_STORE)),
            )
        }
        single { get<PhoneActivityAwareness>().registration }
        single<SleepRepository> { AndroidSleepRepository(androidContext()) }
        factory { WindDownTimeResolver(get()) }
        single {
            val flags = get<FeatureFlagStore>()
            val shared = get<WatchPreferencesRepository>()
            LearnedReminderHours(
                enabled =
                    combine(flags.observe(FeatureFlag.WATCH_EXPERIENCE_V2), shared.observe(SharedPreferenceKey.RemindersEnabled)) {
                        rollout,
                        reminders,
                        ->
                        rollout && reminders
                    },
                resolver = get(),
            )
        }
        factory { CampfireFadeDetector(get(), get()) }
        single {
            val stops = get<ObserveLocationStopsUseCase>()
            val places = get<PlaceResolutionCache>()
            val device = get<DeviceIdProvider>()
            NovelPlaceArrivalDetector(get(), get(), locations = {
                stops.currentDevice(device.getDeviceId().value.toString()).first()
            }, resolve = { places.resolve(it) })
        }
        factory {
            val nodes = Wearable.getNodeClient(androidContext())
            val permissions = get<PermissionManager>()
            PhoneReminderEnvironmentReader(
                sessions = get(),
                config = get(),
                preferences = get(),
                shared = get(),
                flags = get(),
                peers = get(),
                states = get(),
                connectedNodes = { withTimeoutOrNull(2_000) { nodes.connectedNodes.await().mapTo(HashSet()) { it.id } }.orEmpty() },
                locationAllowed = { permissions.isPermissionGranted(PermissionType.LOCATION) },
            )
        }
        factory {
            val drafts = get<FetchMostRecentDraftUseCase>()
            val notes = get<JournalNotesRepository>()
            val recall = get<GetMemoryRecallUseCase>()
            val settings = get<MemoriesSettingsRepository>()
            SupplementalReminderSource(
                latestDraft = { drafts().first() },
                savedSince = { since -> notes.observeEntryTimestamps().first().any { it >= since } },
                recall = {
                    val preferences = settings.getSettings()
                    recall(preferences.aiRecallEnabled, preferences.recallMode, preferences.widgetContentTypes).first()
                },
                history = get(),
            )
        }
        factory<PhoneReminderSnapshotSource> {
            val environment = get<PhoneReminderEnvironmentReader>()
            val marks = get<MarkedMomentRepository>()
            val supplemental = get<SupplementalReminderSource>()
            val tracker = get<ActivityTracker>()
            LivePhoneReminderSnapshotSource(
                environment = { environment.read() },
                windDown = get(),
                journal = get(),
                arrivals = get(),
                openMarks = { marks.observeOpenMarks().first().size },
                activity = { tracker.agreed.first() },
                supplemental = { supplemental.read(it) },
            )
        }
    }
