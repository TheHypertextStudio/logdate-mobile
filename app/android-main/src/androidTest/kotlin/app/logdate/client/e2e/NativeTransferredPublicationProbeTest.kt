package app.logdate.client.e2e

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.AndroidAccountManager
import app.logdate.client.device.crypto.CryptoManager
import app.logdate.client.device.crypto.IdentityKeyFingerprint
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.link.BouncyCastleLinkHandshake
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.ensureLogDateApplicationStarted
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.cloud.BackupUploadFileRequest
import app.logdate.client.sync.cloud.BackupUploadResult
import app.logdate.client.sync.cloud.CloudBackupDataSource
import app.logdate.client.sync.datalayer.v2.LinkBundle
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.LinkNonceLedger
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.peer.link.PairKeyVault
import app.logdate.client.sync.peer.link.PhoneLinkExchange
import app.logdate.client.sync.peer.link.WatchLinkExchange
import app.logdate.client.sync.peer.link.valueOrNull
import app.logdate.client.sync.v2.link.LinkPlan
import app.logdate.client.sync.v2.link.LinkTarget
import app.logdate.client.sync.v2.link.PhoneWatchLinkStore
import app.logdate.client.sync.v2.window.WindowManifestStore
import app.logdate.feature.core.export.CloudArchiveCipher
import app.logdate.feature.core.export.CloudBackupWorker
import app.logdate.feature.core.settings.ui.CloudArchiveStage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Host-driven acceptance; all persistent writes belong to an explicitly named synthetic fixture. */
@RunWith(AndroidJUnit4::class)
class NativeTransferredPublicationProbeTest : CloudArchiveWorkerFixture() {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val koin get() = GlobalContext.get()
    private val fixture get() = requireNotNull(arguments.getString("publicationFixture")).also { Uuid.parse(it) }
    private val peer get() = "native-publication-$fixture"
    private val sentinel get() = "native.publication.$fixture"
    private val receipt get() = File(context.noBackupFilesDir, "$peer.json")
    private val platformAccounts get() = AccountManager.get(context)
    private val platformUsername get() = "native-publication-$fixture"
    private val platformOrigin = "https://native-backup-fixture.invalid"
    private val platformAccount get() = Account("$platformUsername@$platformOrigin", "${context.packageName}.account")

    @Test
    fun transferredPublication() =
        runBlocking {
            require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
            require(context.packageName.endsWith(".debug"))
            org.junit.Assume.assumeTrue(arguments.getString("nativePublicationAcceptance") == "true")
            ensureLogDateApplicationStarted(context.applicationContext as Application)
            val journals = koin.get<JournalRepository>().allJournalsObserved.first()
            assertEquals(2, journals.size, "Only the isolated two-journal fixture is eligible")
            assertTrue(journals.any { it.title == "Native transfer journal" })
            when (arguments.getString("publicationStage")) {
                "seed" -> seedSource()
                "verify", "replay" -> verifyDestination(arguments.getString("publicationStage") == "replay")
                "cleanup" -> cleanupSource()
                else -> error("Explicit publication acceptance stage required")
            }
        }

    private suspend fun seedSource() {
        require(File(context.noBackupFilesDir, "native-backup-fixture.json").isFile)
        require(!receipt.exists())
        val source = identity()
        val sealed = exchange(source.getString("device"))
        val links = koin.get<PhoneWatchLinkStore>()
        assertNull(koin.get<PairKeyVault>().get(peer))
        assertNull(links.getForReconciliation(peer))
        source.put("link", sealed.linkId).put("pairSha256", digest(sealed.pairKey))
        writeReceipt(source)
        val target = LinkTarget(peer, "Synthetic restored watch")
        assertNotNull(links.recordSuccess(target, LinkPlan(LinkMode.PAIR), sealed.pairKey, 1, 1, sealed.linkId))
        assertTrue(links.markConfirmed(peer, 2, 2, sealed.linkId))
        val scope = PeerDeliveryContext(sealed.linkId, source.getString("device"), peer, LinkMode.PAIR)
        repeat(3) { koin.get<WindowManifestStore>().reserveRevision(scope) }
        assertEquals(3L, koin.get<WindowManifestStore>().revision(scope), "The source must own a nonzero local counter")
        koin.get<SecureStorage>().putString(sentinel, "owned-source-vault-sentinel")
        seedPlatformAccountTokens()
        source.put("platformAccountTokensSeeded", true)
        writeReceipt(source)
    }

