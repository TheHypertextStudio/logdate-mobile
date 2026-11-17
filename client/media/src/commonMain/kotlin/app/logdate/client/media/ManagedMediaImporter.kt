package app.logdate.client.media

import io.github.aakira.napier.Napier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A temporary, private copy of externally-owned media. */
data class StagedMediaFile(
    val sourceFilePath: String,
    val fileName: String,
    val mimeType: String,
)

/** Stages externally-owned media so [MediaManager] can copy it into managed storage. */
interface ManagedMediaImportSource {
    suspend fun stage(sourceUri: String): StagedMediaFile

    suspend fun discard(stagedMedia: StagedMediaFile)
}

/** Tracks a published import until its intended destination has durably claimed the reference. */
interface ManagedMediaPublicationCustody {
    suspend fun reserve(
        destination: String,
        stagedMedia: StagedMediaFile,
    ): String

    suspend fun publish(
        ticketId: String,
        reference: String,
    )

    suspend fun acknowledge(
        ticketId: String,
        reference: String,
    )

    object None : ManagedMediaPublicationCustody {
        override suspend fun reserve(
            destination: String,
            stagedMedia: StagedMediaFile,
        ): String = ""

        override suspend fun publish(
            ticketId: String,
            reference: String,
        ) = Unit

        override suspend fun acknowledge(
            ticketId: String,
            reference: String,
        ) = Unit
    }
}

/**
 * Copies externally-owned media through [MediaManager] so the returned reference no longer
 * depends on the source URI grant. The platform manager still defines final deletion semantics.
 */
class ManagedMediaImporter(
    private val mediaManager: MediaManager,
    private val source: ManagedMediaImportSource,
    private val discardManagedMedia: suspend (String) -> Unit = {},
    private val publicationCustody: ManagedMediaPublicationCustody = ManagedMediaPublicationCustody.None,
) {
    suspend fun importAndClaim(
        destination: String,
        sourceUri: String,
        claim: suspend (String) -> Boolean,
    ): Boolean {
        require(destination.isNotBlank()) { "Managed media destination cannot be blank" }
        require(sourceUri.isNotBlank()) { "Source URI cannot be blank" }
        val stagedMedia = source.stage(sourceUri)
        val ticketId = publicationCustody.reserve(destination, stagedMedia)
        val managedUri =
            mediaManager.saveMediaFromFile(
                sourceFilePath = stagedMedia.sourceFilePath,
                fileName = stagedMedia.fileName,
                mimeType = stagedMedia.mimeType,
            )
        check(managedUri.isNotBlank() && managedUri != sourceUri) { "Managed media import returned an invalid URI" }
        publicationCustody.publish(ticketId, managedUri)
        currentCoroutineContext().ensureActive()
        val claimed = claim(managedUri)
        if (claimed) {
            withContext(NonCancellable) {
                runCatching { publicationCustody.acknowledge(ticketId, managedUri) }
                    .onFailure { error -> Napier.w("Failed to acknowledge claimed media", error) }
                runCatching { source.discard(stagedMedia) }
                    .onFailure { error -> Napier.w("Failed to remove staged media import", error) }
            }
        }
        return claimed
    }

    suspend fun import(sourceUri: String): String {
        require(sourceUri.isNotBlank()) { "Source URI cannot be blank" }
        val stagedMedia = source.stage(sourceUri)
        var ownedManagedUri: String? = null

        return try {
            val ticketId = publicationCustody.reserve("unassigned-managed-import", stagedMedia)
            val managedUri =
                mediaManager.saveMediaFromFile(
                    sourceFilePath = stagedMedia.sourceFilePath,
                    fileName = stagedMedia.fileName,
                    mimeType = stagedMedia.mimeType,
                )
            check(managedUri.isNotBlank()) { "Managed media URI cannot be blank" }
            check(managedUri != sourceUri) { "Managed media import returned the source URI" }
            ownedManagedUri = managedUri
            publicationCustody.publish(ticketId, managedUri)
            currentCoroutineContext().ensureActive()
            managedUri
        } catch (primaryFailure: Throwable) {
            ownedManagedUri?.let { managedUri ->
                try {
                    withContext(NonCancellable) {
                        discardManagedMedia(managedUri)
                    }
                } catch (cleanupFailure: Throwable) {
                    primaryFailure.addSuppressed(cleanupFailure)
                    Napier.e("Failed to discard an unpublished managed media import", cleanupFailure)
                }
            }
            throw primaryFailure
        } finally {
            withContext(NonCancellable) {
                runCatching { source.discard(stagedMedia) }
                    .onFailure { error -> Napier.w("Failed to remove staged media import", error) }
            }
        }
    }
}

internal fun safeImportedFileName(
    sourceName: String?,
    preferredExtension: String?,
): String {
    val safeLeaf =
        sourceName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filter { character ->
                !character.isISOControl() &&
                    (character.isLetterOrDigit() || character in SAFE_FILE_NAME_CHARACTERS)
            }?.trim()
            ?.trim('.')
            ?.take(MAX_IMPORTED_FILE_NAME_LENGTH)
            .orEmpty()
            .ifBlank { DEFAULT_IMPORTED_FILE_NAME }
    val safeExtension =
        preferredExtension
            ?.filter(Char::isLetterOrDigit)
            ?.lowercase()
            .orEmpty()

    if (safeExtension.isBlank()) {
        return DEFAULT_IMPORTED_FILE_NAME
    }

    val currentExtension = safeLeaf.substringAfterLast('.', missingDelimiterValue = "")
    if (currentExtension.equals(safeExtension, ignoreCase = true)) {
        return safeLeaf
    }

    val stem =
        safeLeaf
            .substringBeforeLast('.', missingDelimiterValue = safeLeaf)
            .trimEnd('.', ' ')
            .ifBlank { DEFAULT_IMPORTED_FILE_NAME }
            .take(MAX_IMPORTED_FILE_NAME_LENGTH - safeExtension.length - 1)
    return "$stem.$safeExtension"
}

private const val DEFAULT_IMPORTED_FILE_NAME = "imported_media"
private const val MAX_IMPORTED_FILE_NAME_LENGTH = 120
private const val SAFE_FILE_NAME_CHARACTERS = " ._()-"
