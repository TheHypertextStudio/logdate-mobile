package app.logdate.client.e2e

import android.app.job.JobScheduler
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.MainActivity
import app.logdate.client.data.restore.scheduleRetainedJournalReconciliation
import app.logdate.client.database.DatabaseStartupMonitor
import app.logdate.client.database.DatabaseStartupState
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.encryption.PassphraseBackupStore
import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import app.logdate.client.device.restore.RetainedJournalWorkspace
import app.logdate.client.device.restore.retainedJournalWorkspace
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaPayload
import app.logdate.client.media.storage.androidMediaFileResolver
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.shared.model.Journal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Uses the real recovery UI, persistent reconciliation job, and three host-separated app phases. */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RetainedJournalMergeRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val koin get() = GlobalContext.get()
    private val fixtureDirectory get() = File(context.noBackupFilesDir, "retained-journal-merge-runtime")
    private val stateFile get() = File(fixtureDirectory, "state.json")
    private val sourceKeyFile get() = File(fixtureDirectory, "source-key")

    @Test
    fun aSeedRealAppAndRemoveOnlyActiveKeyCopies() =
        runBlocking {
            requireStage("seed")
            RetainedJournalMergeFixtureSupport.requireAcceptanceDevice(context)
            check(!fixtureDirectory.exists()) {
                "Run on a fresh managed installation; do not overwrite an earlier fixture"
            }
            check(!context.getDatabasePath(DATABASE_NAME).exists()) {
                "Use a fresh managed installation; existing journal data must not be changed"
            }
            assertNull(
                retainedJournalWorkspace(context).current(),
                "A fresh fixture cannot inherit an earlier recovery session",
            )

            var firstProcessId = 0
            var sourceMediaPath: File? = null
            var state: JSONObject? = null
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitStartupReady()
                scenario.onActivity { firstProcessId = Process.myPid() }

                val preferences = koin.get<app.logdate.client.datastore.LogdatePreferencesDataSource>()
                // Prepare a legitimate returning-user source install through production APIs.
                koin.get<UserStateRepository>().setIsOnboardingComplete(true)
                preferences.setShouldShowBiometric(false)

                val journals = koin.get<JournalRepository>()
                val notes = koin.get<JournalNotesRepository>()
                val media = koin.get<MediaManager>()
                val now = Clock.System.now()
                val journalId = Uuid.random()
                val conflictNoteId = Uuid.random()
                val deletedNoteId = Uuid.random()
                val imageNoteId = Uuid.random()
                val draftId = Uuid.random()
                val imageBytes = RetainedJournalMergeFixtureSupport.pngFixture()
                val imageRef =
                    media.saveMedia(
                        MediaPayload("retained.png", "image/png", imageBytes.size.toLong(), imageBytes),
                    )
                sourceMediaPath = File(java.net.URI(imageRef))
                val portableImageRef = androidMediaFileResolver(context).storedReference(imageRef)
                assertTrue(portableImageRef.startsWith("logdate-media://library/objects/sha256/"))

                journals.create(Journal(id = journalId, title = "Source journal", created = now, lastUpdated = now))
                notes.create(JournalNote.Text(conflictNoteId, now, now, "Source conflict"), journalId)
                notes.create(JournalNote.Text(deletedNoteId, now, now, "Source row deleted on replacement"), journalId)
                notes.create(JournalNote.Image(imageNoteId, now, now, portableImageRef), journalId)
                journals.saveDraft(
                    RetainedJournalMergeFixtureSupport.draft(draftId, journalId, "source unfinished edit", now),
                )
                val recoveryScope = assertNotNull(journals.getJournalRecoveryScope())
                assertEquals(recoveryScope, notes.getNoteRecoveryScope())

                val sourceKey =
                    assertNotNull(
                        PassphraseBackupStore(context).read(),
                        "A ready app must have a portable database key",
                    )
                assertEquals(32, sourceKey.size)
                check(fixtureDirectory.mkdirs())
                FileOutputStream(sourceKeyFile).use { output ->
                    output.write(sourceKey)
                    output.fd.sync()
                }
                assertNotNull(koin.get<SecureStorage>().getString(DATABASE_KEY_NAME))
                state =
                    JSONObject()
                        .put("firstProcessId", firstProcessId)
                        .put("journalId", journalId.toString())
                        .put("conflictNoteId", conflictNoteId.toString())
                        .put("deletedNoteId", deletedNoteId.toString())
                        .put("imageNoteId", imageNoteId.toString())
                        .put("draftId", draftId.toString())
                        .put("createdAt", now.toEpochMilliseconds())
                        .put("imageRef", portableImageRef)
                        .put("imageSha256", RetainedJournalMergeFixtureSupport.sha256(imageBytes))
                        .put("ownerId", recoveryScope.ownerId)
                        .put("serverOrigin", recoveryScope.serverOrigin)
                RetainedTranscriptionFixtureSupport.seed(assertNotNull(state), fixtureDirectory)
                sourceKey.fill(0)
            }

            // The app has closed its live database handle. Keep the source DB and media intact;
            // only clear the active copies of its key so the next process must use recovery UI.
            koin.get<LogDateDatabase>().close()
            koin.get<SecureStorage>().remove(DATABASE_KEY_NAME)
            koin.get<PassphraseBackupStore>().clear()
            assertTrue(
                koin.get<SecureStorage>().getString(DATABASE_KEY_NAME) == null,
                "The working database key was not durably removed",
            )
            assertTrue(koin.get<PassphraseBackupStore>().readCandidates().isEmpty())
            val activeMedia = assertNotNull(sourceMediaPath)
            assertTrue(activeMedia.isFile)
            RetainedJournalMergeFixtureSupport.writeDurably(stateFile, assertNotNull(state).toString())
        }

    @Test
    fun bUseRecoveryUiAndReconcileAfterLateKeyDelivery() =
        runBlocking {
            requireStage("recover")
            RetainedJournalMergeFixtureSupport.requireAcceptanceDevice(context)
            assertTrue(stateFile.isFile && sourceKeyFile.isFile, "Run the seed phase first")
            val state = JSONObject(stateFile.readText())
            assertNoWorkingKey()
            val workspace = retainedJournalWorkspace(context)
            assertNull(workspace.current(), "The recovery action must create the WORKING generation")

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitStartupFailure()
                val startupDetail = assertIsRecoveryRequiredDetail()
                assertTrue(startupDetail.startsWith("DatabaseKeyRecoveryRequiredException:"), startupDetail)
                assertNoWorkingKey()
                var recoveryProcessId = 0
                scenario.onActivity { recoveryProcessId = Process.myPid() }
                assertTrue(
                    recoveryProcessId != state.getInt("firstProcessId"),
                    "Host force-stop must create a new app process",
                )

                val device = UiDevice.getInstance(instrumentation)
                val evidence = File(fixtureDirectory, "retained-merge-evidence").apply { mkdirs() }
                assertTrue(
                    device.wait(Until.hasObject(By.text(OPEN_RECOVERY_OPTIONS)), UI_TIMEOUT_MS),
                    "Missing-key startup must offer the real recovery action",
                )
                RetainedJournalMergeFixtureSupport.captureScreenshot(instrumentation, evidence, "missing-key.png")
                device.findObject(By.text(OPEN_RECOVERY_OPTIONS)).click()
                awaitStartupReady()
                assertRepositoryScope(state)

                val session = assertNotNull(workspace.current())
                assertEquals(RetainedJournalWorkspace.Phase.WORKING, session.phase)
                val source = workspace.retainedSources().getValue(session.id)
                assertTrue(source.containsKey("source_scope.json"))
                assertTrue(source.containsKey("files/logdate.preferences_pb"))
                assertTrue(source.keys.none { it.endsWith("passphrase_backup") })
                val originalMedia =
                    source.getValue("files/media/" + state.getString("imageRef").removePrefix("logdate-media://library/"))
                assertTrue(
                    originalMedia.isFile,
                    "Recovery must verify the source media before touching the working copy",
                )
                assertEquals(
                    state.getString("imageSha256"),
                    RetainedJournalMergeFixtureSupport.sha256(originalMedia.readBytes()),
                )

                val mediaRef = state.getString("imageRef")
                val activeMedia = File(assertNotNull(androidMediaFileResolver(context).filePath(mediaRef)))
                assertTrue(activeMedia.isFile, "The source media should still exist before recovery starts")
                check(activeMedia.delete()) {
                    "Remove only the working canonical media file to force retained-source import"
                }
                assertTrue(!activeMedia.exists(), "The working original must be absent before retained-source import")
                RetainedTranscriptionFixtureSupport.removeWorkingOriginal(state, source)

                val journals = koin.get<JournalRepository>()
                val notes = koin.get<JournalNotesRepository>()
                val media = koin.get<MediaManager>()
                val now = Instant.fromEpochMilliseconds(state.getLong("createdAt"))
                val journalId = Uuid.parse(state.getString("journalId"))
                val conflictNoteId = Uuid.parse(state.getString("conflictNoteId"))
                val deletedNoteId = Uuid.parse(state.getString("deletedNoteId"))
                val draftId = Uuid.parse(state.getString("draftId"))
                assertNull(
                    journals.getJournalById(journalId),
                    "The replacement database starts without the source journal",
                )
                journals.create(
                    Journal(id = journalId, title = "Working journal edit", created = now, lastUpdated = now),
                )
                notes.create(JournalNote.Text(conflictNoteId, now, now, "Working text edit"), journalId)
                notes.removeById(deletedNoteId)
                journals.saveDraft(
                    RetainedJournalMergeFixtureSupport.draft(draftId, journalId, "working unfinished edit", now),
                )

                RetainedTranscriptionFixtureSupport.createWorkingConflictAndDelete(state)
                val sourceKey = sourceKeyFile.readBytes()
                assertEquals(32, sourceKey.size)
                try {
                    val delivery = Uuid.random().toString()
                    koin.get<NativeRestoreDeliveryGate>().withLock {
                        workspace.beginIncomingDelivery(delivery)
                        val encoded = Base64.encode(sourceKey)
                        workspace.captureIncomingFile(
                            delivery,
                            "files/db_recovery/passphrase_backup",
                            encoded.length.toLong(),
                            encoded.byteInputStream(),
                        )
                        workspace.finishIncomingDelivery()
                    }
                    assertTrue(workspace.coherentIncomingPayloads().containsKey(delivery))
                    check(sourceKeyFile.delete()) {
                        "Erase the private key fixture after placing it in verified custody"
                    }
                    assertTrue(
                        scheduleRetainedJournalReconciliation(context),
                        "The production persistent job should queue",
                    )

                    awaitRecoveredContent(state)
                    RetainedJournalMergeFixtureSupport.assertRecoveredContent(
                        state = state,
                        session = session,
                        delivery = delivery,
                        journals = koin.get(),
                        notes = koin.get(),
                        media = koin.get(),
                        database = koin.get(),
                        workspace = workspaceForCurrentState(session),
                    )
                    RetainedTranscriptionFixtureSupport.assertRecovered(state)
                    assertRepositoryScope(state)
                    state
                        .put("session", session.id)
                        .put("delivery", delivery)
                        .put("recoveryProcessId", recoveryProcessId)
                    RetainedJournalMergeFixtureSupport.writeDurably(stateFile, state.toString())
                    RetainedJournalMergeFixtureSupport.captureJournalScreen(
                        context,
                        instrumentation,
                        Uuid.parse(state.getString("journalId")),
                        evidence,
                        "working-journal.png",
                        "Working journal edit",
                        "Working text edit",
                    )
                    val recoveredJournalId =
                        koin
                            .get<JournalRepository>()
                            .allJournalsObserved
                            .first()
                            .single {
                                it.id != Uuid.parse(state.getString("journalId")) && it.title == "Source journal"
                            }.id
                    RetainedJournalMergeFixtureSupport.captureJournalScreen(
                        context,
                        instrumentation,
                        recoveredJournalId,
                        evidence,
                        "recovered-source-journal.png",
                        "Source journal",
                        "Source conflict",
                    )
                } finally {
                    sourceKey.fill(0)
                    sourceKeyFile.delete()
                }
            }
        }

    @Test
    fun cRestartAndReadIdempotentRecoveredContent() =
        runBlocking {
            requireStage("verify")
            RetainedJournalMergeFixtureSupport.requireAcceptanceDevice(context)
            assertTrue(stateFile.isFile, "Run the recovery phase first")
            val state = JSONObject(stateFile.readText())
            val workspace = retainedJournalWorkspace(context)
            val session = assertNotNull(workspace.current())
            assertEquals(state.getString("session"), session.id)
            assertEquals(RetainedJournalWorkspace.Phase.WORKING, session.phase)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitStartupReady()
                var verifyProcessId = 0
                scenario.onActivity { verifyProcessId = Process.myPid() }
                assertTrue(
                    verifyProcessId != state.getInt("recoveryProcessId"),
                    "Host force-stop must restart the app process",
                )

                assertTrue(
                    scheduleRetainedJournalReconciliation(context),
                    "The production persisted reconciliation job should resume after process restart",
                )
                awaitRetainedMergeJobFinished()
                RetainedJournalMergeFixtureSupport.assertRecoveredContent(
                    state = state,
                    session = session,
                    delivery = state.getString("delivery"),
                    journals = koin.get(),
                    notes = koin.get(),
                    media = koin.get(),
                    database = koin.get(),
                    workspace = workspaceForCurrentState(session),
                )
                RetainedTranscriptionFixtureSupport.assertRecovered(state)
                assertRepositoryScope(state)
                state.put("verificationProcessId", verifyProcessId)
                RetainedJournalMergeFixtureSupport.writeDurably(stateFile, state.toString())

                val evidence = File(fixtureDirectory, "retained-merge-evidence").apply { mkdirs() }
                RetainedJournalMergeFixtureSupport.captureScreenshot(instrumentation, evidence, "restarted-home.png")
                RetainedJournalMergeFixtureSupport.captureScreenshot(instrumentation, evidence, "verify.png")
                RetainedJournalMergeFixtureSupport.writeDurably(
                    File(evidence, "acceptance.json"),
                    """
                    {
                      "recoveryUiAction": "Open recovery options",
                      "recoveryUiPreparedSource": true,
                      "hostForceStoppedBetweenPhases": true,
                      "productionJobService": true,
                      "processRestartedAndIdempotent": true,
                      "lateKeyStagedAsVerifiedRetainedPayload": true,
                      "nativeBackupAgentCallback": false,
                      "workingEditsAndDeletionOutboxPreserved": true,
                      "sourceJournalNotesDraftAndMediaRecovered": true,
                      "sourceCustodyRetained": true,
                      "googleProviderAccepted": false
                    }
                    """.trimIndent(),
                )
            }
        }

    private suspend fun awaitStartupReady() {
        withTimeout(STARTUP_TIMEOUT_MS) {
            koin.get<DatabaseStartupMonitor>().state.first { it == DatabaseStartupState.Ready }
        }
    }

    private suspend fun awaitStartupFailure() {
        withTimeout(STARTUP_TIMEOUT_MS) {
            koin.get<DatabaseStartupMonitor>().state.first { it is DatabaseStartupState.RecoveryRequired }
        }
    }

    private suspend fun awaitRetainedMergeJobFinished() {
        withTimeout(RECONCILIATION_TIMEOUT_MS) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            while (scheduler.allPendingJobs.any { it.id == RETAINED_JOURNAL_JOB_ID }) delay(200)
        }
    }

    private fun assertIsRecoveryRequiredDetail(): String =
        (koin.get<DatabaseStartupMonitor>().state.value as? DatabaseStartupState.RecoveryRequired)?.detail
            ?: error("Expected database-key recovery state")

    private suspend fun assertNoWorkingKey() {
        assertTrue(
            koin.get<SecureStorage>().getString(DATABASE_KEY_NAME) == null,
            "The working database key is still present",
        )
        assertTrue(koin.get<PassphraseBackupStore>().readCandidates().isEmpty())
    }

    private suspend fun assertRepositoryScope(state: JSONObject) {
        val expected = JournalMergeScope(state.getString("ownerId"), state.getString("serverOrigin"))
        assertEquals(expected, koin.get<JournalRepository>().getJournalRecoveryScope())
        assertEquals(expected, koin.get<JournalNotesRepository>().getNoteRecoveryScope())
    }

    private suspend fun awaitRecoveredContent(state: JSONObject) {
        val journals = koin.get<JournalRepository>()
        val notes = koin.get<JournalNotesRepository>()
        val journalId = Uuid.parse(state.getString("journalId"))
        val conflictNoteId = Uuid.parse(state.getString("conflictNoteId"))
        val imageNoteId = Uuid.parse(state.getString("imageNoteId"))
        val draftId = Uuid.parse(state.getString("draftId"))
        withTimeout(RECONCILIATION_TIMEOUT_MS) {
            while (true) {
                val sourceJournalReady =
                    journals.allJournalsObserved.first().any { it.title == "Source journal" && it.id != journalId }
                val sourceConflictReady =
                    notes
                        .allNotesObserved
                        .first()
                        .filterIsInstance<JournalNote.Text>()
                        .any { it.content == "Source conflict" }
                val imageReady = notes.getNoteById(imageNoteId) is JournalNote.Image
                val sourceDraftReady =
                    journals.getAllDrafts().any {
                        it.id != draftId && RetainedJournalMergeFixtureSupport.draftText(it) == "source unfinished edit"
                    }
                if (sourceJournalReady && sourceConflictReady && imageReady && sourceDraftReady) return@withTimeout
                delay(200)
            }
        }
    }

    private fun workspaceForCurrentState(session: RetainedJournalWorkspace.Session): RetainedJournalWorkspace =
        retainedJournalWorkspace(context).also { workspace ->
            assertEquals(session.id, workspace.current()?.id)
        }

    private fun requireStage(stage: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString(ARG_STAGE) == stage)
    }

    private companion object {
        const val ARG_STAGE = "retainedJournalMergeStage"
        const val DATABASE_NAME = "logdate"
        const val DATABASE_KEY_NAME = "db_encryption_key"
        const val OPEN_RECOVERY_OPTIONS = "Open recovery options"
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val RECONCILIATION_TIMEOUT_MS = 120_000L
        const val RETAINED_JOURNAL_JOB_ID = 0x4e4a5235
        const val UI_TIMEOUT_MS = 60_000L
    }
}