    private suspend fun seedPlatformAccountTokens() {
        assertTrue(
            platformAccounts.getAccountsByType(platformAccount.type).isEmpty(),
            "The owned source must have no preexisting LogDate account",
        )
        val metadata =
            Bundle().apply {
                putString(AndroidAccountManager.KEY_USERNAME, platformUsername)
                putString(AndroidAccountManager.KEY_BACKEND_URL, platformOrigin)
            }
        assertTrue(platformAccounts.addAccountExplicitly(platformAccount, null, metadata))
        platformAccounts.setAuthToken(platformAccount, AndroidAccountManager.TOKEN_TYPE_ACCESS, "synthetic-platform-access-$fixture")
        platformAccounts.setAuthToken(platformAccount, AndroidAccountManager.TOKEN_TYPE_REFRESH, "synthetic-platform-refresh-$fixture")
        val tokens = assertNotNull(AndroidAccountManager(context).getTokens(platformUsername, platformOrigin).getOrThrow())
        assertEquals("synthetic-platform-access-$fixture", tokens.accessToken)
        assertEquals("synthetic-platform-refresh-$fixture", tokens.refreshToken)
    }

    private suspend fun verifyDestination(replay: Boolean) {
        val source = JSONObject(requireNotNull(arguments.getString("sourcePublication")))
        val current = identity()
        assertEquals(source.getString("owner"), current.getString("owner"))
        assertEquals(source.getString("fingerprint"), current.getString("fingerprint"))
        assertNotEquals(source.getString("device"), current.getString("device"))
        assertNull(koin.get<SessionStorage>().getSession(), "A source session must not be restored")
        assertTrue(source.getBoolean("platformAccountTokensSeeded"), "The source must exercise Android's separate token store")
        assertTrue(
            platformAccounts.getAccountsByType(platformAccount.type).isEmpty(),
            "Package restore must not create a source OS account",
        )
        assertNull(AndroidAccountManager(context).getTokens(platformUsername, platformOrigin).getOrThrow())
        assertNull(koin.get<SecureStorage>().getString(sentinel), "The source vault must not transfer")
        val oldPeer = PeerDeliveryContext(source.getString("link"), source.getString("device"), peer, LinkMode.PAIR)
        val manifests = koin.get<WindowManifestStore>()
        assertEquals(0L, manifests.revision(oldPeer), "Installation-local counters must not transfer to the replacement")
        val links = koin.get<PhoneWatchLinkStore>()
        val keys = koin.get<PairKeyVault>()
        if (replay || receipt.exists()) {
            val previous = JSONObject(receipt.readText())
            assertEquals(previous.getString("device"), current.getString("device"))
            if (replay) assertTrue(previous.getBoolean("published"), "Only a completed publication can be replayed")
            assertEquals(previous.getString("link"), assertNotNull(links.getForReconciliation(peer)).linkId)
            assertEquals(previous.getString("pairSha256"), digest(assertNotNull(keys.get(peer))))
            assertFalse(links.markConfirmed(peer, 6, 6, previous.getString("link")))
            val newPeer = oldPeer.copy(linkId = previous.getString("link"), phoneNode = current.getString("device"))
            assertEquals(1L, manifests.revision(newPeer), "Restart must retain the replacement counter")
        } else {
            require(!receipt.exists())
            assertNull(keys.get(peer), "Copied peer trust must lack its excluded source key")
            assertNull(links.getForReconciliation(peer), "Copied success must not authorize the replacement")
            val sealed = exchange(current.getString("device"))
            assertNotEquals(source.getString("link"), sealed.linkId)
            assertNotEquals(source.getString("pairSha256"), digest(sealed.pairKey))
            assertNotNull(
                links.recordSuccess(
                    LinkTarget(peer, "Synthetic restored watch"),
                    LinkPlan(LinkMode.PAIR),
                    sealed.pairKey,
                    3,
                    3,
                    sealed.linkId,
                ),
            )
            assertTrue(links.markConfirmed(peer, 4, 4, sealed.linkId))
            assertFalse(links.markConfirmed(peer, 5, 5, sealed.linkId))
            val newPeer = oldPeer.copy(linkId = sealed.linkId, phoneNode = current.getString("device"))
            assertEquals(0L, manifests.revision(newPeer))
            assertEquals(1L, manifests.reserveRevision(newPeer))
            current.put("link", sealed.linkId).put("pairSha256", digest(sealed.pairKey))
            writeReceipt(current)
        }
        verifyOutgoingArchive(current)
        val completed = JSONObject(receipt.readText()).put("published", true)
        writeReceipt(completed)
        assertNull(koin.get<SessionStorage>().getSession(), "The capture transport must not install authentication")
        assertEquals(0L, manifests.revision(oldPeer), "Publication and replay must not adopt the source counter")
    }

