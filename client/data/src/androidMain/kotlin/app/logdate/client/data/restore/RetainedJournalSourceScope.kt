package app.logdate.client.data.restore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.logdate.client.device.identity.sameOwnerId
import app.logdate.client.device.restore.verifyNativeJournalSourceReceipt
import app.logdate.shared.model.ServerDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption

/** Scope captured with the source installation; missing binding data is never inferred from current state. */
internal data class RetainedJournalSourceScope(
    val ownerId: String?,
    val bound: Boolean,
    val backendOrigin: String,
    val draftStorageOrigin: String = backendOrigin,
) {
    companion object {
        fun from(
            preferences: Preferences,
            capturedDefaultOrigin: String? = null,
        ): RetainedJournalSourceScope? {
            if (preferences[stringPreferencesKey("identity.pending_history_owner_adoption.v1")] != null) return null
            val rawOrigin = preferences[stringPreferencesKey("backend_url")] ?: capturedDefaultOrigin ?: return null
            val origin = normalizeBackendOrigin(rawOrigin) ?: return null
            preferences[stringPreferencesKey("server_descriptor")]?.let { raw ->
                val descriptor =
                    runCatching { scopeDescriptorJson.decodeFromString<ServerDescriptor>(raw) }.getOrNull()
                        ?: return null
                if (normalizeBackendOrigin(descriptor.serverOrigin) != origin) return null
            }
            val owner = preferences[stringPreferencesKey("identity.canonical_owner_id")]
            val binding = preferences[stringPreferencesKey("identity.canonical_owner_bound")]
            if (binding != null && binding != "true" && binding != "false") return null
            val bound = binding == "true"
            if (!owner.isNullOrBlank() && runCatching { kotlin.uuid.Uuid.parse(owner) }.isFailure) return null
            if (bound && owner.isNullOrBlank()) return null
            return RetainedJournalSourceScope(owner, bound, origin, rawOrigin.trimEnd('/'))
        }
    }
}

@Serializable
internal data class RetainedSourceScopeAnchor(
    val version: Int,
    val readState: String,
    val canonicalOwnerId: String? = null,
    val canonicalOwnerBound: String? = null,
    val backendOrigin: String? = null,
    val originProvenance: String? = null,
)

/** Exact owner and origin equality only. Unbound legacy sources need a committed adoption receipt. */
internal fun RetainedJournalSourceScope.matchesCurrent(current: RetainedJournalSourceScope): Boolean =
    bound == current.bound && !ownerId.isNullOrBlank() && sameOwnerId(ownerId, current.ownerId) && backendOrigin == current.backendOrigin

/** After source-scope admission (including a validated adoption receipt), repositories must match the live scope. */
internal fun RetainedJournalSourceScope.matchesRepositoryScope(
    ownerId: String?,
    serverOrigin: String?,
): Boolean = sameOwnerId(ownerId, this.ownerId) && normalizeBackendOrigin(serverOrigin.orEmpty()) == backendOrigin

internal fun normalizeBackendOrigin(value: String): String? =
    runCatching {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true))
        require(!uri.host.isNullOrBlank() && uri.port in -1..65535)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        val host = uri.host.lowercase()
        val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
        val port =
            uri.port
                .takeUnless { it == -1 || it == defaultPort }
                ?.let { ":$it" }
                .orEmpty()
        "${uri.scheme.lowercase()}://$host$port"
    }.getOrNull()

