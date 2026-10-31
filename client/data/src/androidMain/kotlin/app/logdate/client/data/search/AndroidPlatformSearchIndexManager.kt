package app.logdate.client.data.search

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appsearch.app.AppSearchSchema
import androidx.appsearch.app.AppSearchSession
import androidx.appsearch.app.GenericDocument
import androidx.appsearch.app.PutDocumentsRequest
import androidx.appsearch.app.SearchResult
import androidx.appsearch.app.SearchSpec
import androidx.appsearch.app.SetSchemaRequest
import androidx.appsearch.platformstorage.PlatformStorage
import app.logdate.client.database.dao.SearchDao
import app.logdate.client.database.dao.SearchResultEntity
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.repository.search.SearchContentType
import com.google.common.util.concurrent.ListenableFuture
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant
import kotlin.uuid.Uuid
import app.logdate.client.repository.search.SearchResult as RepositorySearchResult

class AndroidPlatformSearchIndexManager(
    context: Context,
    private val searchDao: SearchDao,
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val externalScope: CoroutineScope,
    private val databaseName: String = DEFAULT_DATABASE_NAME,
) {
    private val appContext = context.applicationContext
    private val searchExecutor =
        Executor { runnable ->
            externalScope.launch(Dispatchers.IO) {
                runnable.run()
            }
        }

    private val sessionMutex = Mutex()
    private val syncMutex = Mutex()

    @Volatile
    private var session: AppSearchSession? = null

    @Volatile
    private var syncObserverStarted = false

    @Volatile
    private var lastAppliedVisibility: Boolean? = null

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun observeIndexedGeneration(): Flow<Long?> = preferencesDataSource.observeAndroidPlatformSearchIndexedGeneration()

    fun ensureStarted() {
        if (!isSupported() || syncObserverStarted) {
            return
        }

        synchronized(this) {
            if (syncObserverStarted) {
                return
            }
            syncObserverStarted = true
        }

        externalScope.launch(Dispatchers.IO) {
            try {
                searchDao.rebuildIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.e("Search index rebuild failed; system search may be incomplete", error)
            }
            combine(
                searchDao.observeGeneration(),
                preferencesDataSource.observeSystemSearchVisibilityEnabled(),
            ) { generation, isVisible ->
                generation to isVisible
            }.distinctUntilChanged()
                .collect { (generation, isVisible) ->
                    runCatching {
                        syncIndex(
                            roomGeneration = generation ?: 0L,
                            systemVisibilityEnabled = isVisible,
                        )
                    }.onFailure { error ->
                        Napier.w("Failed to synchronize Android AppSearch index", error)
                    }
                }
        }
    }

    internal suspend fun search(
        preparedQuery: PreparedFtsQuery,
        limit: Int,
    ): List<RepositorySearchResult> {
        if (!isSupported() || preparedQuery.usesExplicitSyntax || preparedQuery.tokens.isEmpty()) {
            return emptyList()
        }

        val roomGeneration = searchDao.getGeneration() ?: 0L
        val systemVisibilityEnabled = preferencesDataSource.getSystemSearchVisibilityEnabled()
        syncIndex(
            roomGeneration = roomGeneration,
            systemVisibilityEnabled = systemVisibilityEnabled,
        )

        val appSearchSession = getSession() ?: return emptyList()
        val searchResults =
            appSearchSession
                .search(
                    preparedQuery.tokens.joinToString(" "),
                    buildSearchSpec(
                        limit = limit,
                        isSingleCharacterQuery = preparedQuery.isSingleCharacterPlainQuery,
                    ),
                ).getNextPageAsync()
                .awaitValue()

        return searchResults.map { result -> result.toRepositorySearchResult() }
    }

    private suspend fun syncIndex(
        roomGeneration: Long,
        systemVisibilityEnabled: Boolean,
    ) {
        if (!isSupported()) {
            return
        }

        syncMutex.withLock {
            val appSearchSession = getSession() ?: return
            val indexedGeneration = preferencesDataSource.getAndroidPlatformSearchIndexedGeneration()
            val indexedSchemaVersion = preferencesDataSource.getAndroidPlatformSearchSchemaVersion()
            val indexedNamespaces = appSearchSession.getNamespacesAsync().awaitValue()
            val expectedIndexedContentMissing =
                roomGeneration > 0L &&
                    indexedNamespaces.none { namespace -> namespace in indexedContentTypes }
            val needsSchemaRefresh =
                indexedSchemaVersion != CURRENT_SCHEMA_VERSION ||
                    lastAppliedVisibility != systemVisibilityEnabled ||
                    expectedIndexedContentMissing
            val needsRebuild =
                roomGeneration != indexedGeneration ||
                    indexedSchemaVersion != CURRENT_SCHEMA_VERSION ||
                    expectedIndexedContentMissing

            if (needsSchemaRefresh) {
                appSearchSession
                    .setSchemaAsync(buildSchemaRequest(systemVisibilityEnabled))
                    .awaitValue()
                lastAppliedVisibility = systemVisibilityEnabled
            }

            if (!needsRebuild) {
                return
            }

            rebuildIndex(appSearchSession)
            preferencesDataSource.setAndroidPlatformSearchIndexState(
                generation = roomGeneration,
                schemaVersion = CURRENT_SCHEMA_VERSION,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun getSession(): AppSearchSession? {
        session?.let { return it }

        return sessionMutex.withLock {
            session?.let { return it }

            val searchContext =
                PlatformStorage.SearchContext
                    .Builder(appContext, databaseName)
                    .setWorkerExecutor(searchExecutor)
                    .build()
            val createdSession =
                runCatching {
                    PlatformStorage.createSearchSessionAsync(searchContext).awaitValue()
                }.getOrElse { error ->
                    Napier.w("Android AppSearch PlatformStorage is unavailable; using Room search fallback", error)
                    return null
                }
            session = createdSession
            createdSession
        }
    }

    private suspend fun rebuildIndex(appSearchSession: AppSearchSession) {
        clearIndexedDocuments(appSearchSession)

        // The Room DAO query backing this hits `entries_fts`. When the database is in recovery
        // mode (in-memory framework SQLite, which lacks FTS5), SearchIndexBootstrapper skips the
        // virtual table entirely and the query throws "no such table". Tolerate it so the
        // AppSearch index just stays empty until recovery restores the encrypted database.
        val indexedEntries =
            runCatching { searchDao.getIndexedEntries() }
                .onFailure { error ->
                    Napier.w("Failed to read FTS-backed entries; AppSearch index will be empty until recovery", error)
                }.getOrDefault(emptyList())
        if (indexedEntries.isEmpty()) {
            appSearchSession.requestFlushAsync().awaitValue()
            return
        }

        indexedEntries
            .chunked(PUT_BATCH_SIZE)
            .forEach { batch ->
                val documents = batch.map { entry -> entry.toGenericDocument() }
                appSearchSession
                    .putAsync(
                        PutDocumentsRequest
                            .Builder()
                            .addGenericDocuments(documents)
                            .build(),
                    ).awaitValue()
            }

        appSearchSession.requestFlushAsync().awaitValue()
    }

    private suspend fun clearIndexedDocuments(appSearchSession: AppSearchSession) {
        val indexedNamespaces =
            appSearchSession.getNamespacesAsync().awaitValue().filter { namespace ->
                namespace in indexedContentTypes
            }
        if (indexedNamespaces.isEmpty()) {
            return
        }

        appSearchSession
            .removeAsync(
                "",
                SearchSpec
                    .Builder()
                    .addFilterNamespaces(indexedNamespaces)
                    .build(),
            ).awaitValue()
    }

    private fun buildSchemaRequest(systemVisibilityEnabled: Boolean): SetSchemaRequest {
        val builder =
            SetSchemaRequest
                .Builder()
                .addSchemas(appSearchSchemas)
                .setVersion(CURRENT_SCHEMA_VERSION)

        schemaTypesByContentType.values.forEach { schemaType ->
            builder.setSchemaTypeDisplayedBySystem(schemaType, systemVisibilityEnabled)
        }

        return builder.build()
    }

    private fun buildSearchSpec(
        limit: Int,
        isSingleCharacterQuery: Boolean,
    ): SearchSpec =
        SearchSpec
            .Builder()
            .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
            .setResultCountPerPage(limit)
            .setRankingStrategy(
                if (isSingleCharacterQuery) {
                    SearchSpec.RANKING_STRATEGY_CREATION_TIMESTAMP
                } else {
                    SearchSpec.RANKING_STRATEGY_RELEVANCE_SCORE
                },
            ).setSnippetCount(1)
            .setSnippetCountPerProperty(1)
            .setMaxSnippetSize(
                if (isSingleCharacterQuery) {
                    SHORT_QUERY_SNIPPET_SIZE
                } else {
                    DEFAULT_SNIPPET_SIZE
                },
            ).addFilterSchemas(schemaTypesByContentType.values)
            .build()

    private fun SearchResult.toRepositorySearchResult(): RepositorySearchResult {
        val document = getGenericDocument()
        val uid =
            document.getPropertyString(PROPERTY_UID)
                ?: document.getId().substringAfter(DOCUMENT_ID_SEPARATOR)
        val contentTypeValue =
            document.getPropertyString(PROPERTY_CONTENT_TYPE)
                ?: contentTypeForSchemaType(document.getSchemaType()).ftsValue
        val snippet =
            getMatchInfos()
                .asSequence()
                .map { matchInfo -> matchInfo.snippet.toString() }
                .firstOrNull { value -> value.isNotBlank() }
                ?: document.getPropertyString(PROPERTY_PRIMARY_TEXT).orEmpty()

        return RepositorySearchResult(
            uid = Uuid.parse(uid),
            content = snippet,
            created = Instant.fromEpochMilliseconds(document.getCreationTimestampMillis()),
            contentType = SearchContentType.fromFtsValue(contentTypeValue),
            rank = getRankingSignal(),
        )
    }

    private fun SearchResultEntity.toGenericDocument(): GenericDocument {
        val contentTypeValue = SearchContentType.fromFtsValue(contentType).ftsValue
        val schemaType = schemaTypeForContentType(contentTypeValue)
        val builder = GenericDocument.Builder<GenericDocument.Builder<*>>(contentTypeValue, toDocumentId(), schemaType)

        builder.setCreationTimestampMillis(created)
        builder.setPropertyString(PROPERTY_UID, uid)
        builder.setPropertyString(PROPERTY_CONTENT_TYPE, contentTypeValue)
        builder.setPropertyString(PROPERTY_PRIMARY_TEXT, content)

        return builder.build()
    }

    private fun SearchResultEntity.toDocumentId(): String = "$contentType$DOCUMENT_ID_SEPARATOR$uid"

    private fun schemaTypeForContentType(contentType: String): String = schemaTypesByContentType[contentType] ?: TEXT_NOTE_SCHEMA

    private fun contentTypeForSchemaType(schemaType: String): SearchContentType =
        schemaTypesByContentType.entries
            .firstOrNull { (_, mappedSchemaType) -> mappedSchemaType == schemaType }
            ?.key
            ?.let(SearchContentType::fromFtsValue)
            ?: SearchContentType.TEXT_NOTE

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val DEFAULT_DATABASE_NAME = "logdate_search_v1"
        const val DEFAULT_SNIPPET_SIZE = 160
        const val DOCUMENT_ID_SEPARATOR = ":"
        const val PROPERTY_CONTENT_TYPE = "contentType"
        const val PROPERTY_PRIMARY_TEXT = "primaryText"
        const val PROPERTY_UID = "uid"
        const val PUT_BATCH_SIZE = 256
        const val SHORT_QUERY_SNIPPET_SIZE = 96
        const val JOURNAL_SCHEMA = "JournalDocument"
        const val MEDIA_CAPTION_SCHEMA = "MediaCaptionDocument"
        const val PLACE_SCHEMA = "PlaceDocument"
        const val POSTCARD_SCHEMA = "PostcardDocument"
        const val REWIND_SCHEMA = "RewindDocument"
        const val STICKER_SCHEMA = "StickerDocument"
        const val TEXT_NOTE_SCHEMA = "TextNoteDocument"
        const val TRANSCRIPTION_SCHEMA = "TranscriptionDocument"

        val schemaTypesByContentType =
            mapOf(
                SearchContentType.TEXT_NOTE.ftsValue to TEXT_NOTE_SCHEMA,
                SearchContentType.TRANSCRIPTION.ftsValue to TRANSCRIPTION_SCHEMA,
                SearchContentType.JOURNAL.ftsValue to JOURNAL_SCHEMA,
                SearchContentType.MEDIA_CAPTION.ftsValue to MEDIA_CAPTION_SCHEMA,
                SearchContentType.PLACE.ftsValue to PLACE_SCHEMA,
                SearchContentType.REWIND.ftsValue to REWIND_SCHEMA,
                SearchContentType.STICKER.ftsValue to STICKER_SCHEMA,
                SearchContentType.POSTCARD.ftsValue to POSTCARD_SCHEMA,
            )

        val indexedContentTypes = schemaTypesByContentType.keys

        val appSearchSchemas =
            schemaTypesByContentType.values.map { schemaType ->
                AppSearchSchema
                    .Builder(schemaType)
                    .addProperty(
                        AppSearchSchema
                            .StringPropertyConfig
                            .Builder(PROPERTY_UID)
                            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                            .build(),
                    ).addProperty(
                        AppSearchSchema
                            .StringPropertyConfig
                            .Builder(PROPERTY_CONTENT_TYPE)
                            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                            .build(),
                    ).addProperty(
                        AppSearchSchema
                            .StringPropertyConfig
                            .Builder(PROPERTY_PRIMARY_TEXT)
                            .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                            .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                            .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                            .build(),
                    ).build()
            }
    }
}

private suspend fun <T> ListenableFuture<T>.awaitValue(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                runCatching { get() }
                    .onSuccess { value -> continuation.resume(value) }
                    .onFailure { error -> continuation.resumeWithException(error) }
            },
            DirectExecutor,
        )

        continuation.invokeOnCancellation {
            cancel(true)
        }
    }

private object DirectExecutor : Executor {
    override fun execute(command: Runnable) {
        command.run()
    }
}
