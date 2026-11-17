package app.logdate.client.e2e

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.AtomicFile
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.logdate.client.MainActivity
import app.logdate.client.datastore.LogDateConfigDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.CryptoManager
import app.logdate.client.device.crypto.IdentityKeyFingerprint
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DefaultDeviceManager
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.networking.ServerDiscoveryClient
import app.logdate.client.repository.journals.CaptureActivity
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.AndroidLogDateSyncWorker
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.crypto.SyncPayloadCipher
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.SerializableTextBlock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Owned-emulator registration against the host's local server, independent of Google providers. */
@RunWith(AndroidJUnit4::class)
class NativeDeviceEnrollmentProbeTest {
    @get:Rule
    internal val trace = NativeTransferProbeTraceRule()

    private val koin get() = GlobalContext.get()
    private lateinit var context: Context
    private lateinit var fixture: NativeDeviceEnrollmentCredentials
    private var startupWorkIds = emptySet<String>()
    private val receiptFile get() = File(context.noBackupFilesDir, "native-device-enrollment-receipt.json")

    @Test
    fun nativeDeviceEnrollment() =
        runBlocking {
            trace.bindCurrentProbeThread()
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("nativeDeviceEnrollment") == "true")
            val stage = arguments.getString("nativeDeviceEnrollmentStage")
            require(stage in setOf("source-prepare", "source-enroll", "destination-edit", "destination-enroll", "replay", "cleanup")) {
                "Explicit enrollment fixture stage required"
            }
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Owned emulator required" }
            context = InstrumentationRegistry.getInstrumentation().targetContext
            require(context.packageName.endsWith(".debug")) { "Isolated debug application required" }
            fixture = readNativeDeviceEnrollmentCredentials(context)
            if (stage == "destination-enroll" || stage == "replay") awaitValidatedNativeWifi(context)
            nativeTransferPhase("enrollment_before_application_start")
            ensureLogDateApplicationStarted(context.applicationContext as Application)
            nativeTransferPhase("enrollment_application_started")
            if (stage == "destination-enroll" || stage == "replay") {
                startupWorkIds = priorStartupWorkIds(stage == "replay")
            }
            if (stage != "source-prepare" && stage != "destination-edit") selectFixtureServer()
            when (stage) {
                "source-prepare" -> prepareSource()
                "source-enroll" -> enrollSource()
                "destination-edit" -> editDraftOffline()
                "destination-enroll" -> enrollDestination(replay = false)
                "replay" -> enrollDestination(replay = true)
                "cleanup" -> cleanupSession()
            }
        }

    private suspend fun prepareSource() {
        assertFalse(receiptFile.exists(), "Fresh preparation must not replace an earlier enrollment receipt")
        assertTrue(
            koin
                .get<JournalRepository>()
                .allJournalsObserved
                .first()
                .isEmpty(),
            "Fresh empty source required",
        )
        val owner = koin.get<CanonicalOwnerProvider>()
        assertFalse(owner.hasBoundOwner(), "Preparation must not replace a bound journal owner")
        assertFalse(koin.get<IdentityKeyManager>().hasIdentityKey(), "Existing identity must be preserved by refusing preparation")
        assertNoSession()
        assertTrue(owner.adoptRemoteOwnerIfUninitialized(fixture.owner))
        selectFixtureServer()
        assertEquals(fixture.owner, owner.getCanonicalOwnerId())
        assertNoSession()
        assertFalse(koin.get<IdentityKeyManager>().hasIdentityKey(), "Preparation must not create an encryption identity")
    }

    private suspend fun enrollSource() {
        val sourceMarker = File(context.noBackupFilesDir, "native-backup-fixture.json")
        require(sourceMarker.isFile) { "The native transfer probe must seed the source first" }
        val marker = JSONObject(sourceMarker.readText())
        val before = identity()
        assertEquals(fixture.owner, before.getString("owner"))
        assertSameIdentity(marker, before)
        val journals = retainedJournals()
        assertNoSession()
        require(!receiptFile.exists()) { "Source provenance may only be prepared once on the fresh fixture" }
        prepareHistoricalCapture()
        val proof = localTransferProof(destination = false)
        installFixtureSession()
        val listed = registerAndList(setOf(before.getString("device")))
        assertSameIdentity(before, identity())
        assertEquals(journals, retainedJournals())
        writeReceipt(before.put("listedIds", JSONArray(listed.sorted())).put("proof", proof))
    }

    private suspend fun enrollDestination(replay: Boolean) {
        val before = identity()
        val sourceDevice = requireNotNull(fixture.sourceDevice) { "Destination source device required" }
        val sourceFingerprint = requireNotNull(fixture.sourceFingerprint) { "Destination source fingerprint required" }
        assertEquals(fixture.owner, before.getString("owner"))
        assertEquals(sourceFingerprint, before.getString("fingerprint"))
        assertNotEquals(sourceDevice, before.getString("device"), "Fresh replacement installation required")
        val journals = retainedJournals()
        val proof = localTransferProof(destination = true)
        val sourceProof = requireNotNull(fixture.sourceProof) { "Source provenance baseline required" }
        listOf("historicalCreatedAt", "draftCreatedAt").forEach { assertEquals(sourceProof.getLong(it), proof.getLong(it)) }
        if (replay) {
            require(receiptFile.isFile) { "A completed destination enrollment receipt is required for replay" }
            val receipt = JSONObject(receiptFile.readText())
            assertSameIdentity(receipt, before)
            assertEquals(receipt.getJSONObject("proof").toString(), proof.toString())
            assertFixtureSession()
        } else {
            require(!receiptFile.exists()) { "Initial destination enrollment must not replace an existing receipt" }
            assertNoSession()
            installFixtureSession()
        }
        val listed = observeAutomaticSync(proof, before)
        assertSameIdentity(before, identity())
        assertEquals(journals, retainedJournals())
        writeReceipt(before.put("listedIds", JSONArray(listed.sorted())).put("proof", proof))
    }

    private suspend fun editDraftOffline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        withTimeout(10_000) { while (connectivity.activeNetwork != null) delay(25) }
        assertNoSession()
        assertFalse(receiptFile.exists(), "Offline editing must precede destination authentication")
        val config = koin.get<LogDateConfigRepository>()
        koin.get<LogDateConfigDataSource>().awaitConfigurationLoaded()
        config.updateBackendUrl(fixture.origin)
        koin.get<LogDateConfigDataSource>().persistCurrentConfiguration()
        val before = identity()
        assertEquals(fixture.owner, before.getString("owner"))
        assertEquals(fixture.sourceFingerprint, before.getString("fingerprint"))
        val journals = koin.get<JournalRepository>()
        val original = requireNotNull(journals.getDraft(fixtureId(10)))
        assertEquals(
            "Unfinished native-transfer draft",
            original.blocks
                .filterIsInstance<SerializableTextBlock>()
                .single()
                .content,
        )
        journals.saveDraft(
            original.copy(
                blocks = original.blocks.map { if (it is SerializableTextBlock) it.copy(content = OFFLINE_DRAFT_EDIT) else it },
                lastModifiedAt = Clock.System.now(),
            ),
        )
        val saved = requireNotNull(journals.getDraft(original.id))
        assertEquals(original.createdAt, saved.createdAt)
        assertEquals(original.selectedJournalIds, saved.selectedJournalIds)
        assertEquals(original.blocks.map { it.id }, saved.blocks.map { it.id })
        assertEquals(original.blocks.filterNot { it is SerializableTextBlock }, saved.blocks.filterNot { it is SerializableTextBlock })
        assertEquals(
            OFFLINE_DRAFT_EDIT,
            saved.blocks
                .filterIsInstance<SerializableTextBlock>()
                .single()
                .content,
        )
        assertSameIdentity(before, identity())
        assertNoSession()
    }

    private suspend fun observeAutomaticSync(
        proof: JSONObject,
        before: JSONObject,
    ): Set<String> =
        ActivityScenario.launch(MainActivity::class.java).use {
            val work = awaitExecutedWork()
            awaitUploadedProof(proof, before.getString("device"))
            before.put("automaticSyncObserved", true).put("syncWorkIds", JSONArray(work.map { it.id.toString() }))
            listDevices(setOf(requireNotNull(fixture.sourceDevice), before.getString("device")))
        }

    private suspend fun awaitExecutedWork(): List<WorkInfo> {
        val observed = mutableListOf<WorkInfo>()
        try {
            return withTimeout(120_000) {
                WorkManager
                    .getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
                    .first { requests ->
                        observed.clear()
                        observed.addAll(requests)
                        requests.any { request ->
                            request.id.toString() !in startupWorkIds &&
                                request.state == WorkInfo.State.SUCCEEDED
                        }
                    }
            }
        } catch (_: TimeoutCancellationException) {
            val states = observed.joinToString { "${it.id}:${it.state}:attempt=${it.runAttemptCount}" }
            throw AssertionError(
                "Normal startup did not complete a new immediate sync within 120s; prior=$startupWorkIds; observed=$states",
            )
        }
    }

    private suspend fun cleanupSession() {
        nativeTransferPhase("enrollment_cleanup_identity")
        require(receiptFile.isFile) { "Cleanup requires this fixture's enrollment receipt" }
        val receipt = JSONObject(receiptFile.readText())
        val before = identity()
        assertSameIdentity(receipt, before)
        assertEquals(fixture.owner, before.getString("owner"))
        val sessions = koin.get<SessionStorage>()
        nativeTransferPhase("enrollment_cleanup_session_read")
        if (sessions.hasValidSession()) {
            assertFixtureSession()
            val current = requireNotNull(sessions.getOriginBoundSession()) { "Matching fixture session required" }
            nativeTransferPhase("enrollment_cleanup_session_clear")
            assertTrue(sessions.clearSessionIfCurrent(current), "Fixture session cleanup must be conditional")
        }
        assertNoSession()
        assertSameIdentity(before, identity())
        assertTrue(File(context.noBackupFilesDir, "native-device-enrollment-fixture.json").isFile)
        nativeTransferPhase("enrollment_cleanup_finished")
    }

    private suspend fun installFixtureSession() {
        val config = koin.get<LogDateConfigRepository>()
        config.updateBackendUrl(fixture.origin)
        koin.get<SessionStorage>().saveExplicitSession(UserSession(fixture.accessToken, fixture.refreshToken, fixture.owner))
        assertFixtureSession()
    }

    private suspend fun selectFixtureServer() {
        trace.awaitConfiguration(koin)
        val config = koin.get<LogDateConfigRepository>()
        config.updateBackendUrl(fixture.origin)
        nativeTransferPhase("enrollment_before_server_discovery")
        val descriptor = awaitNativeEnrollmentServer { koin.get<ServerDiscoveryClient>().discoverServer(fixture.origin) }
        nativeTransferPhase("enrollment_server_discovered")
        assertEquals(fixture.origin, descriptor.serverOrigin)
        config.updateServerDescriptor(descriptor)
        nativeTransferPhase("enrollment_before_configuration_persist")
        koin.get<LogDateConfigDataSource>().persistCurrentConfiguration()
        nativeTransferPhase("enrollment_configuration_persisted")
    }

    private fun priorStartupWorkIds(replay: Boolean): Set<String> {
        if (replay) {
            val prior = JSONObject(receiptFile.readText()).getJSONArray("syncWorkIds")
            return (0 until prior.length()).map { prior.getString(it) }.toSet()
        }
        return WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
            .get(10, TimeUnit.SECONDS)
            .filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.SUCCEEDED || it.runAttemptCount > 0 }
            .map { it.id.toString() }
            .toSet()
    }

    private suspend fun assertNoSession() {
        val sessions = koin.get<SessionStorage>()
        assertFalse(sessions.hasValidSession(), "A prior account session must not be present")
        assertTrue(sessions.getSession() == null, "A prior account session must not be present")
    }

    private suspend fun assertFixtureSession() {
        val sessions = koin.get<SessionStorage>()
        assertTrue(sessions.hasValidSession(), "The fixture session must be durable")
        val current = requireNotNull(sessions.getOriginBoundSession()) { "The fixture session must retain origin provenance" }
        assertTrue(current.origin == fixture.origin, "Only the fixture server session is eligible")
        assertTrue(current.session.accountId == fixture.owner, "Only the fixture owner session is eligible")
        assertTrue(current.session.refreshToken == fixture.refreshToken, "Only the fixture credential family is eligible")
    }

    private suspend fun registerAndList(expected: Set<String>): Set<String> {
        val manager = koin.get<DefaultDeviceManager>()
        repeat(2) {
            val result = manager.registerWithCloud()
            assertTrue(result.getOrNull() == true, "Production device registration must succeed")
        }
        return listDevices(expected)
    }

    private suspend fun listDevices(expected: Set<String>): Set<String> {
        val manager = koin.get<DefaultDeviceManager>()
        val listed =
            withTimeout(30_000) {
                // CloudDeviceRepository emits its cached snapshot before reading the real server.
                manager.getAssociatedDevices().drop(1).first { devices -> expected.all { id -> devices.any { it.id.toString() == id } } }
            }
        assertEquals(expected.size, listed.size, "The real server must retain each installation exactly once")
        assertEquals(expected, listed.map { it.id.toString() }.toSet())
        assertFixtureSession()
        return listed.map { it.id.toString() }.toSet()
    }

    private suspend fun prepareHistoricalCapture() {
        val notes = koin.get<JournalNotesRepository>()
        val note = requireNotNull(notes.getNoteById(fixtureId(3))) as JournalNote.Text
        assertTrue(note.capturedOn == null && note.capturedDuring == null, "Only the fresh synthetic source can acquire provenance")
        assertTrue(
            notes.compareAndSetNoteForRestore(
                note,
                note.copy(capturedOn = CaptureDevice.WATCH, capturedDuring = CaptureActivity.MOVING),
                notes.getNoteRecoveryScope(),
            ),
        )
    }

    private suspend fun localTransferProof(destination: Boolean): JSONObject {
        val note = requireNotNull(koin.get<JournalNotesRepository>().getNoteById(fixtureId(3)))
        assertEquals(CaptureDevice.WATCH, note.capturedOn)
        assertEquals(CaptureActivity.MOVING, note.capturedDuring)
        val draft = requireNotNull(koin.get<JournalRepository>().getDraft(fixtureId(10)))
        return JSONObject()
            .put("historicalCreatedAt", note.creationTimestamp.toEpochMilliseconds())
            .put("draftCreatedAt", draft.createdAt.toEpochMilliseconds())
            .also { proof ->
                if (destination) {
                    assertEquals(
                        OFFLINE_DRAFT_EDIT,
                        draft.blocks
                            .filterIsInstance<SerializableTextBlock>()
                            .single()
                            .content,
                    )
                    val offline = requireNotNull(koin.get<JournalNotesRepository>().getNoteById(fixtureId(8))) as JournalNote.Text
                    assertEquals("Written offline on replacement phone", offline.content)
                    proof.put("offlineCreatedAt", offline.creationTimestamp.toEpochMilliseconds())
                }
            }
    }

    private suspend fun awaitUploadedProof(
        proof: JSONObject,
        device: String,
    ) {
        val api = koin.get<CloudApiClient>()
        var observation = "No server response observed"
        try {
            withTimeout(120_000) {
                while (true) {
                    val token = requireNotNull(koin.get<SessionStorage>().getSession()).accessToken
                    val draftResult = api.getDraftChanges(token, 0)
                    val contentResult = api.getContentChanges(token, 0)
                    val draft =
                        draftResult
                            .getOrNull()
                            ?.drafts
                            ?.singleOrNull { it.id == fixtureId(10).toString() }
                    val notes =
                        contentResult
                            .getOrNull()
                            ?.changes
                            .orEmpty()
                    val historical = notes.singleOrNull { it.id == fixtureId(3).toString() }
                    val offline = notes.singleOrNull { it.id == fixtureId(8).toString() }
                    observation = "draftDevice=${draft?.deviceId?.value}; historical=${historical != null}; offline=${offline != null}; " +
                        "draftFailure=${draftResult.exceptionOrNull()?.javaClass?.simpleName}; " +
                        "contentFailure=${contentResult.exceptionOrNull()?.javaClass?.simpleName}"
                    if (draft?.deviceId?.value == device && historical != null && offline != null) {
                        assertEquals(proof.getLong("draftCreatedAt"), draft.createdAt)
                        assertEquals(proof.getLong("historicalCreatedAt"), historical.createdAt)
                        assertEquals(proof.getLong("offlineCreatedAt"), offline.createdAt)
                        assertTrue(!historical.capturedOn.isNullOrBlank() && !historical.capturedDuring.isNullOrBlank())
                        assertCloudCapture(token)
                        break
                    }
                    delay(500)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw AssertionError("Production startup sync did not upload replacement attribution within 120s; $observation")
        }
    }

    private suspend fun assertCloudCapture(token: String) {
        val api = koin.get<CloudApiClient>()
        val cipher = koin.get<SyncPayloadCipher>()
        val decoded = DefaultCloudContentDataSource(api, cipher).getContentChanges(token, Instant.fromEpochMilliseconds(0)).getOrThrow()
        assertTrue(decoded.unreadable.isEmpty() && decoded.failures.isEmpty(), "The server content snapshot must decode completely")
        val retained = decoded.changes.single { it.uid == fixtureId(3) }
        assertEquals(CaptureDevice.WATCH, retained.capturedOn)
        assertEquals(CaptureActivity.MOVING, retained.capturedDuring)
        val drafts = DefaultCloudDraftDataSource(api, cipher).getDraftChanges(token, Instant.fromEpochMilliseconds(0)).getOrThrow()
        assertTrue(drafts.unreadable.isEmpty() && drafts.incompatible.isEmpty() && drafts.failures.isEmpty())
        val draft = drafts.changes.single { it.id == fixtureId(10) }
        assertEquals(
            OFFLINE_DRAFT_EDIT,
            draft.richDraft
                ?.blocks
                ?.filterIsInstance<SerializableTextBlock>()
                ?.single()
                ?.content,
        )
    }

    private suspend fun retainedJournals(): List<Pair<String, String>> {
        val journals = koin.get<JournalRepository>().allJournalsObserved.first()
        assertEquals(2, journals.size, "Only the owned native transfer fixture is eligible")
        assertTrue(journals.any { it.title == "Native transfer journal" })
        return journals.map { it.id.toString() to it.title }.sortedBy { it.first }
    }

    private suspend fun identity(): JSONObject {
        val manager = koin.get<IdentityKeyManager>()
        assertTrue(manager.hasIdentityKey(), "The native transfer must retain its existing encryption identity")
        return JSONObject()
            .put("owner", koin.get<CanonicalOwnerProvider>().getCanonicalOwnerId())
            .put(
                "device",
                koin
                    .get<DeviceIdProvider>()
                    .getDeviceId()
                    .value
                    .toString(),
            ).put("fingerprint", IdentityKeyFingerprint.of(manager.getIdentityKey(), koin.get<CryptoManager>()))
            .put("origin", koin.get<LogDateConfigRepository>().getCurrentBackendUrl())
    }

    private fun assertSameIdentity(
        expected: JSONObject,
        actual: JSONObject,
    ) {
        listOf("owner", "device", "fingerprint").forEach { field ->
            assertEquals(expected.getString(field), actual.getString(field), "Enrollment must retain $field")
        }
        if (expected.has("origin")) assertEquals(expected.getString("origin"), actual.getString("origin"))
        assertEquals(fixture.origin, actual.getString("origin"))
    }

    private fun writeReceipt(receipt: JSONObject) {
        val atomic = AtomicFile(receiptFile)
        val stream = atomic.startWrite()
        try {
            stream.write(receipt.toString().encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}

private fun fixtureId(number: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")

private const val OFFLINE_DRAFT_EDIT = "Unfinished native-transfer draft, continued offline on the replacement phone"
