package app.logdate.client.e2e

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.database.entities.TranscriptionStatus
import app.logdate.client.datastore.DataStoreKeyValueStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.media.MediaFileSource
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaPayload
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.media.storage.androidMediaFileResolver
import app.logdate.client.media.storage.isRecoveredOriginal
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.sync.AutomaticMediaRecovery
import app.logdate.client.sync.AutomaticMediaRecoveryResult
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.cloud.CloudMediaDataSource
import app.logdate.client.sync.cloud.CloudRequestLocation
import app.logdate.client.sync.cloud.CloudRequestLocationProvider
import app.logdate.client.sync.cloud.MediaFile
import app.logdate.client.sync.cloud.MediaUploadResult
import app.logdate.client.sync.metadata.KeyValueMediaSyncRefStore
import app.logdate.client.sync.metadata.MediaSyncRef
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.diagnostics.DiagnosticReason
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Synthetic attachments only; the network test waits for the harness to remove its metered override. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativeMissingMediaE2ETest : CloudArchiveWorkerFixture() {
    private val koin get() = GlobalContext.get()
    private val media get() = koin.get<MediaManager>()
    private val references get() = koin.get<StoredMediaReferences>()
    private val fixture get() = File(context.noBackupFilesDir, "native-missing-media-acceptance.txt")
    private val png =
        Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=",
            Base64.DEFAULT,
        )

    private fun requireEmulator() {
        require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        require(context.packageName.endsWith(".debug"))
    }

    // The instrumentation process has its own UID; query the app-under-test UID explicitly.
    private fun targetPermission(permission: String): Int =
        context.checkPermission(permission, Process.myPid(), context.applicationInfo.uid)

    private fun ensureApplicationStarted() {
        ensureLogDateApplicationStarted(context.applicationContext as android.app.Application)
    }

    @Test
    fun partialNativeMediaRecognizesMissingOriginalAcrossReferenceSpellings() =
        runBlocking {
            requireEmulator()
            ensureApplicationStarted()
            val resolver = androidMediaFileResolver(context)
            val directory = File(context.filesDir, "media/native-partial-${Uuid.random()}")
            check(directory.mkdirs())
            try {
                val available =
                    listOf("proof.wav", "proof.mp4").flatMap { name ->
                        val bytes =
                            InstrumentationRegistry
                                .getInstrumentation()
                                .context.assets
                                .open(
                                    "recovery/$name",
                                ).use { it.readBytes() }
                        val file = File(directory, name).apply { writeBytes(bytes) }
                        val raw = file.toURI().toString()
                        val owned = requireNotNull(resolver.refFor(file.absolutePath)).toString()
                        assertContentEquals(bytes, media.readMedia(raw).data)
                        assertContentEquals(bytes, media.readMedia(owned).data)
                        listOf(raw, owned)
                    }
                assertPartialNativeMediaStates(File(directory, "missing.png"), available, resolver)
            } finally {
                check(directory.deleteRecursively())
            }
        }

    private suspend fun assertPartialNativeMediaStates(
        missing: File,
        available: List<String>,
        resolver: MediaFileResolver,
    ) {
        val missingOwned = requireNotNull(resolver.refFor(missing.absolutePath)).toString()
        val missingRaw = missing.toURI().toString()
        assertFalse(missing.exists())

        suspend fun settled(
            references: List<String>,
            expected: String = missingOwned,
        ): Boolean = partialNativeMediaSettled(references, expected, resolver, media::exists)

        val rawUnavailable = UnavailableMediaReferences.nativeRestore(missingRaw)
        val ownedUnavailable = UnavailableMediaReferences.nativeRestore(missingOwned)
        val references = listOf(rawUnavailable, ownedUnavailable) + available
        assertEquals(6, references.size)
        assertEquals(missingRaw, UnavailableMediaReferences.nativeSource(rawUnavailable))
        assertTrue(settled(references))
        assertTrue(settled(references, missingRaw))
        assertFalse(settled(listOf(missingRaw, ownedUnavailable) + available))
        assertFalse(settled(available))
        val wrong = UnavailableMediaReferences.nativeRestore(File(missing.parentFile, "wrong.png").toURI().toString())
        assertFalse(settled(listOf(wrong, ownedUnavailable) + available))
    }

    @Test
    fun aSeedProviderCollisionForColdRestore() =
        runBlocking {
            requireEmulator()
            ensureApplicationStarted()
            val uri =
                requireNotNull(
                    context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "native-restore-${Uuid.random()}.png")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LogDateRecoveryAcceptance")
                        },
                    ),
                )
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(png) }
            assertTrue(media.exists(uri.toString()))
            val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
            val note = ImageNoteEntity(contentUri = uri.toString(), created = now, lastUpdated = now)
            val missingPrivateFile = context.filesDir.resolve("media/objects/sha256/${Uuid.random()}.png")
            assertFalse(missingPrivateFile.exists())
            val missingPrivateSource = "file://${missingPrivateFile.absolutePath}"
            val missingPrivateNote = ImageNoteEntity(contentUri = missingPrivateSource, created = now, lastUpdated = now)
            val database = koin.get<LogDateDatabase>()
            database.imageNoteDao().addNote(note)
            database.imageNoteDao().addNote(missingPrivateNote)
            val audio = AudioNoteEntity(contentUri = "content://media/external/audio/media/123", created = now, lastUpdated = now)
            val transcript =
                TranscriptionEntity(
                    noteId = audio.uid,
                    text = "Retained transcript",
                    documentJson = Json.encodeToString(TranscriptDocument.fromPlainText("Retained transcript")),
                    mediaDurationMs = audio.durationMs,
                    status = TranscriptionStatus.COMPLETED,
                    created = now,
                    lastUpdated = now,
                    mediaUri = audio.contentUri,
                )
            koin.get<SyncTransactionManager>().withTransaction {
                database.audioNoteDao().addNote(audio)
                database.transcriptionDao().insertTranscription(transcript)
            }
            fixture.writeText(
                JSONObject()
                    .put(
                        "note",
                        note.uid.toString(),
                    ).put(
                        "source",
                        uri.toString(),
                    ).put(
                        "audio",
                        audio.uid.toString(),
                    ).put("transcript", transcript.id.toString())
                    .put("missingPrivateNote", missingPrivateNote.uid.toString())
                    .put("missingPrivateSource", missingPrivateSource)
                    .put("created", now.toEpochMilliseconds())
                    .toString(),
            )
            // Startup can still be completing while the fixture arms the marker. Either state is
            // valid here: the marker is pending for the next process, or startup already repaired it.
            val marker = File(context.noBackupFilesDir, ".logdate_media_quarantine_pending")
            require(marker.createNewFile()) { "An existing native recovery must finish before this fixture" }
            FileOutputStream(marker).use {
                it.write(Uuid.random().toString().encodeToByteArray())
                it.fd.sync()
            }
            val afterArming = database.imageNoteDao().getNoteOneOff(note.uid)
            val retainedSource = UnavailableMediaReferences.nativeSource(afterArming.contentUri)
            assertTrue(
                afterArming.contentUri == note.contentUri || retainedSource == uri.toString(),
                "Cold restore must retain the original media reference until startup safely quarantines it",
            )
            assertEquals(note.created, afterArming.created)
            assertTrue(
                PostRestoreDetector(context).pendingMediaQuarantineId() != null || retainedSource == uri.toString(),
                "The native delivery must remain pending unless its provider reference was already quarantined",
            )
        }

    @Test
    fun bQuarantinedOriginalSurvivesProcessRestart() =
        runBlocking {
            requireEmulator()
            ensureApplicationStarted()
            require(fixture.exists()) { "Run the collision fixture first" }
            withTimeout(30_000) {
                while (PostRestoreDetector(context).pendingMediaQuarantineId() != null) delay(50)
            }
            val state = JSONObject(fixture.readText())
            val id = Uuid.parse(state.getString("note"))
            val source = state.getString("source")
            val database = koin.get<LogDateDatabase>()
            val note = database.imageNoteDao().getNoteOneOff(id)
            assertEquals(source, UnavailableMediaReferences.nativeSource(note.contentUri))
            assertEquals(state.getLong("created"), note.created.toEpochMilliseconds())
            assertTrue(media.exists(source))
            assertFalse(media.exists(note.contentUri))
            assertFalse(media.isRecoveredOriginal(source, references))
            val missingPrivate = database.imageNoteDao().getNoteOneOff(Uuid.parse(state.getString("missingPrivateNote")))
            assertEquals(state.getString("missingPrivateSource"), UnavailableMediaReferences.nativeSource(missingPrivate.contentUri))
            assertEquals(state.getLong("created"), missingPrivate.created.toEpochMilliseconds())
            val audio = database.audioNoteDao().getNoteOneOff(Uuid.parse(state.getString("audio")))
            assertEquals("content://media/external/audio/media/123", UnavailableMediaReferences.nativeSource(audio.contentUri))
            val transcript = requireNotNull(database.transcriptionDao().getTranscriptionByNoteId(audio.uid))
            assertEquals(state.getString("transcript"), transcript.id.toString())
            assertEquals("Retained transcript", transcript.text)
            assertEquals(audio.contentUri, transcript.mediaUri)
            assertEquals(state.getLong("created"), transcript.lastUpdated.toEpochMilliseconds())
        }

    @Test
    fun cPrivateOriginalWorksWithoutLibraryOrNotificationPermission() =
        runBlocking {
            requireEmulator()
            ensureApplicationStarted()
            for (permission in listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            )) {
                assertEquals(PackageManager.PERMISSION_DENIED, targetPermission(permission), permission)
            }
            val local = media.saveMedia(MediaPayload("denied-permissions.png", "image/png", png.size.toLong(), png))
            assertTrue(media.isRecoveredOriginal(local, references))
            assertContentEquals(png, media.readMedia(local).data)
        }

    @Test
    fun dMeteredRecoveryResumesWhenPlatformAllowsMedia() =
        runBlocking {
            requireEmulator()
            awaitValidatedNativeWifi(context)
            ensureApplicationStarted()
            val policy = koin.get<DataUsagePolicy>()
            val expectedMode =
                if (InstrumentationRegistry.getArguments().getString("restoreNetworkPolicyExpected") ==
                    "restricted"
                ) {
                    DataUsageMode.Restricted
                } else {
                    DataUsageMode.Conservative
                }
            withTimeout(30_000) { policy.policy.first { it == expectedMode } }
            val id = Uuid.random()
            val origin = "https://sync.example"
            val source = "content://media/external/images/media/987654321"
            var downloads = 0
            val (recovery, _) = recoveryFixture(id, source, policy) { downloads++ }
            val scope = UploadScope("account", origin)
            val quarantined = UnavailableMediaReferences.nativeRestore(source)
            val pending =
                assertIs<AutomaticMediaRecoveryResult.Pending>(recovery.recover(id, null, "image", quarantined, quarantined, scope))
            assertEquals(DiagnosticReason.NETWORK_POLICY, pending.reason)
            assertEquals(0, downloads)
            awaitUnrestrictedNativeNetwork(context, policy)
            val recovered =
                assertIs<AutomaticMediaRecoveryResult.Available>(recovery.recover(id, null, "image", quarantined, quarantined, scope))
            assertEquals(1, downloads)
            assertTrue(media.isRecoveredOriginal(recovered.reference, references))
            assertContentEquals(png, media.readMedia(recovered.reference).data)
        }

    @Test
    fun eCorruptCanonicalOriginalsAreRepairedOnce() =
        runBlocking {
            requireEmulator()
            ensureApplicationStarted()
            val payload = MediaPayload("integrity-original.png", "image/png", png.size.toLong(), png)
            val local = media.saveMedia(payload)
            val target = File(requireNotNull(androidMediaFileResolver(context).filePath(local))).canonicalFile
            val originalBytes = target.readBytes()
            assertContentEquals(png, originalBytes)
            val objects = File(context.filesDir, "media/objects/sha256").canonicalFile
            require(target.path.startsWith(objects.path + File.separator))
            val corruptions =
                listOf(
                    png.copyOf(png.size / 2),
                    png.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() },
                )
            try {
                for (corrupted in corruptions) {
                    FileOutputStream(target).use {
                        it.write(corrupted)
                        it.fd.sync()
                    }
                    assertTrue(media.exists(local))
                    assertFalse(media.isRecoveredOriginal(local, references))
                    val id = Uuid.random()
                    var downloads = 0
                    val (recovery, refs) = recoveryFixture(id, local) { downloads++ }
                    val scope = UploadScope("account", "https://sync.example")
                    val recovered =
                        assertIs<AutomaticMediaRecoveryResult.Available>(recovery.recover(id, null, "image", local, local, scope))
                    assertEquals(1, downloads)
                    assertTrue(recovered.reference != local, "Recovery must preserve the existing corrupt path")
                    val replacement = File(URI(recovered.reference)).canonicalFile
                    assertEquals(target.parentFile, replacement.parentFile)
                    assertTrue(replacement.name.startsWith("${target.name.substringBefore('.')}.retained-"))
                    assertContentEquals(corrupted, target.readBytes())
                    assertContentEquals(png, media.readMedia(recovered.reference).data)
                    assertTrue(media.isRecoveredOriginal(recovered.reference, references))
                    assertEquals(recovered.reference, requireNotNull(refs.getScoped(id, scope)).localUri)
                    assertEquals(
                        recovered,
                        assertIs<AutomaticMediaRecoveryResult.Available>(recovery.recover(id, null, "image", local, local, scope)),
                    )
                    assertEquals(1, downloads, "The repaired object must be reused on retry")
                }
            } finally {
                FileOutputStream(target).use {
                    it.write(originalBytes)
                    it.fd.sync()
                }
            }
        }

    private suspend fun recoveryFixture(
        id: Uuid,
        source: String,
        policy: DataUsagePolicy? = null,
        onDownload: () -> Unit,
    ): Pair<AutomaticMediaRecovery, KeyValueMediaSyncRefStore> {
        val origin = "https://sync.example"
        val storage =
            object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                    transform(data.value).also { data.value = it }
            }
        val refs = KeyValueMediaSyncRefStore(DataStoreKeyValueStorage(storage), { origin }, { "account" })
        refs.upsert(MediaSyncRef(id.toString(), source, "$origin/media/original", "original", 1))
        val cloud =
            object : CloudMediaDataSource {
                override suspend fun uploadMedia(
                    accessToken: String,
                    contentId: Uuid,
                    media: MediaFileSource,
                ): Result<MediaUploadResult> = error("No upload")

                override suspend fun downloadMedia(
                    accessToken: String,
                    mediaId: String,
                ): Result<MediaFile> {
                    onDownload()
                    return Result.success(MediaFile(id, "metered-original.png", "image/png", png.size.toLong(), png))
                }
            }
        val account =
            object : CloudAccountRepository by mockk(relaxed = true), CloudRequestLocationProvider {
                override fun captureLocation() = CloudRequestLocation(origin, "$origin/api")

                override fun isCurrentOrigin(origin: String) = origin == "https://sync.example"
            }
        val owner =
            object : CanonicalOwnerProvider {
                override suspend fun getCanonicalOwnerId() = "account"

                override suspend fun hasBoundOwner() = true
            }
        return AutomaticMediaRecovery(
            media,
            refs,
            cloud,
            FakeSessionStorage(UserSession("access", "refresh", "account")),
            account,
            owner,
            DefaultLogDateConfigRepository(initialBackendUrl = origin),
            references,
            policy,
        ) to refs
    }
}