    private suspend fun verifyOutgoingArchive(current: JSONObject) {
        val params = workerParameters()
        archiveProgress.clear()
        val delegate = FakeCloudBackupDataSource(Result.success(BackupUploadResult("synthetic", 1, 1)))
        val encrypted = File(context.cacheDir, "$peer.ldcb")
        val plaintext = File(context.cacheDir, "$peer.zip")
        var uploads = 0
        val transport =
            object : CloudBackupDataSource by delegate {
                override suspend fun uploadBackupFile(
                    accessToken: String,
                    backup: BackupUploadFileRequest,
                ): Result<BackupUploadResult> {
                    assertEquals("synthetic-publication-access", accessToken)
                    assertEquals(current.getString("device"), backup.deviceId)
                    assertEquals(CloudArchiveCipher.MANIFEST, backup.manifest)
                    File(backup.sourcePath.toString()).copyTo(encrypted, overwrite = true)
                    uploads++
                    return delegate.uploadBackupFile(accessToken, backup)
                }
            }
        try {
            val cipher = koin.get<CloudArchiveCipher>()
            val exportEvents = mutableListOf<String>()
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    observedExporter(exportEvents),
                    transport,
                    FakeSessionStorage(
                        UserSession("synthetic-publication-access", "synthetic-publication-refresh", current.getString("owner")),
                    ),
                    koin.get<DeviceIdProvider>(),
                    koin.get<IdentityKeyManager>(),
                    cipher,
                )
            val result = worker.doWork()
            assertTrue(
                result is ListenableWorker.Result.Success,
                "Publication result=${result.javaClass.simpleName}; uploads=$uploads; export=$exportEvents",
            )
            val stages = archiveProgress.mapNotNull { it.getString(CloudBackupWorker.PROGRESS_STAGE) }
            assertTrue(CloudArchiveStage.CHECKING.name in stages, "The worker must report its actual checking progress")
            assertTrue(CloudArchiveStage.UPLOADING.name in stages, "The worker must report its actual uploading progress")
            assertEquals(1, uploads)
            cipher.decrypt(encrypted, plaintext)
            ZipFile(plaintext).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
                val notes = assertNotNull(zip.getEntry("data/notes.json"))
                val text = zip.getInputStream(notes).bufferedReader().use { it.readText() }
                assertTrue(text.contains("Text retained through native backup"))
                assertTrue(text.contains("Written offline on replacement phone"))
                verifyArchiveOriginals(zip)
            }
            assertEquals(current.getString("owner"), koin.get<CanonicalOwnerProvider>().getCanonicalOwnerId())
        } finally {
            encrypted.delete()
            plaintext.delete()
        }
    }

    /** Observe the real export without replacing its data, progress, failures, or container. */
    private fun observedExporter(events: MutableList<String>): ExportArchiveUseCase {
        val real = koin.get<ExportArchiveUseCase>()
        return mockk<ExportArchiveUseCase>().also { observer ->
            every { observer.export(any(), any()) } answers {
                real.export(firstArg(), secondArg()).onEach { progress ->
                    events +=
                        when (progress) {
                            is ArchiveExportProgress.Starting -> {
                                "starting"
                            }

                            is ArchiveExportProgress.InProgress -> {
                                progress.stage.name
                            }

                            is ArchiveExportProgress.Failed -> {
                                "failed:${progress.error}"
                            }

                            is ArchiveExportProgress.Completed -> {
                                val omissions =
                                    progress.summary.scope.omitted
                                        .groupingBy { it.reason }
                                        .eachCount()
                                "completed:omissions=$omissions"
                            }
                        }
                }
            }
        }
    }

    private fun verifyArchiveOriginals(zip: ZipFile) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected =
            listOf("proof.png", "proof.wav", "proof.mp4")
                .map { name ->
                    val bytes = assets.open("recovery/$name").use { it.readBytes() }
                    assertTrue(bytes.isNotEmpty(), "The original fixture must contain bytes: $name")
                    bytes.size.toLong() to digest(bytes)
                }.toSet()
        val actual =
            zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.startsWith("media/") }
                .map { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    assertTrue(bytes.isNotEmpty(), "Published original is empty: ${entry.name}")
                    assertEquals(entry.size, bytes.size.toLong(), "Published original size changed")
                    bytes.size.toLong() to digest(bytes)
                }.toSet()
        assertTrue(actual.containsAll(expected), "The publication must retain every fixture original's exact size and SHA-256")
    }

    private suspend fun identity(): JSONObject {
        val identity = koin.get<IdentityKeyManager>()
        return JSONObject()
            .put("owner", koin.get<CanonicalOwnerProvider>().getCanonicalOwnerId())
            .put(
                "device",
                koin
                    .get<DeviceIdProvider>()
                    .getDeviceId()
                    .value
                    .toString(),
            ).put("fingerprint", IdentityKeyFingerprint.of(identity.getIdentityKey(), koin.get<CryptoManager>()))
    }

    private suspend fun exchange(phoneNode: String): PhoneLinkExchange.Sealed {
        val handshake = BouncyCastleLinkHandshake()
        val phone = PhoneLinkExchange(handshake)
        val watch = WatchLinkExchange(handshake, LinkNonceLedger())
        val started = phone.start(LinkMode.PAIR, phoneNode)
        val accepted = assertNotNull(watch.accept(started.offer, peer, phoneNode).valueOrNull())
        val sealed = assertNotNull(phone.seal(started, accepted.reply, peer, LinkBundle(mode = LinkMode.PAIR)).valueOrNull())
        val opened = assertNotNull(watch.open(accepted, sealed.bundle).valueOrNull())
        assertTrue(phone.verify(sealed, opened.confirm))
        assertEquals(sealed.linkId, opened.linkId)
        assertEquals(digest(sealed.pairKey), digest(opened.pairKey))
        return sealed
    }

    private suspend fun cleanupSource() {
        require(File(context.noBackupFilesDir, "native-backup-fixture.json").isFile)
        if (!receipt.exists()) return
        val source = JSONObject(receipt.readText())
        val scope = PeerDeliveryContext(source.getString("link"), source.getString("device"), peer, LinkMode.PAIR)
        assertEquals(3L, koin.get<WindowManifestStore>().revision(scope), "Transfer must preserve the source counter")
        assertEquals(
            source.getString("device"),
            koin
                .get<DeviceIdProvider>()
                .getDeviceId()
                .value
                .toString(),
        )
        koin.get<PairKeyVault>().get(peer)?.let { key ->
            assertEquals(source.getString("pairSha256"), digest(key))
        }
        if (platformAccounts.getAccountsByType(platformAccount.type).contains(platformAccount)) {
            assertEquals(platformUsername, platformAccounts.getUserData(platformAccount, AndroidAccountManager.KEY_USERNAME))
            assertEquals(platformOrigin, platformAccounts.getUserData(platformAccount, AndroidAccountManager.KEY_BACKEND_URL))
            assertTrue(platformAccounts.removeAccountExplicitly(platformAccount), "Only the owned synthetic account is removed")
        }
        assertNull(AndroidAccountManager(context).getTokens(platformUsername, platformOrigin).getOrThrow())
        koin.get<PhoneWatchLinkStore>().forget(peer)
        koin.get<SecureStorage>().remove(sentinel)
        receipt.delete()
    }

    private fun writeReceipt(value: JSONObject) {
        val atomic = AtomicFile(receipt)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString().encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
