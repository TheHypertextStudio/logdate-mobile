@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.integration.e2e.harness

import app.logdate.client.data.account.AccountKeyUnlockCoordinator
import app.logdate.client.data.account.AccountKeyUnlockStatus
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.AccountKeyEnvelopeCipher
import app.logdate.client.device.crypto.ContentEncryptionService
import app.logdate.client.device.crypto.DesktopCryptoManager
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.KeyDerivation
import app.logdate.client.device.identity.data.AccountDeviceApi
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.networking.DefaultAccountKeyEnvelopeApi
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import app.logdate.client.repository.transcription.TranscriptEngineMetadata
import app.logdate.client.repository.transcription.TranscriptSegment
import app.logdate.client.repository.transcription.TranscriptSource
import app.logdate.client.repository.transcription.TranscriptSpeaker
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.MediaUploadRequest
import app.logdate.client.sync.crypto.AesGcmMediaPayloadCrypto
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import app.logdate.client.sync.crypto.SyncPayloadCipher
import app.logdate.integration.e2e.fixtures.createAccountWithSyntheticPasskey
import app.logdate.integration.e2e.fixtures.uploadMedia
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import app.logdate.shared.model.sync.LocationHistoryBatchRequest
import app.logdate.shared.model.sync.LocationHistoryChangesResponse
import app.logdate.shared.model.sync.LocationHistoryUpload
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Disposable loopback journal for headless and signed-app native acceptance. */
object NativeJournalSyncHarness {
    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking {
            require(args.size in 1..2 && (args.size == 1 || args[1] == "--interactive")) {
                "Supply a private temporary control directory and optional --interactive"
            }
            val interactive = args.size == 2
            val control = Path.of(args.first())
            Files.createDirectories(control)
            withServerClientHarness {
                val account = apiClient.createAccountWithSyntheticPasskey("turnkey_${Random.nextInt(100000, 999999)}").data
                val token = account.tokens.accessToken
                val identityKey = ByteArray(32) { it.toByte() }
                val mediaKey = ByteArray(32) { (it + 32).toByte() }
                val crypto = DesktopCryptoManager()
                val credential =
                    apiClient
                        .getAccountInfo(token)
                        .getOrThrow()
                        .passkeyCredentialIds
                        .single()
                val secret = ByteArray(32) { (it + 128).toByte() }
                Files.write(control.resolve("fixture-unlock-secret"), secret)
                val storage = HarnessSecrets()
                val identity = IdentityKeyManager(storage, crypto)
                identity.installAccountKey(account.account.id.toString(), identityKey)
                val mediaKeys = MediaPayloadKeyProvider(storage, crypto, identity, KeyDerivation(crypto))
                mediaKeys.installAccountKey(mediaKey)
                val envelopeApi = DefaultAccountKeyEnvelopeApi(httpClient)
                val status =
                    AccountKeyUnlockCoordinator(envelopeApi, AccountKeyEnvelopeCipher(crypto), identity, mediaKeys)
                        .unlockOrProvision(baseUrl, account.account.id.toString(), token, credential, secret, true)
                check(status == AccountKeyUnlockStatus.UNLOCKED) { "Encrypted-envelope provisioning is unavailable" }
                val envelope = envelopeApi.fetch(baseUrl, token, credential).getOrThrow()!!
                val cipher = SyncPayloadCipher(ContentEncryptionService(identity, KeyDerivation(crypto), crypto), identity, crypto)
                val content = DefaultCloudContentDataSource(apiClient, cipher)
                val now = Clock.System.now()
                val initial = JournalNote.Text(creationTimestamp = now, lastUpdated = now, content = "Existing Android journal entry")
                content.uploadNote(token, initial).getOrThrow()
                val bitmap = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
                val pixels = Random(42)
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) bitmap.setRGB(x, y, pixels.nextInt(0x1000000))
                }
                val photo =
                    ByteArrayOutputStream().use { output ->
                        check(ImageIO.write(bitmap, "png", output))
                        output.toByteArray()
                    }
                check(photo.size > 64 * 1024) { "Fixture must cross an encryption chunk boundary" }
                val encryptedPhoto =
                    AesGcmMediaPayloadCrypto(mediaKey)
                        .streamEncryptor()
                        .encrypt(Buffer().apply { write(photo) })
                        .buffered()
                        .use { it.readByteArray() }
                val photoEntry = JournalNote.Image(creationTimestamp = now, lastUpdated = now, mediaRef = "", caption = "Kotlin photo")
                val upload =
                    apiClient
                        .uploadMedia(
                            token,
                            MediaUploadRequest(
                                contentId = photoEntry.uid.toString(),
                                fileName = "fixture.png",
                                mimeType = "image/png",
                                sizeBytes = encryptedPhoto.size.toLong(),
                                data = encryptedPhoto,
                            ),
                        ).getOrThrow()
                content.uploadNote(token, photoEntry.copy(mediaRef = upload.mediaId)).getOrThrow()
                val transcript =
                    TranscriptDocument(
                        revision = 1,
                        status = TranscriptDocumentStatus.FINAL,
                        language = "en-US",
                        segments =
                            listOf(
                                TranscriptSegment(
                                    "s0",
                                    "Kotlin café 🌎 transcript",
                                    0,
                                    400,
                                    speakerId = "speaker-1",
                                    source = TranscriptSource.LOCAL_REFINEMENT,
                                    isFinal = true,
                                ),
                            ),
                        speakers = listOf(TranscriptSpeaker("speaker-1", "Speaker 1")),
                        engine = TranscriptEngineMetadata("kotlin-fixture", modelId = "synthetic"),
                    )
                val audioEntry =
                    JournalNote.Audio(
                        creationTimestamp = now,
                        lastUpdated = now,
                        mediaRef = "",
                        durationMs = 500,
                        caption = "Kotlin audio",
                        transcript = transcript,
                    )
                val pcm = ByteArray(24_000)
                val wav =
                    ByteBuffer
                        .allocate(44 + pcm.size)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .put("RIFF".toByteArray())
                        .putInt(pcm.size + 36)
                        .put("WAVEfmt ".toByteArray())
                        .putInt(16)
                        .putShort(1)
                        .putShort(1)
                        .putInt(24_000)
                        .putInt(48_000)
                        .putShort(2)
                        .putShort(16)
                        .put("data".toByteArray())
                        .putInt(pcm.size)
                        .put(pcm)
                        .array()
                val encryptedAudio =
                    AesGcmMediaPayloadCrypto(mediaKey)
                        .streamEncryptor()
                        .encrypt(Buffer().apply { write(wav) })
                        .buffered()
                        .use { it.readByteArray() }
                val audioUpload =
                    apiClient
                        .uploadMedia(
                            token,
                            MediaUploadRequest(
                                contentId = audioEntry.uid.toString(),
                                fileName = "fixture.wav",
                                mimeType = "audio/wav",
                                sizeBytes = encryptedAudio.size.toLong(),
                                data = encryptedAudio,
                            ),
                        ).getOrThrow()
                content.uploadNote(token, audioEntry.copy(mediaRef = audioUpload.mediaId)).getOrThrow()
                val speechImport = control.resolve("speech-import.wav")
                if (interactive && Files.exists(speechImport)) {
                    val durationMs =
                        AudioSystem.getAudioInputStream(speechImport.toFile()).use { audio ->
                            check(audio.frameLength > 0 && audio.format.frameRate > 0)
                            (audio.frameLength * 1_000 / audio.format.frameRate).toLong()
                        }
                    val imported =
                        JournalNote.Audio(
                            creationTimestamp = now,
                            lastUpdated = now,
                            mediaRef = "",
                            durationMs = durationMs,
                            caption = "Imported speech awaiting automatic transcript",
                        )
                    val encrypted =
                        AesGcmMediaPayloadCrypto(mediaKey)
                            .streamEncryptor()
                            .encrypt(Buffer().apply { write(Files.readAllBytes(speechImport)) })
                            .buffered()
                            .use { it.readByteArray() }
                    val upload =
                        apiClient
                            .uploadMedia(
                                token,
                                MediaUploadRequest(
                                    imported.uid.toString(),
                                    "speech-import.wav",
                                    "audio/wav",
                                    encrypted.size.toLong(),
                                    encrypted,
                                ),
                            ).getOrThrow()
                    content.uploadNote(token, imported.copy(mediaRef = upload.mediaId)).getOrThrow()
                    Files.writeString(control.resolve("speech-import-id"), imported.uid.toString())
                }
                val historyJson =
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                val historyOrigin = baseUrl.removeSuffix("/api/v1").trimEnd('/')
                val historyPlaceId = "harness-place-cafe"
                val seededPlace =
                    SemanticPlace(
                        id = historyPlaceId,
                        name = "Kotlin Cedar Cafe",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        userConfirmed = true,
                        radiusMeters = 45.0,
                    )
                val historyBaseTs = now - 10.minutes
                val historyUploads =
                    buildList {
                        val placePayload =
                            historyJson.encodeToString(HistoryPayload.serializer(), HistoryPayload.Place(seededPlace))
                        add(
                            LocationHistoryUpload(
                                id = seededPlace.id,
                                recordType = "place",
                                payload =
                                    cipher.encryptString(
                                        "location-history:$historyOrigin:${account.account.id}:place:${seededPlace.id}:v1",
                                        placePayload,
                                    ),
                                deviceId = "android-harness",
                                deviceVersion = 1L,
                            ),
                        )
                        for (step in 0..3) {
                            val obsId = "harness-obs-$step"
                            val obs =
                                LocationObservation(
                                    id = obsId,
                                    ownerId = account.account.id.toString(),
                                    deviceId = "android-harness",
                                    timestamp = historyBaseTs + (step * 3).minutes,
                                    latitude = 37.7749,
                                    longitude = -122.4194,
                                    accuracyMeters = 12f,
                                    activity = TravelMode.STILL,
                                    timeZoneId = "UTC",
                                )
                            val obsPayload =
                                historyJson.encodeToString(HistoryPayload.serializer(), HistoryPayload.Observation(obs))
                            add(
                                LocationHistoryUpload(
                                    id = "observation:$obsId",
                                    recordType = "observation",
                                    payload =
                                        cipher.encryptString(
                                            "location-history:$historyOrigin:${account.account.id}:observation:observation:$obsId:v1",
                                            obsPayload,
                                        ),
                                    deviceId = "android-harness",
                                    deviceVersion = (step + 1).toLong(),
                                ),
                            )
                        }
                    }
                val historySeedResp =
                    httpClient.put("$baseUrl/location-history") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(historyJson.encodeToString(LocationHistoryBatchRequest(historyUploads)))
                    }
                check(historySeedResp.status.isSuccess()) { "Failed to seed encrypted location history (${historySeedResp.status})" }
                val fixture =
                    buildJsonObject {
                        put("owner", account.account.id.toString())
                        put("token", token)
                        put("refreshToken", account.tokens.refreshToken)
                        put("api", baseUrl)
                        put("entry", initial.uid.toString())
                        put("credential", credential)
                        put("envelope", envelope)
                        put("audio", audioEntry.uid.toString())
                        put("photo", photoEntry.uid.toString())
                        put("photoHash", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(photo)))
                        put("placeId", historyPlaceId)
                        put("placeTimestampMs", historyBaseTs.toEpochMilliseconds())
                    }.toString().toByteArray()
                val fixtureServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                fixtureServer.createContext("/fixture") { exchange ->
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.responseHeaders.add("Cache-Control", "no-store")
                    exchange.sendResponseHeaders(200, fixture.size.toLong())
                    exchange.responseBody.use { it.write(fixture) }
                }
                fixtureServer.start()
                Files.writeString(control.resolve("ready"), "http://127.0.0.1:${fixtureServer.address.port}/fixture")
                try {
                    suspend fun inspectRecordings(): List<JournalNote.Audio> {
                        val downloaded = content.getContentChanges(token, Instant.fromEpochMilliseconds(0)).getOrThrow()
                        check(downloaded.unreadable.isEmpty()) { "Kotlin could not decrypt native records" }
                        val recordings = downloaded.changes.filterIsInstance<JournalNote.Audio>().filter { it.uid != audioEntry.uid }
                        val snapshot =
                            buildJsonArray {
                                recordings.forEach { note ->
                                    add(
                                        buildJsonObject {
                                            put("id", note.uid.toString())
                                            put("caption", note.caption)
                                            put("durationMs", note.durationMs)
                                            put("text", note.transcript?.plainText)
                                            put("status", note.transcript?.status?.name)
                                            put("engine", note.transcript?.engine?.name)
                                            put("segments", note.transcript?.segments?.size ?: 0)
                                        },
                                    )
                                }
                            }
                        Files.writeString(control.resolve("native-recordings.json"), snapshot.toString())
                        return recordings
                    }
                    val deadline = System.currentTimeMillis() + (if (interactive) 60 else 10) * 60 * 1000
                    while (!Files.exists(control.resolve("complete")) && System.currentTimeMillis() < deadline) {
                        if (interactive && Files.exists(control.resolve("inspect"))) {
                            val request = Files.readString(control.resolve("inspect"))
                            val acknowledged = control.resolve("inspected")
                            if (!Files.exists(acknowledged) || Files.readString(acknowledged) != request) {
                                inspectRecordings()
                                Files.writeString(acknowledged, request)
                            }
                        }
                        for (round in 1..2) {
                            val requested = Files.exists(control.resolve("phone-edit-$round"))
                            val confirmed = Files.exists(control.resolve("phone-edited-$round"))
                            if (requested && !confirmed) {
                                content
                                    .updateNote(
                                        token,
                                        initial.copy(content = "Concurrent Android edit $round", lastUpdated = Clock.System.now()),
                                    ).getOrThrow()
                                Files.writeString(control.resolve("phone-edited-$round"), "done")
                            }
                        }
                        if (Files.exists(control.resolve("old-audio-edit")) && !Files.exists(control.resolve("old-audio-edited"))) {
                            val currentAudio =
                                content
                                    .getContentChanges(token, Instant.fromEpochMilliseconds(0))
                                    .getOrThrow()
                                    .changes
                                    .filterIsInstance<JournalNote.Audio>()
                                    .single { it.uid == audioEntry.uid }
                            content
                                .updateNote(
                                    token,
                                    currentAudio.copy(
                                        caption = "Older client caption",
                                        transcript = null,
                                        lastUpdated = Clock.System.now(),
                                    ),
                                ).getOrThrow()
                            Files.writeString(control.resolve("old-audio-edited"), "done")
                        }
                        delay(50)
                    }
                    check(Files.exists(control.resolve("complete"))) { "Mac verification did not finish" }
                    if (interactive) {
                        val recorded = inspectRecordings().filter { it.transcript?.status == TranscriptDocumentStatus.FINAL }
                        check(recorded.any { it.transcript?.plainText?.contains("garden", ignoreCase = true) == true }) {
                            "A native recording with automatically generated speech did not reach the server"
                        }
                        recorded.forEach { note ->
                            check(note.durationMs > 0 && note.transcript?.engine?.name == "apple-speech")
                            val uploaded = apiClient.downloadMedia(token, note.mediaRef).getOrThrow().data
                            check(uploaded.isNotEmpty()) { "Native audio did not upload" }
                            val opaque =
                                apiClient
                                    .getContentChanges(token, 0)
                                    .getOrThrow()
                                    .changes
                                    .single { it.id == note.uid.toString() }
                            check(opaque.transcript.orEmpty().startsWith("LDSE2:")) { "Transcript did not use encrypted sync" }
                        }
                        Files.writeString(
                            control.resolve("verified"),
                            "Kotlin decrypted native automatic transcripts and observed uploaded audio.\n",
                        )
                        return@withServerClientHarness
                    }
                    val macDeviceId = Files.readString(control.resolve("mac-device-id"))
                    val devices =
                        AccountDeviceApi(httpClient).list(
                            OriginBoundSession(
                                baseUrl.removeSuffix("/api/v1"),
                                UserSession(token, account.tokens.refreshToken, account.account.id.toString()),
                            ),
                        )
                    check(devices.single().id == macDeviceId && devices.single().platform == "MACOS") {
                        "Kotlin did not observe the Mac device registration"
                    }
                    check(devices.single().name == "Fixture Mac renamed" && devices.single().appVersion == "0.1.1")
                    val macEnvelope = Files.readAllBytes(control.resolve("mac-envelope"))
                    val restored = AccountKeyEnvelopeCipher(crypto).open(account.account.id.toString(), credential, secret, macEnvelope)
                    check(restored.identity.contentEquals(identityKey) && restored.media.contentEquals(mediaKey))
                    val downloaded = content.getContentChanges(token, Instant.fromEpochMilliseconds(0)).getOrThrow()
                    check(downloaded.unreadable.isEmpty()) { "Kotlin could not decrypt a Mac edit" }
                    val notes = downloaded.changes.filterIsInstance<JournalNote.Text>()
                    check(notes.any { it.uid == initial.uid && it.content == "Kept Mac draft" })
                    check(notes.any { it.content == "New offline Mac entry" })
                    val roundTripAudio = downloaded.changes.filterIsInstance<JournalNote.Audio>().single { it.uid == audioEntry.uid }
                    check(roundTripAudio.transcript?.plainText == "Swift café 🌎 transcript")
                    check(roundTripAudio.transcript?.engine?.name == "swift-fixture")
                    check(
                        roundTripAudio.transcript
                            ?.speakers
                            ?.single()
                            ?.label == "Speaker 1",
                    )
                    check(roundTripAudio.caption == "Older client caption")
                    val historyPullResp =
                        httpClient.get("$baseUrl/location-history") {
                            bearerAuth(token)
                            parameter("since", 0)
                            parameter("limit", 100)
                        }
                    check(historyPullResp.status.isSuccess()) { "Kotlin failed to pull location history edits" }
                    val historyChanges = historyJson.decodeFromString<LocationHistoryChangesResponse>(historyPullResp.bodyAsText())
                    val decryptedHistoryPayloads =
                        historyChanges.records.filter { !it.deleted && it.payload != null }.map { rec ->
                            val plain =
                                cipher.decryptString(
                                    "location-history:$historyOrigin:${account.account.id}:${rec.recordType}:${rec.id}:v1",
                                    rec.payload!!,
                                )
                            historyJson.decodeFromString(HistoryPayload.serializer(), plain)
                        }
                    val renamedCorrection =
                        decryptedHistoryPayloads
                            .filterIsInstance<HistoryPayload.Correction>()
                            .any { correction ->
                                correction.value.targetEvidenceId == "place:$historyPlaceId" &&
                                    correction.value.field == HistoryField.LABEL &&
                                    correction.value.value == "Mac Renamed Cafe"
                            }
                    check(renamedCorrection) { "Kotlin did not decrypt Mac's SemanticPlace LABEL correction" }
                    Files.writeString(
                        control.resolve("verified"),
                        "Kotlin observed the Mac device and decrypted the resolved conflict, location history edit, and new offline entry.\n",
                    )
                } finally {
                    fixtureServer.stop(0)
                    Files.deleteIfExists(control.resolve("ready"))
                }
            }
            Unit
        }
}

private class HarnessSecrets : SecureStorage {
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun getString(key: String): String? = state.value[key]

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        state.value = state.value + (key to value)
    }

    override suspend fun remove(key: String) {
        state.value = state.value - key
    }

    override suspend fun clear() {
        state.value = emptyMap()
    }

    override fun observeString(key: String): Flow<String?> = state.map { it[key] }

    override fun observeAll(): Flow<Map<String, String>> = state

    override suspend fun encrypt(data: ByteArray): ByteArray = error("Not used by this disposable fixture")

    override suspend fun decrypt(data: ByteArray): ByteArray? = error("Not used by this disposable fixture")
}
