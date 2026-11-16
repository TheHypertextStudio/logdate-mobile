package app.logdate.client.sync

import android.app.Notification
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import app.logdate.client.data.journals.LocalFirstDraftRepository
import app.logdate.client.data.journals.OfflineFirstJournalContentRepository
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.data.journals.RemoteJournalDataSource
import app.logdate.client.data.notes.OfflineFirstJournalNotesRepository
import app.logdate.client.data.quota.AndroidRemoteQuotaDataSource
import app.logdate.client.database.getDatabaseBuilder
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.DataStoreKeyValueStorage
import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.AndroidAppInfoProvider
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.device.identity.DefaultCanonicalOwnerProvider
import app.logdate.client.media.AndroidMediaManager
import app.logdate.client.networking.DataRestriction
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.QuotaApiClient
import app.logdate.client.notifications.LiveUpdatesSwitch
import app.logdate.client.notifications.currentLiveUpdatesSwitch
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.cloud.DefaultCloudAssociationDataSource
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.cloud.LogDateCloudApiClient
import app.logdate.client.sync.cloud.account.DefaultCloudAccountRepository
import app.logdate.client.sync.cloud.account.ServerAccountStore
import app.logdate.client.sync.conflict.KeyValueSyncConflictStore
import app.logdate.client.sync.conflict.LastWriteWinsResolver
import app.logdate.client.sync.di.syncModule
import app.logdate.client.sync.metadata.DatabaseSyncMetadataService
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.KeyValueFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.KeyValueIdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.KeyValueLastSyncErrorStore
import app.logdate.client.sync.metadata.KeyValueMediaSyncRefStore
import app.logdate.client.sync.metadata.KeyValueSyncDeadLetterStore
import app.logdate.client.sync.metadata.KeyValueSyncRetryScheduleStore
import app.logdate.client.sync.metadata.KeyValueUnreadableCloudRecordStore
import app.logdate.client.sync.metadata.MediaSyncRef
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.quota.LogDateCloudQuotaManager
import app.logdate.client.sync.quota.LogDateQuotaCalculator
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.Journal
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Real worker and production persistence; only authorization/environment and HTTP are synthetic. */
internal class BackupSyncGraphFixture(
    private val expedited: Boolean = false,
) {
    private val application = RuntimeEnvironment.getApplication()
    private val root = Files.createTempDirectory("logdate-backup-graph-").toFile()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val config = DefaultLogDateConfigRepository(initialBackendUrl = ORIGIN)
    private val sessions = SyntheticSession()
    private val preferenceStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "state.preferences_pb") })
    private val storage = DataStoreKeyValueStorage(preferenceStore)
    private val database = getRoomDatabase(getDatabaseBuilder(application, databaseFile = File(root, "journal.db")), driver = null)
    private val owner =
        DefaultCanonicalOwnerProvider(storage).also { provider ->
            runBlocking { check(provider.adoptRemoteOwnerIfUninitialized(OWNER)) }
        }
    private val metadata = DatabaseSyncMetadataService(database.syncMetadataDao(), config, owner)
    private val transactions = RoomSyncTransactionManager(database)
    private val bodyWrites = AtomicInteger()
    private val requests = CopyOnWriteArrayList<String>()
    val notices = CopyOnWriteArrayList<Notification>()
    val originalIds = mutableSetOf<String>()
    var accountDelayMillis = 0L
    var quotaDelayMillis = 0L
    val payloadWrites get() = bodyWrites.get()
    val mediaPayloadRequests get() = requests.count { !it.startsWith("GET ") && it.contains("/media") }
    private var cachedOriginal: File? = null
    private val mediaRefs = KeyValueMediaSyncRefStore(storage, { ORIGIN }, { OWNER })
    private val http =
        HttpClient(
            MockEngine { request ->
                check(request.url.host == "backup.invalid") { "Only synthetic transport is allowed" }
                val path = request.url.encodedPath
                requests += "${request.method.value} $path"
                val now = Clock.System.now().toEpochMilliseconds()
                val body =
                    when {
                        path.endsWith("/auth/me") -> {
                            delay(accountDelayMillis)
                            """{"success":true,"data":{"account":{"id":"$OWNER","username":"synthetic","displayName":"Synthetic","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},"tokens":{"accessToken":"synthetic-access","refreshToken":"synthetic-refresh"}}}"""
                        }

                        path.endsWith("/quota") -> {
                            delay(quotaDelayMillis)
                            """{"totalBytes":1000000,"usedBytes":0,"categories":[]}"""
                        }

                        request.method == HttpMethod.Put && path.contains("/contents/") -> {
                            bodyWrites.incrementAndGet()
                            """{"id":"${path.substringAfterLast('/')}","serverVersion":1,"uploadedAt":$now}"""
                        }

                        request.method == HttpMethod.Patch && path.contains("/contents/") -> {
                            bodyWrites.incrementAndGet()
                            """{"id":"${path.substringAfterLast('/')}","serverVersion":2,"updatedAt":$now}"""
                        }

                        request.method == HttpMethod.Get && path.endsWith("/drafts/changes") -> {
                            """{"drafts":[],"deletions":[],"lastTimestamp":$now,"hasMore":false}"""
                        }

                        request.method == HttpMethod.Get && listOf("/contents", "/journals", "/associations").any(path::endsWith) -> {
                            """{"changes":[],"deletions":[],"lastTimestamp":$now,"hasMore":false}"""
                        }

                        else -> {
                            error("Unexpected synthetic transport request: ${request.method} $path")
                        }
                    }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    private val api = LogDateCloudApiClient(config, http, sessions)
    private val account = DefaultCloudAccountRepository(api, storage, config, scope, sessions)
    private val drafts = LocalFirstDraftRepository(storage, Json, sessions, config, metadata = metadata, transactions = transactions)
    private val unusedLegacyRemote =
        object : RemoteJournalDataSource {
            override suspend fun observeAllJournals(): List<Journal> = error("Legacy repository transport must not run")

            override suspend fun addJournal(journal: Journal): String = error("Legacy repository transport must not run")

            override suspend fun editJournal(journal: Journal) = error("Legacy repository transport must not run")

            override suspend fun deleteJournal(journalId: String) = error("Legacy repository transport must not run")
        }
    private val journals =
        OfflineFirstJournalRepository(
            database.journalDao(),
            unusedLegacyRemote,
            drafts,
            syncMetadataService = metadata,
            externalScope = scope,
            database = database,
            mergeTransactionManager = transactions,
        )
    private val media = AndroidMediaManager(application.contentResolver, application)
    private val notes =
        OfflineFirstJournalNotesRepository(
            database.textNoteDao(),
            database.imageNoteDao(),
            database.audioNoteDao(),
            database.videoNoteDao(),
            database.journalContentDao(),
            journals,
            database.mediaCaptionDao(),
            mediaManager = media,
            transactionManager = transactions,
            syncMetadataService = metadata,
            syncScope = scope,
        )
    private val links = OfflineFirstJournalContentRepository(database.journalContentDao(), journals, notes, metadata, transactions)
    private val policy =
        object : DataUsagePolicy {
            override val policy = MutableStateFlow<DataUsageMode>(DataUsageMode.Conservative)

            override suspend fun currentMode() = policy.value

            override suspend fun currentRestriction() = DataRestriction.NONE
        }
    private val engine =
        DefaultSyncManager(
            cloudContentDataSource = DefaultCloudContentDataSource(api),
            cloudJournalDataSource = DefaultCloudJournalDataSource(api),
            cloudAssociationDataSource = DefaultCloudAssociationDataSource(api),
            cloudMediaDataSource = DefaultCloudMediaDataSource(api),
            cloudDraftDataSource = DefaultCloudDraftDataSource(api),
            cloudAccountRepository = account,
            sessionStorage = sessions,
            mediaManager = media,
            mediaSyncRefStore = mediaRefs,
            journalRepository = journals,
            journalNotesRepository = notes,
            journalContentRepository = links,
            journalConflictResolver = LastWriteWinsResolver(),
            noteConflictResolver = LastWriteWinsResolver(),
            conflictStore = KeyValueSyncConflictStore(storage),
            deadLetterStore = KeyValueSyncDeadLetterStore(storage),
            retryScheduleStore = KeyValueSyncRetryScheduleStore(storage),
            syncMetadataService = metadata,
            transactionManager = transactions,
            dataUsagePolicy = policy,
            syncScope = scope,
            lastErrorStore = KeyValueLastSyncErrorStore(storage),
            firstSyncEnqueueStore = KeyValueFirstSyncEnqueueStore(storage),
            identityRecoveryNeededStore = KeyValueIdentityRecoveryNeededStore(storage),
            unreadableCloudRecordStore = KeyValueUnreadableCloudRecordStore(storage),
            cloudQuotaManager =
                LogDateCloudQuotaManager(
                    LogDateQuotaCalculator(database.storageMetadataDao()),
                    AndroidRemoteQuotaDataSource(QuotaApiClient(http, config), sessions),
                    sessions,
                ),
            cloudApiClient = api,
            serverAccounts = ServerAccountStore(storage),
            syncRouteStore = KeyValueSyncRouteStore(storage),
        )
    private val context = spyk(ContextWrapper(application))
    private val worker: AndroidLogDateSyncWorker

    init {
        mockkStatic(::currentLiveUpdatesSwitch)
        every { currentLiveUpdatesSwitch() } returns
            object : LiveUpdatesSwitch {
                override var enabled = true
            }
        every { context.getString(R.string.sync_notification_starting) } returns "Checking for changes"
        every { context.getString(R.string.sync_notification_title) } returns "Backing up your memories"
        every { context.getString(R.string.sync_notification_progress, *anyVararg()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]} of ${args[1]} changes backed up"
        }
        stopKoin()
        startKoin {
            allowOverride(true)
            modules(
                syncModule,
                module {
                    single<DefaultSyncManager> { engine }
                    single<SessionStorage> { sessions }
                    single<KeyValueStorage> { storage }
                    single<AppInfoProvider> { AndroidAppInfoProvider(application) }
                },
            )
        }
        // Reuse the actual production request constructor, including network constraints and input.
        val builder =
            Class
                .forName("app.logdate.client.sync.AndroidSyncRequestsKt")
                .getDeclaredMethod("immediateSyncRequest", String::class.java, UploadScope::class.java, Boolean::class.javaPrimitiveType)
        val request = builder.invoke(null, AndroidLogDateSyncWorker.SYNC_TYPE_FULL, null, expedited) as OneTimeWorkRequest
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(expedited, request.workSpec.expedited)
        val updater = mockk<ForegroundUpdater>()
        every { updater.setForegroundAsync(any(), any(), any()) } answers {
            notices += thirdArg<ForegroundInfo>().notification
            SettableFuture.create<Void>().also { it.set(null) }
        }
        val parameters = mockk<WorkerParameters>(relaxed = true)
        every { parameters.id } returns request.id
        every { parameters.inputData } returns request.workSpec.input
        every { parameters.runAttemptCount } returns 0
        every { parameters.foregroundUpdater } returns updater
        worker = AndroidLogDateSyncWorker(context, parameters)
    }

    suspend fun addOriginals(count: Int) {
        repeat(count) { index ->
            val file = File(root, "original-$index.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, index.toByte())) }
            val note =
                JournalNote.Image(
                    creationTimestamp = Clock.System.now(),
                    lastUpdated = Clock.System.now(),
                    mediaRef = file.toURI().toString(),
                )
            notes.create(note)
            originalIds += note.uid.toString()
        }
    }

    suspend fun addCachedOriginalMetadataChange() {
        val file = File(root, "cached-original.m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        cachedOriginal = file
        val note =
            JournalNote.Audio(
                mediaRef = file.toURI().toString(),
                creationTimestamp = Clock.System.now(),
                lastUpdated = Clock.System.now(),
                syncVersion = 1,
            )
        notes.createFromSync(note)
        mediaRefs.upsert(
            MediaSyncRef(
                note.uid.toString(),
                note.mediaRef,
                "$ORIGIN/api/v1/media/already-uploaded",
                "already-uploaded",
                Clock.System.now().toEpochMilliseconds(),
            ),
        )
        assertTrue(
            notes.updateAudioDurationIfUnknown(note.uid, note.mediaRef, 1_200),
            "Real metadata writer must queue the changed duration",
        )
    }

    fun cachedOriginalIntact() = cachedOriginal?.readBytes()?.contentEquals(byteArrayOf(1, 2, 3, 4)) == true

    suspend fun addText() {
        notes.create(
            JournalNote.Text(creationTimestamp = Clock.System.now(), lastUpdated = Clock.System.now(), content = "Synthetic eligible text"),
        )
    }

    suspend fun pendingOperations() = metadata.getPendingUploads(EntityType.NOTE)

    suspend fun notesCount() = notes.allNotesObserved.first().size

    suspend fun status() = engine.getSyncStatus()

    private var lastStatus: SyncStatus? = null
    private var workerResult: String? = null

    suspend fun runWorker() =
        worker.doWork().also {
            lastStatus = engine.getSyncStatus()
            workerResult = it.toString()
        }

    suspend fun platformForegroundInfo() = worker.getForegroundInfo()

    fun diagnostics() =
        "notices=${notices.map {
            Triple(
                it.extras.getString(Notification.EXTRA_TITLE),
                it.extras.getInt(Notification.EXTRA_PROGRESS),
                it.extras.getInt(Notification.EXTRA_PROGRESS_MAX),
            )
        }}; requests=$requests; result=$workerResult; status=$lastStatus"

    suspend fun useGraph(body: suspend (BackupSyncGraphFixture) -> Unit) {
        try {
            body(this)
        } finally {
            stopKoin()
            unmockkStatic(::currentLiveUpdatesSwitch)
            job.cancelAndJoin()
            http.close()
            database.close()
            check(root.deleteRecursively())
        }
    }

    private class SyntheticSession : SessionStorage {
        private val value = MutableStateFlow<UserSession?>(UserSession("synthetic-access", "synthetic-refresh", OWNER))

        override fun getSession() = value.value

        override fun getSessionFlow() = value

        override fun getOriginBoundSession() = value.value?.let { OriginBoundSession(ORIGIN, it) }

        override suspend fun saveSession(session: UserSession) {
            value.value = session
        }

        override suspend fun clearSession() {
            value.value = null
        }

        override suspend fun hasValidSession() = value.value != null
    }

    companion object {
        private const val ORIGIN = "https://backup.invalid"
        private const val OWNER = "00000000-0000-0000-0000-000000000001"
    }
}
