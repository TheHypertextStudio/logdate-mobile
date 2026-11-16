package app.logdate.client.device.restore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlin.uuid.Uuid

/** Only the real framework completion path may record a new repair; startup may resume that exact intent. */
internal class NativePrivacyRepairIntent(
    private val context: Context,
    private val preferences: DataStore<Preferences>,
    private val readIncoming: suspend (String, String) -> Preferences,
    private val protectedInventory: () -> Map<String, String>,
    private val accountSource: suspend (NativePrivacyRepairAcknowledgement) -> Unit = {},
    private val afterDurableStep: (String) -> Unit = {},
) {
    @Serializable
    private data class Intent(
        val version: Int = 1,
        val identity: NativePrivacyRepairIdentity,
        val cohort: String,
        val manifestSha256: String,
        val protected: Map<String, String>,
        val before: String,
        val after: String,
    )

    private val file = File(context.noBackupFilesDir, NATIVE_PRIVACY_REPAIR_INTENT)

    suspend fun record(
        identity: NativePrivacyRepairIdentity,
        cohort: String,
        manifestSha256: String,
        protected: Map<String, String>,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return resume()
        check(Uuid.parse(cohort).toString() == cohort && manifestSha256.matches(SHA256))
        requireCurrent(identity, protected)
        val incoming = readIncoming(cohort, manifestSha256)
        val current = preferences.data.first()
        val repaired =
            nativePrivacyRepairPolicy(current, incoming, currentPolicyReceived = requireCurrent(identity, protected).privacyReceived)
                ?: return false
        val intent =
            Intent(
                identity = identity,
                cohort = cohort,
                manifestSha256 = manifestSha256,
                protected = protected,
                before = nativePrivacyPreferenceFingerprint(current),
                after = nativePrivacyPreferenceFingerprint(repaired),
            )
        val encoded = Json.encodeToString(intent)
        check(encoded.encodeToByteArray().size <= MAX_INTENT_BYTES)
        requireCurrent(identity, protected)
        currentCoroutineContext().ensureActive()
        retainedJournalWriteAtomic(file, encoded)
        afterDurableStep("intent")
        return resume()
    }

    suspend fun resume(): Boolean {
        currentCoroutineContext().ensureActive()
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val intent = Json.decodeFromString<Intent>(retainedJournalReadBounded(file, MAX_INTENT_BYTES))
        check(intent.version == 1 && Uuid.parse(intent.cohort).toString() == intent.cohort)
        check(listOf(intent.manifestSha256, intent.before, intent.after).all { it.matches(SHA256) })
        check(intent.protected.values.all { it.matches(SHA256) })
        requireCurrent(intent.identity, intent.protected)
        val incoming = readIncoming(intent.cohort, intent.manifestSha256)
        preferences.updateData { current ->
            currentCoroutineContext().ensureActive()
            val currentIdentity = requireCurrent(intent.identity, intent.protected)
            when (nativePrivacyPreferenceFingerprint(current)) {
                intent.after -> {
                    check(NativeRestorePrivacyPolicy.hasKnownPrivacyMetadata(current))
                    current
                }

                intent.before -> {
                    val repaired =
                        checkNotNull(
                            nativePrivacyRepairPolicy(current, incoming, currentPolicyReceived = currentIdentity.privacyReceived),
                        ) {
                            "Native privacy repair is no longer current"
                        }
                    check(nativePrivacyPreferenceFingerprint(repaired) == intent.after)
                    repaired
                }

                else -> {
                    error("Preferences changed after native privacy repair was authorized")
                }
            }
        }
        afterDurableStep("preferences")
        currentCoroutineContext().ensureActive()
        preferences.updateData { current ->
            currentCoroutineContext().ensureActive()
            check(nativePrivacyPreferenceFingerprint(current) == intent.after) { "Preferences changed before privacy acknowledgement" }
            readIncoming(intent.cohort, intent.manifestSha256)
            requireCurrent(intent.identity, intent.protected)
            currentCoroutineContext().ensureActive()
            check(
                PostRestoreDetector(context).acknowledgePrivacyRepair(intent.identity),
            ) { "Native privacy delivery changed before acknowledgement" }
            current
        }
        afterDurableStep("acknowledged")
        currentCoroutineContext().ensureActive()
        accountSource(
            NativePrivacyRepairAcknowledgement(intent.identity, intent.cohort, intent.manifestSha256, intent.protected, intent.after),
        )
        afterDurableStep("accounted")
        currentCoroutineContext().ensureActive()
        Files.delete(file.toPath())
        retainedJournalSyncDirectory(checkNotNull(file.parentFile))
        return true
    }

    private fun requireCurrent(
        identity: NativePrivacyRepairIdentity,
        protected: Map<String, String>,
    ): NativePrivacyRepairIdentity {
        val current =
            checkNotNull(PostRestoreDetector(context).capturePrivacyRepairIdentity()) { "Completed native delivery is no longer available" }
        check(nativePrivacyRepairIdentityMayAdvance(identity, current)) { "Native privacy repair belongs to another delivery" }
        check(protectedInventory() == protected) { "Protected journal inputs changed during native privacy repair" }
        return current
    }

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_INTENT_BYTES = 1024 * 1024
    }
}

/** A receipt for the old incomplete preference file does not acknowledge an unfinished typed-policy repair. */
internal fun hasPendingNativePrivacyRepair(context: Context): Boolean =
    try {
        Files.readAttributes(
            File(context.noBackupFilesDir, NATIVE_PRIVACY_REPAIR_INTENT).toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        true
    } catch (_: NoSuchFileException) {
        false
    }

private const val NATIVE_PRIVACY_REPAIR_INTENT = "native-privacy-repair-intent.json"