/** Reads the immutable retained DataStore file without migrations or writes. */
internal suspend fun readRetainedJournalSourceScope(
    file: File,
    anchorFile: File,
    scratchRoot: File,
    databaseFiles: Map<String, File> = emptyMap(),
): RetainedJournalSourceScope? {
    require(Files.isRegularFile(anchorFile.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(anchorFile.toPath()))
    require(anchorFile.length() in 1..MAX_SCOPE_ANCHOR_BYTES)
    val encoded = anchorFile.readText()
    val anchor =
        when (
            Json
                .parseToJsonElement(encoded)
                .jsonObject["version"]
                ?.jsonPrimitive
                ?.intOrNull
        ) {
            1 -> {
                Json.decodeFromString<RetainedSourceScopeAnchor>(encoded)
            }

            2, 3, 4 -> {
                verifyNativeJournalSourceReceipt(anchorFile, file, databaseFiles).let {
                    RetainedSourceScopeAnchor(
                        version = 1,
                        readState = it.readState,
                        canonicalOwnerId = it.canonicalOwnerId,
                        canonicalOwnerBound = it.canonicalOwnerBound,
                        backendOrigin = it.backendOrigin,
                        originProvenance = it.originProvenance,
                    )
                }
            }

            else -> {
                return null
            }
        }
    if (anchor.version != 1 || anchor.readState != "captured") return null
    val backendOrigin = anchor.backendOrigin ?: return null
    val normalizedOrigin = normalizeBackendOrigin(backendOrigin) ?: return null
    val capturedDefault =
        when (anchor.originProvenance) {
            "stored-backend-url" -> null
            "default-at-capture" -> backendOrigin
            else -> return null
        }
    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(file.toPath()) ||
        file.length() !in 1..MAX_RETAINED_PREFERENCES_BYTES
    ) {
        return null
    }
    val preferences = readRetainedPreferencesSnapshot(file, scratchRoot) ?: return null
    val parsed = RetainedJournalSourceScope.from(preferences, capturedDefault) ?: return null
    val anchoredOwner = anchor.canonicalOwnerId
    val anchoredBinding = anchor.canonicalOwnerBound
    if (parsed.ownerId != anchoredOwner ||
        (preferences[stringPreferencesKey("identity.canonical_owner_bound")] ?: "false") != (anchoredBinding ?: "false") ||
        parsed.backendOrigin != normalizedOrigin
    ) {
        return null
    }
    if (anchor.originProvenance == "stored-backend-url" && preferences[stringPreferencesKey("backend_url")] == null) return null
    if (anchor.originProvenance == "default-at-capture" && preferences[stringPreferencesKey("backend_url")] != null) return null
    return parsed.copy(draftStorageOrigin = backendOrigin.trimEnd('/'))
}

internal suspend fun readRetainedPreferencesSnapshot(
    file: File,
    scratchRoot: File,
): Preferences? {
    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath()))
    require(file.length() in 1..MAX_RETAINED_PREFERENCES_BYTES)
    require(Files.isDirectory(scratchRoot.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(scratchRoot.toPath()))
    val scratchDirectory = File(scratchRoot, "preferences-read-${kotlin.uuid.Uuid.random()}")
    check(scratchDirectory.absoluteFile.parentFile?.canonicalFile == scratchRoot.canonicalFile)
    check(!scratchDirectory.exists() && scratchDirectory.mkdirs())
    val scratchFile = File(scratchDirectory, "logdate.preferences_pb")
    val scopeJob = SupervisorJob()
    val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    return try {
        Files.copy(file.toPath(), scratchFile.toPath())
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { scratchFile.canonicalFile }).data.first()
    } finally {
        scopeJob.cancelAndJoin()
        check(scratchDirectory.deleteRecursively()) { "Could not clean retained preference read copy" }
    }
}

private const val MAX_RETAINED_PREFERENCES_BYTES = 4 * 1024 * 1024
private const val MAX_SCOPE_ANCHOR_BYTES = 64 * 1024

private val scopeDescriptorJson = Json { ignoreUnknownKeys = true }

/** The source's scope survives only exact continuity or a committed, origin-bound local adoption. */
internal fun RetainedJournalSourceScope.matchesCurrentOrAdopted(
    current: RetainedJournalSourceScope,
    preferences: Preferences,
): Boolean {
    if (matchesCurrent(current)) return true
    if (bound || !current.bound || ownerId.isNullOrBlank() || current.ownerId.isNullOrBlank()) return false
    val raw = preferences[stringPreferencesKey("identity.completed_history_owner_adoption.v1")] ?: return false
    val receipt = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
    val oldOwner = receipt["oldOwner"]?.jsonPrimitive?.contentOrNull ?: return false
    val newOwner = receipt["newOwner"]?.jsonPrimitive?.contentOrNull ?: return false
    val origin = receipt["origin"]?.jsonPrimitive?.contentOrNull ?: return false
    val device = receipt["device"]?.jsonPrimitive?.contentOrNull ?: return false
    return device.isNotBlank() &&
        sameOwnerId(oldOwner, ownerId) &&
        sameOwnerId(newOwner, current.ownerId) &&
        normalizeBackendOrigin(origin) == backendOrigin &&
        backendOrigin == current.backendOrigin
}
