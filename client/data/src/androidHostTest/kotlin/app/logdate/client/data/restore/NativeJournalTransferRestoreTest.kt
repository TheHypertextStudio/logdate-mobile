package app.logdate.client.data.restore

import android.app.Application
import android.content.ContextWrapper
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.logdate.client.device.restore.NATIVE_JOURNAL_TRANSFER_PREFIX
import app.logdate.client.device.restore.NATIVE_MEDIA_INVENTORY_PATH
import app.logdate.client.device.restore.NATIVE_SOURCE_RECEIPT_PATH
import app.logdate.client.device.restore.NativeIncomingJournal
import app.logdate.client.device.restore.NativeJournalMediaInventory
import app.logdate.client.device.restore.NativeJournalReplayCustody
import app.logdate.client.device.restore.NativeJournalRestoreAuthority
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.PostRestoreType
import app.logdate.client.device.restore.pinDirectNativeCameraOriginal
import app.logdate.client.device.restore.verifiedNativeJournalSources
import app.logdate.client.device.restore.verifyNativeJournalSourceReceipt
import app.logdate.client.device.restore.writeNativeJournalMediaInventory
import app.logdate.client.device.restore.writeNativeJournalSourceReceipt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Native SQLCipher validation is the boundary double; installation, state, files, and cleanup are real. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class NativeJournalTransferRestoreTest {
    @Test
    fun `direct v4 camera callback is indexed and acknowledged with its exact receipt cohort`() =
        fixture { f ->
            val camera =
                File(f.files, "camera-captures/selected.mp4").apply {
                    check(parentFile!!.mkdirs())
                    writeText("direct camera original")
                }
            val drafts =
                File(f.current, "files/datastore/entry_drafts.preferences_pb").apply {
                    check(parentFile!!.mkdirs())
                    writeText("pending entry draft state")
                }
            val inventory = File(f.current, NATIVE_MEDIA_INVENTORY_PATH)
            writeNativeJournalMediaInventory(inventory, f.files, setOf("files/camera-captures/selected.mp4"))
            val snapshot = f.payloads()
            writeNativeJournalSourceReceipt(
                File(f.current, NATIVE_SOURCE_RECEIPT_PATH),
                mutablePreferencesOf(
                    stringPreferencesKey("identity.canonical_owner_id") to "347c6231-19f4-49fa-bd1d-58ed34b9be03",
                    stringPreferencesKey("identity.canonical_owner_bound") to "true",
                    stringPreferencesKey("backend_url") to "https://cloud.example",
                ),
                checkNotNull(snapshot["files/logdate.preferences_pb"]),
                mapOf("database/logdate" to checkNotNull(snapshot["database/logdate"])),
                inventory,
                drafts,
            )
            val cohort = Uuid.random().toString()
            val custody = NativeJournalReplayCustody(f.context)
            val payloads = f.payloads()
            val unacceptedKeyPath = "files/db_recovery/passphrase_backup"
            (payloads - unacceptedKeyPath).forEach { (path, file) ->
                file.inputStream().use { custody.captureInCohort(cohort, NATIVE_JOURNAL_TRANSFER_PREFIX + path, file.length(), it) }
            }
            val unacceptedKeyCohort = Uuid.random().toString()
            payloads.getValue(unacceptedKeyPath).inputStream().use {
                custody.captureInCohort(
                    unacceptedKeyCohort,
                    NATIVE_JOURNAL_TRANSFER_PREFIX + unacceptedKeyPath,
                    payloads.getValue(unacceptedKeyPath).length(),
                    it,
                )
            }
            camera.inputStream().use { custody.captureInCohort(cohort, "files/camera-captures/selected.mp4", camera.length(), it) }

            val accepted = verifiedNativeJournalSources(custody.sealedPayloads()).values.single()
            val receipt = nativeSnapshotChecksum(accepted.getValue("source_scope.json"))
            val sourceFiles = accepted.mapKeys { (path, _) -> if (path == "source_scope.json") NATIVE_SOURCE_RECEIPT_PATH else path }
            val cameraPayload = custody.sealedPayloads().single { it.path == "files/camera-captures/selected.mp4" }
            val index = NativeJournalStagedSourceIndex(f.context)
            index.remember(sourceFiles)
            index.admitAfterMerged(receipt)
            val source = index.admittedSources().getValue(receipt)
            assertEquals(setOf(cameraPayload.file.canonicalPath), source.stagedAuxiliaryPhysicalPaths["files/camera-captures/selected.mp4"])
            val inventoryPayload =
                custody.sealedPayloads().single {
                    it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + NATIVE_MEDIA_INVENTORY_PATH
                }
            assertEquals(
                setOf(cameraPayload.file.canonicalPath, inventoryPayload.file.canonicalPath),
                index.verifiedAuxiliary(receipt, custody.sealedPayloads()).keys,
            )

            custody.acknowledgeMergedSource(receipt, accepted, source.sourceCorePhysicalPaths)

            assertEquals(
                setOf(NATIVE_JOURNAL_TRANSFER_PREFIX + unacceptedKeyPath),
                custody.sealedPayloads().map { it.path }.toSet(),
            )
        }

    @Test
    fun `same cohort staged and direct v4 camera callbacks are both retired after one merge`() =
        fixture { f ->
            val camera =
                File(f.files, "camera-captures/selected.mp4").apply {
                    check(parentFile!!.mkdirs())
                    writeText("same camera original")
                }
            val drafts =
                File(f.current, "files/datastore/entry_drafts.preferences_pb").apply {
                    check(parentFile!!.mkdirs())
                    writeText("pending entry draft state")
                }
            val inventory = File(f.current, NATIVE_MEDIA_INVENTORY_PATH)
            writeNativeJournalMediaInventory(inventory, f.files, setOf("files/camera-captures/selected.mp4"))
            val snapshot = f.payloads()
            writeNativeJournalSourceReceipt(
                File(f.current, NATIVE_SOURCE_RECEIPT_PATH),
                mutablePreferencesOf(
                    stringPreferencesKey("identity.canonical_owner_id") to "347c6231-19f4-49fa-bd1d-58ed34b9be03",
                    stringPreferencesKey("identity.canonical_owner_bound") to "true",
                    stringPreferencesKey("backend_url") to "https://cloud.example",
                ),
                checkNotNull(snapshot["files/logdate.preferences_pb"]),
                mapOf("database/logdate" to checkNotNull(snapshot["database/logdate"])),
                inventory,
                drafts,
            )

            val cohort = Uuid.random().toString()
            val custody = NativeJournalReplayCustody(f.context)
            val payloads = f.payloads()
            val unacceptedKeyPath = "files/db_recovery/passphrase_backup"
            (payloads - unacceptedKeyPath).forEach { (path, file) ->
                file.inputStream().use { custody.captureInCohort(cohort, NATIVE_JOURNAL_TRANSFER_PREFIX + path, file.length(), it) }
            }
            val unacceptedKeyCohort = Uuid.random().toString()
            payloads.getValue(unacceptedKeyPath).inputStream().use {
                custody.captureInCohort(
                    unacceptedKeyCohort,
                    NATIVE_JOURNAL_TRANSFER_PREFIX + unacceptedKeyPath,
                    payloads.getValue(unacceptedKeyPath).length(),
                    it,
                )
            }
            val stagedCamera = File(f.root, "staged-selected.mp4").apply { writeBytes(camera.readBytes()) }
            stagedCamera.inputStream().use {
                custody.captureInCohort(
                    cohort,
                    NATIVE_JOURNAL_TRANSFER_PREFIX + "files/camera-captures/selected.mp4",
                    stagedCamera.length(),
                    it,
                )
            }
            camera.inputStream().use { custody.captureInCohort(cohort, "files/camera-captures/selected.mp4", camera.length(), it) }

            val accepted = verifiedNativeJournalSources(custody.sealedPayloads()).values.single()
            val stagedCameraPayload =
                custody.sealedPayloads().single {
                    it.path ==
                        NATIVE_JOURNAL_TRANSFER_PREFIX + "files/camera-captures/selected.mp4"
                }
            assertEquals(stagedCameraPayload.file.canonicalFile, accepted.getValue("files/camera-captures/selected.mp4").canonicalFile)
            val receipt = nativeSnapshotChecksum(accepted.getValue("source_scope.json"))
            val sourceFiles = accepted.mapKeys { (path, _) -> if (path == "source_scope.json") NATIVE_SOURCE_RECEIPT_PATH else path }
            val index = NativeJournalStagedSourceIndex(f.context)
            index.remember(sourceFiles)
            index.admitAfterMerged(receipt)
            val source = index.admittedSources().getValue(receipt)
            assertEquals(
                setOf(stagedCameraPayload.file.canonicalPath),
                source.stagedAuxiliaryPhysicalPaths["files/camera-captures/selected.mp4"],
            )
            val directCameraPayload = custody.sealedPayloads().single { it.path == "files/camera-captures/selected.mp4" }
            val inventoryPayload =
                custody.sealedPayloads().single {
                    it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + NATIVE_MEDIA_INVENTORY_PATH
                }
            val auxiliary = index.verifiedAuxiliary(receipt, custody.sealedPayloads()).keys
            assertEquals(setOf(stagedCameraPayload.file.canonicalPath, inventoryPayload.file.canonicalPath), auxiliary)
            assertFalse(directCameraPayload.file.canonicalPath in auxiliary)

            custody.acknowledgeMergedSource(receipt, accepted, source.sourceCorePhysicalPaths)

            assertEquals(
                setOf(NATIVE_JOURNAL_TRANSFER_PREFIX + unacceptedKeyPath),
                custody.sealedPayloads().map { it.path }.toSet(),
            )
        }

    @Test
    fun `vacant receiver retains a v4 camera original delivered at its live path for reconciliation`() =
        fixture { f ->
            val camera = f.selectCameraOriginal()
            val path = "files/camera-captures/selected.mp4"

            assertTrue(pinDirectNativeCameraOriginal(f.files, path, camera.length()) { camera.inputStream() })
            assertFalse(File(f.files, "camera-captures").exists(), "The live capture directory receives only imported drafts")

            val restore = f.restore()
            assertTrue(restore.restore())
            f.complete()
            assertTrue(restore.finish())
            val custody = NativeJournalReplayCustody(f.context)
            val source = verifiedNativeJournalSources(custody.sealedPayloads()).values.single()
            assertEquals("direct camera original", source.getValue(path).readText())
            val receipt = nativeSnapshotChecksum(source.getValue("source_scope.json"))
            val index = NativeJournalStagedSourceIndex(f.context)
            index.admitAfterMerged(receipt)
            assertEquals(
                setOf(source.getValue(path).canonicalPath),
                index.admittedSources().getValue(receipt).stagedAuxiliaryPhysicalPaths[path],
            )
        }

    @Test
    fun `vacant receiver drops a live-path camera callback its receipt inventory does not prove`() {
        val path = "files/camera-captures/selected.mp4"
        fixture { f ->
            val camera = f.selectCameraOriginal()
            val pinned = File(f.current, path)
            val truncated = File(f.root, "truncated.mp4").apply { writeText("direct camera") }
            assertFalse(pinDirectNativeCameraOriginal(f.files, path, truncated.length()) { truncated.inputStream() })
            val substituted = File(f.root, "substituted.mp4").apply { writeText("direct camera 0riginal") }
            assertEquals(camera.length(), substituted.length())
            assertTrue(pinDirectNativeCameraOriginal(f.files, path, substituted.length()) { substituted.inputStream() })
            assertFalse(pinned.exists())
            assertFalse(
                pinDirectNativeCameraOriginal(f.files, "files/camera-captures/other.mp4", camera.length()) { camera.inputStream() },
            )
            assertFalse(pinDirectNativeCameraOriginal(f.files, "files/media/selected.mp4", camera.length()) { camera.inputStream() })
            assertEquals(
                listOf("native_journal_transfer"),
                f.files
                    .list()
                    .orEmpty()
                    .toList(),
            )
            assertEquals(listOf("current"), File(f.files, "native_journal_transfer").list().orEmpty().toList())

            assertTrue(pinDirectNativeCameraOriginal(f.files, path, camera.length()) { camera.inputStream() })
            assertEquals("direct camera original", pinned.readText())
            assertFalse(
                pinDirectNativeCameraOriginal(f.files, path, camera.length()) { camera.inputStream() },
                "An original already beside the receipt is not replaced",
            )
        }
        fixture { f ->
            val camera = f.selectCameraOriginal(withDrafts = false)
            assertFalse(pinDirectNativeCameraOriginal(f.files, path, camera.length()) { camera.inputStream() })
            assertFalse(File(f.current, path).exists(), "A version 3 receipt does not authorize a live-path camera original")
        }
        fixture { f ->
            val camera = f.selectCameraOriginal()
            check(f.current.deleteRecursively())
            assertFalse(pinDirectNativeCameraOriginal(f.files, path, camera.length()) { camera.inputStream() })
            assertFalse(f.current.exists(), "A camera callback without a receipt must not create a transfer snapshot")
        }
    }

    @Test
    fun `direct camera callback outside its v4 receipt cohort inventory is never indexed`() {
        val path = "files/camera-captures/selected.mp4"
        val rejected = "Direct camera original does not match its v4 receipt cohort inventory"

        fun assertRejected(
            f: Fixture,
            camera: File,
            cameraInReceiptCohort: Boolean,
        ) {
            val cohort = Uuid.random().toString()
            val cameraCohort = if (cameraInReceiptCohort) cohort else Uuid.random().toString()
            val custody = NativeJournalReplayCustody(f.context)
            f.payloads().forEach { (source, file) ->
                file.inputStream().use { custody.captureInCohort(cohort, NATIVE_JOURNAL_TRANSFER_PREFIX + source, file.length(), it) }
            }
            camera.inputStream().use { custody.captureInCohort(cameraCohort, path, camera.length(), it) }
            val delivered = custody.sealedPayloads()
            val files = delivered.associate { it.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX) to it.file }
            val index = NativeJournalStagedSourceIndex(f.context)

            index.remember(files)

            val receipt = nativeSnapshotChecksum(files.getValue(NATIVE_SOURCE_RECEIPT_PATH))
            index.admitAfterMerged(receipt)
            val source = index.admittedSources().getValue(receipt)
            assertFalse(path in source.pathSha256)
            assertFalse(path in source.stagedAuxiliaryPhysicalPaths)
            assertEquals(
                emptyList(),
                index.verifiedAuxiliary(receipt, delivered).values.filter { it.canonicalFile == files.getValue(path).canonicalFile },
            )
            val failure =
                assertFailsWith<IllegalStateException> {
                    index.remember(
                        files,
                        delivered.associate {
                            it.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX) to
                                checkNotNull(it.cohortId)
                        },
                    )
                }
            assertEquals(rejected, failure.message)
            assertFalse(path in index.admittedSources().getValue(receipt).pathSha256)
        }

        fixture { f -> assertRejected(f, f.selectCameraOriginal(), cameraInReceiptCohort = false) }
        fixture { f -> assertRejected(f, f.selectCameraOriginal(withDrafts = false), cameraInReceiptCohort = true) }
        fixture { f -> assertRejected(f, f.selectCameraOriginal(inventoriedSizeOffset = 1), cameraInReceiptCohort = true) }
        fixture { f ->
            f.selectCameraOriginal()
            val other = File(f.root, "uninventoried.mp4").apply { writeText("another camera original") }
            assertRejected(f, other, cameraInReceiptCohort = true)
        }
    }

    @Test
    fun `direct camera original delivered again in a new cohort retires both exact revisions`() =
        fixture { f ->
            val camera = f.selectCameraOriginal()
            val path = "files/camera-captures/selected.mp4"
            val custody = NativeJournalReplayCustody(f.context)
            val index = NativeJournalStagedSourceIndex(f.context)
            val revisions =
                List(2) {
                    val cohort = Uuid.random().toString()
                    // The accepted source omits the key, which staged auxiliary acknowledgement retires separately.
                    (f.payloads() - "files/db_recovery/passphrase_backup").forEach { (source, file) ->
                        file.inputStream().use {
                            custody.captureInCohort(
                                cohort,
                                NATIVE_JOURNAL_TRANSFER_PREFIX + source,
                                file.length(),
                                it,
                            )
                        }
                    }
                    camera.inputStream().use { custody.captureInCohort(cohort, path, camera.length(), it) }
                    val delivered = custody.sealedPayloadsInCohort(cohort)
                    index.remember(delivered.associate { it.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX) to it.file })
                    delivered.single { it.path == path }.file.canonicalPath
                }.toSet()
            assertEquals(2, revisions.size)
            val receipt = nativeSnapshotChecksum(File(f.current, NATIVE_SOURCE_RECEIPT_PATH))
            index.admitAfterMerged(receipt)
            val admitted = index.admittedSources().getValue(receipt)
            assertEquals(revisions, admitted.stagedAuxiliaryPhysicalPaths[path])
            assertTrue(index.verifiedAuxiliary(receipt, custody.sealedPayloads()).keys.containsAll(revisions))

            val accepted = verifiedNativeJournalSources(custody.sealedPayloads()).values.single()
            custody.acknowledgeMergedSource(receipt, accepted, admitted.sourceCorePhysicalPaths)

            assertFalse(custody.hasInputs())
        }

    @Test
    fun `missing selected key leaves the journal encrypted and completes as missing content`() =
        fixture { f ->
            assertTrue(f.key.delete())
            val authority = NativeJournalRestoreAuthority(f.context)
            authority.requireSnapshot()
            val restore = f.restore { throw MissingNativeJournalTransferKeyException() }

            assertFalse(restore.restore())
            assertFalse(f.database.exists())
            assertTrue(f.current.isDirectory)

            val detector = PostRestoreDetector(f.context)
            detector.recordRestoreCompleted()
            assertEquals(PostRestoreType.CONTENT_MISSING, detector.detect(isOnboarded = true))
            restore.finish()
            f.restore { throw MissingNativeJournalTransferKeyException() }.finish()
            assertTrue(f.current.isDirectory)
            assertTrue(authority.isSnapshotRequired())
        }

    @Test
    fun `original key arriving after missing-content completion installs the retained journal`() =
        fixture { f ->
            val originalKey = f.key.readBytes()
            assertTrue(f.key.delete())
            val authority = NativeJournalRestoreAuthority(f.context)
            authority.requireSnapshot()
            assertFalse(f.restore { throw MissingNativeJournalTransferKeyException() }.restore())
            PostRestoreDetector(f.context).recordRestoreCompleted()
            f.restore { throw MissingNativeJournalTransferKeyException() }.finish()
            assertTrue(authority.isSnapshotRequired())
            assertFalse(f.database.exists())

            f.key.writeBytes(originalKey)
            assertTrue(f.restore().restore())
            assertEquals("immutable sender database", f.database.readText())
            assertEquals("accepted portable key", File(f.files, "db_recovery/passphrase_backup").readText())
            f.complete()
            f.restore().finish()
            assertFalse(authority.isSnapshotRequired())
        }

    @Test
    fun `mismatched encrypted snapshot remains rejected without completing delivery`() =
        fixture { f ->
            NativeJournalRestoreAuthority(f.context).requireSnapshot()
            val restore =
                f.restore {
                    throw IllegalStateException("Encrypted source scope does not match native preferences")
                }

            assertFailsWith<IllegalStateException> { restore.restore() }
            assertEquals(PostRestoreType.RECOVERY_REQUIRED, PostRestoreDetector(f.context).detect(isOnboarded = true))
            assertTrue(f.current.isDirectory)
            assertFalse(f.database.exists())
        }

    @Test
    fun `clean install accepts either source key name and raw legacy DB delivered afterward`() {
        for (keyName in listOf("passphrase_backup", "working_passphrase_backup")) {
            fixture(keyName) { f ->
                // The vacancy was durably captured before these framework-delivered raw bytes.
                NativeJournalRestoreAuthority(f.context).requireFrameworkDatabaseWrite(f.database)
                f.database.writeText("torn framework database")
                NativeJournalRestoreAuthority(f.context).recordFrameworkDatabaseWrite(f.database)
                assertTrue(f.restore().restore())
                assertEquals("immutable sender database", f.database.readText())
                assertEquals("snapshot prefs", File(f.files, "logdate.preferences_pb").readText())
                assertEquals("accepted portable key", File(f.files, "db_recovery/passphrase_backup").readText())
            }
        }
    }

    @Test
    fun `vacant camera transfer installs journal and durably retains verified draft media for reconciliation`() =
        fixture { f ->
            val draftFile =
                File(f.current, "files/datastore/entry_drafts.preferences_pb").apply {
                    checkNotNull(parentFile).mkdirs()
                    writeBytes("verified draft fixture".encodeToByteArray())
                }
            val camera =
                File(f.current, "files/camera-captures/pending.jpg").apply {
                    checkNotNull(parentFile).mkdirs()
                    writeBytes("verified photo fixture".encodeToByteArray())
                }
            val inventory =
                File(f.current, NATIVE_MEDIA_INVENTORY_PATH).apply {
                    writeText(
                        Json.encodeToString(
                            NativeJournalMediaInventory(
                                1,
                                listOf(
                                    NativeJournalMediaInventory.Entry(
                                        "files/camera-captures/pending.jpg",
                                        camera.length(),
                                        nativeSnapshotChecksum(camera),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            writeNativeJournalSourceReceipt(
                File(f.current, NATIVE_SOURCE_RECEIPT_PATH),
                mutablePreferencesOf(
                    stringPreferencesKey("identity.canonical_owner_id") to "347c6231-19f4-49fa-bd1d-58ed34b9be03",
                    stringPreferencesKey("backend_url") to "https://cloud.example",
                ),
                File(f.current, "files/logdate.preferences_pb"),
                mapOf("database/logdate" to File(f.current, "database/logdate")),
                inventory,
                draftFile,
            )

            val restore = f.restore()
            assertTrue(restore.restore())
            assertEquals("immutable sender database", f.database.readText())
            assertEquals("snapshot prefs", File(f.files, "logdate.preferences_pb").readText())
            f.complete()
            assertTrue(restore.finish())
            assertFalse(f.current.exists())
            assertTrue(NativeJournalTransferStaging(f.context).hasPendingStageMarker())
            assertTrue(NativeJournalReplayCustody(f.context).hasInputs())
            val source = verifiedNativeJournalSources(NativeJournalReplayCustody(f.context).sealedPayloads()).values.single()
            assertEquals("verified draft fixture", source.getValue("files/datastore/entry_drafts.preferences_pb").readText())
            assertEquals("verified photo fixture", source.getValue("files/camera-captures/pending.jpg").readText())
            assertTrue(f.restore().finish(), "A process replay must resume the retained handoff")
            assertTrue(NativeJournalTransferStaging(f.context).hasPendingStageMarker())
            f.restore().reconciliationScheduled()
            assertFalse(NativeJournalTransferStaging(f.context).hasPendingStageMarker())
            assertEquals(null, NativeJournalRestoreAuthority(f.context).installedReceipt())
            assertTrue(NativeJournalReplayCustody(f.context).hasInputs(), "Scheduling must not consume source custody")
            assertFalse(f.restore().finish(), "Repeated completion must not re-admit the same camera source")
        }

    @Test
    fun `raw database and WAL callbacks remain valid when each exact revision is recorded`() =
        fixture { f ->
            val authority = NativeJournalRestoreAuthority(f.context)
            val wal = File(f.database.path + "-wal")
            authority.requireFrameworkDatabaseWrite(f.database)
            f.database.writeText("framework database")
            authority.recordFrameworkDatabaseWrite(f.database)
            authority.requireFrameworkDatabaseWrite(wal)
            wal.writeText("framework WAL")
            authority.recordFrameworkDatabaseWrite(wal)
            assertTrue(f.restore().restore())
            assertEquals("immutable sender database", f.database.readText())
            assertFalse(wal.exists())
        }

    @Test
    fun `local database or WAL created after vacancy stays intact while source moves to custody`() {
        for (suffix in listOf("", "-wal")) {
            fixture { f ->
                val local = File(f.database.path + suffix).apply { writeText("local edit after vacancy") }
                assertFalse(f.restore().restore())
                assertEquals("local edit after vacancy", local.readText())
                f.assertStaged()
                assertFalse(PostRestoreDetector(f.context).isNativeRestoreDeliveryComplete())
            }
        }
    }

    @Test
    fun `preexisting preferences or key make a database-free destination occupied`() {
        for (path in listOf("logdate.preferences_pb", "db_recovery/passphrase_backup")) {
            fixture { f ->
                val local =
                    File(f.files, path).apply {
                        checkNotNull(parentFile).mkdirs()
                        writeText("accepted local identity and settings")
                    }
                val authority = NativeJournalRestoreAuthority(f.context)
                authority.begin(resumeInterrupted = false)
                assertTrue(authority.requiresCustodyForIncoming())
                assertFalse(f.restore().restore())
                assertEquals("accepted local identity and settings", local.readText())
                f.assertStaged()
            }
        }
    }

    @Test
    fun `local preference edit after a framework callback redirects later files to custody`() =
        fixture { f ->
            val authority = NativeJournalRestoreAuthority(f.context)
            val preferences = File(f.files, "logdate.preferences_pb")
            preferences.writeText("framework preferences")
            authority.recordFrameworkPortableState()
            preferences.writeText("new local preference edit")
            assertTrue(authority.requiresCustodyForIncoming())
            assertEquals("new local preference edit", preferences.readText())
        }

    @Test
    fun `local WAL edit after a raw DB callback prevents the next framework write`() =
        fixture { f ->
            val authority = NativeJournalRestoreAuthority(f.context)
            authority.requireFrameworkDatabaseWrite(f.database)
            f.database.writeText("framework database")
            authority.recordFrameworkDatabaseWrite(f.database)
            val wal = File(f.database.path + "-wal").apply { writeText("local WAL edit") }
            assertFailsWith<IllegalStateException> { authority.requireFrameworkDatabaseWrite(wal) }
            assertFalse(f.restore().restore())
            assertEquals("framework database", f.database.readText())
            assertEquals("local WAL edit", wal.readText())
            f.assertStaged()
        }

    @Test
    fun `completed snapshot retains verified source owner and origin`() =
        fixture { f ->
            assertTrue(f.restore().restore())
            val authority = NativeJournalRestoreAuthority(f.context)
            val receipt = checkNotNull(authority.installedReceipt())
            authority.rememberCompletedSnapshot(receipt, f.payloads())
            assertEquals("347c6231-19f4-49fa-bd1d-58ed34b9be03", authority.completedSourceReceipt()?.canonicalOwnerId)
            assertEquals("https://cloud.example", authority.completedSourceReceipt()?.backendOrigin)
        }

    @Test
    fun `preexisting destination sidecar without a main database is occupied and preserved`() {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            fixture { f ->
                val sidecar = File(f.database.path + suffix).apply { writeText("retained destination $suffix") }
                NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)
                assertFalse(f.restore().restore())
                assertFalse(f.database.exists())
                assertEquals("retained destination $suffix", sidecar.readText())
                f.assertStaged()
            }
        }
    }

    @Test
    fun `process death after atomic DB replacement resumes identical bytes`() =
        fixture { f ->
            assertTrue(f.restore().restore())
            assertTrue(f.restore().restore())
            assertEquals("immutable sender database", f.database.readText())
        }

    @Test
    fun `edited DB after installation begins is never overwritten by retry`() =
        fixture { f ->
            assertTrue(f.restore().restore())
            f.database.writeText("new user entry after interruption")
            assertFalse(f.restore().restore())
            assertEquals("new user entry after interruption", f.database.readText())
            f.assertStaged()
            assertFalse(PostRestoreDetector(f.context).isNativeRestoreDeliveryComplete())
        }

    @Test
    fun `WAL-only edit after installation is preserved even while main DB bytes are identical`() =
        fixture { f ->
            assertTrue(f.restore().restore())
            val wal = File(f.database.path + "-wal").apply { writeText("new committed WAL-only edit") }
            assertFalse(f.restore().restore())
            assertEquals("new committed WAL-only edit", wal.readText())
            assertEquals("immutable sender database", f.database.readText())
            f.assertStaged()
        }

    @Test
    fun `preference edit after installation is preserved on retry`() =
        fixture { f ->
            assertTrue(f.restore().restore())
            val preferences = File(f.files, "logdate.preferences_pb").apply { writeText("new local settings") }
            assertFalse(f.restore().restore())
            assertEquals("new local settings", preferences.readText())
            assertEquals("immutable sender database", f.database.readText())
            f.assertStaged()
        }

    @Test
    fun `missing receipt fails without completion or removing incoming custody`() =
        fixture { f ->
            check(File(f.current, NATIVE_SOURCE_RECEIPT_PATH).delete())
            assertFailsWith<IllegalStateException> { f.restore().restore() }
            assertFalse(f.database.exists())
            assertTrue(f.current.isDirectory)
            assertFalse(PostRestoreDetector(f.context).isNativeRestoreDeliveryComplete())
        }

    @Test
    fun `occupied local journal refuses otherwise valid incoming install`() =
        fixture { f ->
            f.database.writeText("existing local journal")
            NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)
            assertFalse(f.restore().restore())
            assertEquals("existing local journal", f.database.readText())
            f.assertStaged()
        }

    @Test
    fun `staged source survives a new restore instance and completion without changing local data`() =
        fixture { f ->
            f.database.writeText("accepted local journal")
            NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)
            assertFalse(f.restore().restore())
            f.assertStaged()
            assertFalse(f.restore().restore())
            assertEquals("accepted local journal", f.database.readText())
            f.complete()
            f.restore().finish()
            assertEquals("accepted local journal", f.database.readText())
            assertTrue(NativeJournalReplayCustody(f.context).hasInputs())
        }

    @Test
    fun `interrupted staged copy replaces and retires only its owned partial cohort`() {
        for (sealed in listOf(false, true)) {
            fixture { f ->
                val files = f.payloads()
                val hashes = files.mapValues { nativeSnapshotChecksum(it.value) }
                val receipt = hashes.getValue(NATIVE_SOURCE_RECEIPT_PATH)
                val cohorts = hashes.mapValues { Uuid.random().toString() }
                File(f.noBackup, "native-journal-transfer-staged").writeText(
                    """{"receipt":${Json.encodeToString(
                        receipt,
                    )},"files":${Json.encodeToString(hashes)},"cohorts":${Json.encodeToString(cohorts)}}""",
                )
                val source = files.getValue("database/logdate")
                val oldId = cohorts.getValue("database/logdate")
                val custody = NativeJournalReplayCustody(f.context)
                assertFailsWith<IOException> {
                    custody.captureInCohort(
                        oldId,
                        NATIVE_JOURNAL_TRANSFER_PREFIX + "database/logdate",
                        source.length(),
                        source.readBytes().copyOfRange(0, 1).inputStream(),
                    )
                }
                if (sealed) custody.sealedPayloadsInCohort(oldId)
                f.database.writeText("accepted local journal")
                NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)

                assertFalse(f.restore().restore())

                assertEquals("accepted local journal", f.database.readText())
                assertFalse(custody.hasCohort(oldId))
                f.assertStaged()
            }
        }
    }

    @Test
    fun `staged source retains exact scope and auxiliary hashes after source files are removed`() =
        fixture { f ->
            val receipt = nativeSnapshotChecksum(f.payloads().getValue(NATIVE_SOURCE_RECEIPT_PATH))
            f.database.writeText("accepted local journal")
            NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)
            assertFalse(f.restore().restore())
            f.assertStaged()
            val index = NativeJournalStagedSourceIndex(f.context)
            assertTrue(index.hasRemembered(receipt))
            assertTrue(index.admittedSources().isEmpty())
            val callbacks = NativeJournalReplayCustody(f.context).sealedPayloads()

            index.admitAfterMerged(receipt)

            val source = index.admittedSources().getValue(receipt)
            assertEquals("347c6231-19f4-49fa-bd1d-58ed34b9be03", source.receipt.canonicalOwnerId)
            assertEquals("https://cloud.example", source.receipt.backendOrigin)
            assertEquals(null, source.mediaInventory)
            assertEquals(
                setOf(callbacks.single { it.path.endsWith("passphrase_backup") }.file.canonicalPath),
                index.verifiedAuxiliary(receipt, callbacks).keys,
            )
            assertEquals(
                NATIVE_JOURNAL_TRANSFER_PREFIX + "files/db_recovery/passphrase_backup",
                callbacks.single { it.file == index.verifiedAuxiliary(receipt, callbacks).values.single() }.path,
            )
            val rawKey = File(f.root, "raw-late-key").apply { writeText("accepted portable key") }
            assertEquals(
                emptySet(),
                index
                    .verifiedAuxiliary(
                        receipt,
                        listOf(NativeIncomingJournal.Payload("files/db_recovery/passphrase_backup", rawKey, true, rawKey.length())),
                    ).keys,
            )
            val future = NativeJournalReplayCustody(f.context)
            rawKey.inputStream().use {
                future.capture(NATIVE_JOURNAL_TRANSFER_PREFIX + "files/db_recovery/passphrase_backup", rawKey.length(), it)
            }
            val oldKeyPath = callbacks.single { it.path.endsWith("passphrase_backup") }.file.canonicalPath
            val laterKey =
                future.sealedPayloads().single {
                    it.path == NATIVE_JOURNAL_TRANSFER_PREFIX + "files/db_recovery/passphrase_backup" && it.file.canonicalPath != oldKeyPath
                }
            assertEquals(emptyMap(), index.verifiedAuxiliary(receipt, listOf(laterKey)))
        }

    @Test
    fun `identical receipt in a new callback cohort retires both exact key revisions`() =
        fixture { f ->
            val receipt = nativeSnapshotChecksum(f.payloads().getValue(NATIVE_SOURCE_RECEIPT_PATH))
            f.database.writeText("accepted local journal")
            NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)
            assertFalse(f.restore().restore())
            val custody = NativeJournalReplayCustody(f.context)
            val originals = custody.sealedPayloads()
            val sourceFiles = originals.associate { it.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX) to it.file }
            val callback = NativeJournalReplayCustody(f.context)
            sourceFiles.forEach { (path, file) ->
                file.inputStream().use { callback.capture(NATIVE_JOURNAL_TRANSFER_PREFIX + path, file.length(), it) }
            }
            val oldPaths = originals.mapTo(mutableSetOf()) { it.file.canonicalPath }
            val repeated = callback.sealedPayloads().filter { it.file.canonicalPath !in oldPaths }
            val repeatedFiles = repeated.associate { it.path.removePrefix(NATIVE_JOURNAL_TRANSFER_PREFIX) to it.file }
            val index = NativeJournalStagedSourceIndex(f.context)
            index.admitAfterMerged(receipt)

            index.remember(repeatedFiles)

            val keys =
                index.verifiedAuxiliary(receipt, callback.sealedPayloads()).values.filter {
                    it.readText() == "accepted portable key"
                }
            assertEquals(2, keys.size)
            val accepted = verifiedNativeJournalSources(callback.sealedPayloads()).values.single()
            val admitted = index.admittedSources().getValue(receipt)
            callback.acknowledgeMergedSource(receipt, accepted, admitted.sourceCorePhysicalPaths)
            val verified = index.verifiedAuxiliary(receipt, callback.sealedPayloads()).values.mapTo(mutableSetOf()) { it.canonicalPath }
            callback.acknowledgeStagedAuxiliary(verified, admitted.pathSha256)
            assertFalse(callback.hasInputs())
        }

    @Test
    fun `staged v3 source retains its verified media inventory after source files are removed`() =
        fixture(withInventory = true) { f ->
            val receipt = nativeSnapshotChecksum(f.payloads().getValue(NATIVE_SOURCE_RECEIPT_PATH))
            f.database.writeText("accepted local journal")
            NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)
            assertFalse(f.restore().restore())

            val index = NativeJournalStagedSourceIndex(f.context)
            index.admitAfterMerged(receipt)

            val source = index.admittedSources().getValue(receipt)
            assertEquals(3, source.receipt.version)
            assertEquals(emptyList(), source.mediaInventory?.files)
            val inventoryPayload =
                NativeJournalReplayCustody(f.context).sealedPayloads().single { it.path.endsWith(NATIVE_MEDIA_INVENTORY_PATH) }
            assertTrue(inventoryPayload.file.canonicalPath in index.verifiedAuxiliary(receipt, listOf(inventoryPayload)))
        }

    @Test
    fun `late v3 media inventory can join an already staged and admitted source after restart`() =
        fixture(withInventory = true) { f ->
            val receipt = nativeSnapshotChecksum(f.payloads().getValue(NATIVE_SOURCE_RECEIPT_PATH))
            val lateInventory =
                File(f.root, "late-inventory").apply {
                    writeBytes(f.payloads().getValue(NATIVE_MEDIA_INVENTORY_PATH).readBytes())
                }
            check(File(f.current, NATIVE_MEDIA_INVENTORY_PATH).delete())
            f.database.writeText("accepted local journal")
            NativeJournalRestoreAuthority(f.context).begin(resumeInterrupted = false)
            assertFalse(f.restore().restore())
            val index = NativeJournalStagedSourceIndex(f.context)
            index.admitAfterMerged(receipt)
            assertEquals(null, index.admittedSources().getValue(receipt).mediaInventory)
            val wrong = File(f.root, "wrong-inventory").apply { writeText("wrong") }
            assertFailsWith<IllegalStateException> { index.retainMediaInventory(receipt, wrong) }

            NativeJournalStagedSourceIndex(f.context).retainMediaInventory(receipt, lateInventory)

            val restarted = NativeJournalStagedSourceIndex(f.context)
            assertEquals(
                emptyList(),
                restarted
                    .admittedSources()
                    .getValue(receipt)
                    .mediaInventory
                    ?.files,
            )
            assertEquals(
                setOf(lateInventory.canonicalPath),
                restarted
                    .verifiedAuxiliary(
                        receipt,
                        listOf(
                            NativeIncomingJournal.Payload(
                                NATIVE_JOURNAL_TRANSFER_PREFIX + NATIVE_MEDIA_INVENTORY_PATH,
                                lateInventory,
                                true,
                                lateInventory.length(),
                            ),
                        ),
                    ).keys,
            )
            assertEquals(
                setOf(lateInventory.canonicalPath),
                restarted
                    .verifiedAuxiliary(
                        receipt,
                        listOf(NativeIncomingJournal.Payload(NATIVE_MEDIA_INVENTORY_PATH, lateInventory, true, lateInventory.length())),
                    ).keys,
            )
        }

    @Test
    fun `consumed rename survives missing receipt and exact replay preserves subsequent edit`() =
        fixture { f ->
            assertTrue(f.restore().restore())
            f.complete()
            val authority = NativeJournalRestoreAuthority(f.context)
            val receipt = checkNotNull(authority.installedReceipt())
            val original = f.payloads().mapValues { it.value.readBytes() }
            authority.rememberCompletedSnapshot(receipt, f.payloads())
            authority.beginCleanup(receipt)
            val consumed = File(f.current.parentFile, ".$receipt.consumed")
            Files.move(f.current.toPath(), consumed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            check(File(consumed, NATIVE_SOURCE_RECEIPT_PATH).delete())
            f.restore().finish()
            assertFalse(consumed.exists())
            f.database.writeText("new user edit after completed transfer")
            original.forEach { (path, bytes) ->
                File(f.current, path).apply {
                    checkNotNull(parentFile).mkdirs()
                    writeBytes(bytes)
                }
            }
            PostRestoreDetector(f.context).beginNativeRestoreSession()
            authority.begin(resumeInterrupted = false)
            assertTrue(f.restore().restore())
            assertEquals("new user edit after completed transfer", f.database.readText())
            f.complete()
            f.restore().finish()
            assertFalse(f.current.exists())
        }

    @Test
    fun `empty key-only and unknown partial callbacks are never old replay authority`() =
        fixture { f ->
            assertTrue(f.restore().restore())
            f.complete()
            val authority = NativeJournalRestoreAuthority(f.context)
            authority.rememberCompletedSnapshot(checkNotNull(authority.installedReceipt()), f.payloads())
            assertFalse(authority.matchesCompletedSnapshot(emptyMap()))
            assertFalse(authority.matchesCompletedSnapshot(mapOf("files/db_recovery/passphrase_backup" to f.key)))
            val unknown = File(f.root, "unknown").apply { writeText("new original media") }
            assertFalse(authority.matchesCompletedSnapshot(f.payloads() + ("files/media/new.png" to unknown)))
        }

    private fun fixture(
        keyName: String = "passphrase_backup",
        withInventory: Boolean = false,
        block: (Fixture) -> Unit,
    ) {
        Fixture(keyName, withInventory).use(block)
    }

    private class Fixture(
        keyName: String,
        withInventory: Boolean,
    ) : AutoCloseable {
        val root = Files.createTempDirectory("native-journal-install-").toFile()
        val files = File(root, "files").apply { mkdirs() }
        val noBackup = File(root, "no-backup").apply { mkdirs() }
        val database = File(root, "database/logdate").apply { checkNotNull(parentFile).mkdirs() }
        val current = File(files, "native_journal_transfer/current")
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
                override fun getFilesDir() = this@Fixture.files

                override fun getNoBackupFilesDir() = noBackup

                override fun getApplicationContext() = this

                override fun getDatabasePath(name: String) = File(root, "database/$name")
            }
        val key =
            File(current, "files/db_recovery/$keyName").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("accepted portable key")
            }

        init {
            val incoming =
                File(current, "database/logdate").apply {
                    checkNotNull(parentFile).mkdirs()
                    writeText("immutable sender database")
                }
            val prefs =
                File(current, "files/logdate.preferences_pb").apply {
                    checkNotNull(parentFile).mkdirs()
                    writeText("snapshot prefs")
                }
            val inventory =
                if (withInventory) {
                    File(current, NATIVE_MEDIA_INVENTORY_PATH).apply {
                        checkNotNull(parentFile).mkdirs()
                        writeText(Json.encodeToString(NativeJournalMediaInventory(1, emptyList())))
                    }
                } else {
                    null
                }
            writeNativeJournalSourceReceipt(
                File(current, NATIVE_SOURCE_RECEIPT_PATH),
                mutablePreferencesOf(
                    stringPreferencesKey("identity.canonical_owner_id") to "347c6231-19f4-49fa-bd1d-58ed34b9be03",
                    stringPreferencesKey("backend_url") to "https://cloud.example",
                ),
                prefs,
                mapOf("database/logdate" to incoming),
                inventory,
            )
            PostRestoreDetector(context).beginNativeRestoreSession()
            NativeJournalRestoreAuthority(context).begin(resumeInterrupted = false)
        }

        /** Writes a receipt that selects one sender camera original at its live path; without drafts the receipt is version 3. */
        fun selectCameraOriginal(
            withDrafts: Boolean = true,
            inventoriedSizeOffset: Long = 0,
        ): File {
            val camera =
                File(root, "sender/camera-captures/selected.mp4").apply {
                    check(checkNotNull(parentFile).mkdirs())
                    writeText("direct camera original")
                }
            val drafts =
                File(current, "files/datastore/entry_drafts.preferences_pb").takeIf { withDrafts }?.apply {
                    check(checkNotNull(parentFile).mkdirs())
                    writeText("pending entry draft state")
                }
            val inventory =
                File(current, NATIVE_MEDIA_INVENTORY_PATH).apply {
                    writeText(
                        Json.encodeToString(
                            NativeJournalMediaInventory(
                                1,
                                listOf(
                                    NativeJournalMediaInventory.Entry(
                                        "files/camera-captures/selected.mp4",
                                        camera.length() + inventoriedSizeOffset,
                                        nativeSnapshotChecksum(camera),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            writeNativeJournalSourceReceipt(
                File(current, NATIVE_SOURCE_RECEIPT_PATH),
                mutablePreferencesOf(
                    stringPreferencesKey("identity.canonical_owner_id") to "347c6231-19f4-49fa-bd1d-58ed34b9be03",
                    stringPreferencesKey("identity.canonical_owner_bound") to "true",
                    stringPreferencesKey("backend_url") to "https://cloud.example",
                ),
                File(current, "files/logdate.preferences_pb"),
                mapOf("database/logdate" to File(current, "database/logdate")),
                inventory,
                drafts,
            )
            return camera
        }

        fun payloads() = current.walkTopDown().filter(File::isFile).associateBy { it.relativeTo(current).invariantSeparatorsPath }

        fun assertStaged() {
            assertFalse(current.exists())
            assertTrue(NativeJournalTransferStaging(context).wasStaged())
            val custody = NativeJournalReplayCustody(context)
            assertTrue(custody.hasInputs())
            assertTrue(custody.sealedPayloads().any { it.path.endsWith(NATIVE_SOURCE_RECEIPT_PATH) && it.complete })
        }

        fun restore(validateSnapshot: ((android.content.Context) -> Map<String, File>?)? = null) =
            NativeJournalTransferRestore(
                context,
                { source -> source.copyTo(File(files, "logdate.preferences_pb"), overwrite = true) },
                validateSnapshot ?: {
                    payloads().also { snapshot ->
                        verifyNativeJournalSourceReceipt(
                            checkNotNull(snapshot[NATIVE_SOURCE_RECEIPT_PATH]),
                            checkNotNull(snapshot["files/logdate.preferences_pb"]),
                            mapOf("database/logdate" to checkNotNull(snapshot["database/logdate"])),
                        )
                    }
                },
            )

        fun complete() {
            PostRestoreDetector(context).apply {
                markNativeRestorePayloadReceived(databaseDelivered = true)
                markNativePrivacyPayloadReceived()
                recordRestoreCompleted()
            }
        }

        override fun close() {
            check(root.deleteRecursively())
        }
    }
}
