package app.logdate.client.data.rewind

import app.logdate.client.database.dao.rewind.CachedRewindDao
import app.logdate.client.database.entities.rewind.RewindEntity
import app.logdate.client.database.entities.rewind.RewindImageContentEntity
import app.logdate.client.database.entities.rewind.RewindTextContentEntity
import app.logdate.client.database.entities.rewind.RewindVideoContentEntity
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.client.util.platformIODispatcher
import app.logdate.shared.model.ActivityType
import app.logdate.shared.model.HighlightedQuote
import app.logdate.shared.model.LocationSummary
import app.logdate.shared.model.MapPoint
import app.logdate.shared.model.ReflectionPrompt
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import app.logdate.shared.model.RewindMetadata
import app.logdate.shared.model.TopListItem
import app.logdate.shared.model.TopListKind
import app.logdate.shared.model.WeatherContext
import app.logdate.shared.model.WeekStatsSnapshot
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid
import app.logdate.client.database.dao.rewind.RewindContent as StoredRewindContent

/**
 * A [RewindRepository] that uses a local database as the single source of truth.
 *
 * This implementation handles the storage and retrieval of rewinds, providing
 * a consistent interface for the rest of the application. It uses a Room DAO
 * for persistence and handles the mapping between database entities and domain models.
 */
class OfflineFirstRewindRepository(
    private val cachedRewindDao: CachedRewindDao,
    private val ioDispatcher: CoroutineDispatcher = platformIODispatcher,
    private val mediaClaimFence: MediaClaimFence = MediaClaimFence.Disabled,
) : RewindRepository {
    private val contentJson = Json { ignoreUnknownKeys = true }

    /**
     * Retrieves all rewinds that have been generated.
     *
     * @return Flow emitting a list of all available rewinds
     */
    override fun getAllRewinds(): Flow<List<Rewind>> =
        cachedRewindDao
            .getAllRewinds()
            .map { it.toDomainModels() }

    /**
     * Retrieves a rewind by its unique identifier.
     *
     * @param uid The unique identifier of the rewind to retrieve
     * @return Flow emitting the rewind if found
     */
    override fun getRewind(uid: Uuid): Flow<Rewind> =
        cachedRewindDao
            .getRewindById(uid)
            .map { rewindEntity ->
                rewindEntity.toDomainModel()
            }

    /**
     * Retrieves a rewind for a given time period.
     *
     * @param start The start of the time period
     * @param end The end of the time period
     * @return Flow emitting the rewind for the given period, or null if none exists
     */
    override fun getRewindBetween(
        start: Instant,
        end: Instant,
    ): Flow<Rewind?> =
        cachedRewindDao
            .getRewindForPeriod(start, end)
            .map { rewindEntity ->
                rewindEntity?.toDomainModel()
            }

    /**
     * Checks if a rewind is available for the given time period.
     *
     * @param start The start of the time period
     * @param end The end of the time period
     * @return True if a rewind exists for the period, false otherwise
     */
    override suspend fun isRewindAvailable(
        start: Instant,
        end: Instant,
    ): Boolean =
        withContext(ioDispatcher) {
            cachedRewindDao.rewindExistsForPeriod(start, end)
        }

    /**
     * Creates a new rewind for the given time period.
     *
     * This method is deprecated and will be removed in a future version.
     * Use the GenerateBasicRewindUseCase instead, which handles the entire
     * rewind generation process.
     *
     * @param start The start of the time period
     * @param end The end of the time period
     * @return The created rewind
     */
    @Deprecated("Use GenerateBasicRewindUseCase instead")
    override suspend fun createRewind(
        start: Instant,
        end: Instant,
    ): Rewind =
        throw UnsupportedOperationException(
            "Direct rewind creation is no longer supported. Use GenerateBasicRewindUseCase instead.",
        )

    /**
     * Saves a rewind to the repository.
     *
     * @param rewind The rewind to save
     */
    override suspend fun saveRewind(rewind: Rewind): Unit =
        withContext(ioDispatcher) {
            mediaClaimFence.withClaim {
                try {
                    val rewindEntity = rewind.toEntity()
                    val (textEntities, imageEntities, videoEntities) = rewind.content.toEntities(rewind.uid)
                    cachedRewindDao.insertRewindWithContent(
                        rewind = rewindEntity,
                        textContent = textEntities,
                        imageContent = imageEntities,
                        videoContent = videoEntities,
                    )

                    Napier.d(
                        "Saved rewind: ${rewind.uid} with ${rewind.content.size} content items " +
                            "(${textEntities.size} text, ${imageEntities.size} images, ${videoEntities.size} videos)",
                    )
                } catch (e: Exception) {
                    Napier.e("Failed to save rewind", e)
                    throw e
                }
            }
        }

    override suspend fun replaceRewind(
        existingUid: Uuid,
        replacement: Rewind,
    ): Unit =
        withContext(ioDispatcher) {
            require(replacement.uid == existingUid)
            mediaClaimFence.withClaim {
                val (textEntities, imageEntities, videoEntities) = replacement.content.toEntities(replacement.uid)
                cachedRewindDao.replaceRewind(
                    existingUid = existingUid,
                    replacement = replacement.toEntity(),
                    textContent = textEntities,
                    imageContent = imageEntities,
                    videoContent = videoEntities,
                )
            }
        }

    override fun getRewindsInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<Rewind>> =
        cachedRewindDao
            .getRewindsContainedIn(start, end)
            .map { it.toDomainModels() }

    override suspend fun markAsViewed(uid: Uuid): Unit =
        withContext(ioDispatcher) {
            val now = Clock.System.now()
            // First view: sets is_viewed=1 and view_count=1. No-ops if already viewed.
            val firstViewApplied = cachedRewindDao.markAsFirstViewed(uid, now)
            // Subsequent views: only bump the count if the first-view path didn't fire.
            if (firstViewApplied == 0) {
                cachedRewindDao.incrementViewCount(uid)
            }
        }

    override suspend fun deleteRewind(uid: Uuid): Unit =
        withContext(ioDispatcher) {
            mediaClaimFence.withClaim { cachedRewindDao.deleteRewind(uid) }
            Napier.d("Deleted rewind: $uid")
        }

    override suspend fun tagAsMilestone(
        uid: Uuid,
        signal: String,
    ): Unit =
        withContext(ioDispatcher) {
            // Update the existing row in place. REPLACE would delete it first and
            // cascade into panels and replies even though only metadata changed.
            val rewind = cachedRewindDao.getRewindById(uid).firstOrNull() ?: return@withContext
            val metadataObject =
                rewind.metadata?.let { json ->
                    runCatching { contentJson.decodeFromString<SerializableRewindMetadata>(json) }
                        .getOrNull()
                }
            val updated =
                metadataObject?.let {
                    it.copy(milestones = listOf(signal) + it.milestones, hasDomainMetadata = true)
                } ?: SerializableRewindMetadata(
                    detectedActivities = emptyList(),
                    locationSummary = null,
                    milestones = listOf(signal),
                    peopleHighlighted = emptyList(),
                )
            val encoded = contentJson.encodeToString(updated)
            cachedRewindDao.updateRewind(rewind.copy(metadata = encoded))
            Napier.d("Tagged rewind $uid as milestone: $signal")
        }

    /**
     * Converts a RewindEntity to a domain Rewind model.
     */
    private suspend fun List<RewindEntity>.toDomainModels(): List<Rewind> {
        val contentByRewind = cachedRewindDao.getContentForRewinds(map { it.uid })
        return map { entity ->
            entity.toDomainModel(contentByRewind[entity.uid] ?: StoredRewindContent(emptyList(), emptyList(), emptyList()))
        }
    }

    private suspend fun RewindEntity.toDomainModel(): Rewind = toDomainModel(cachedRewindDao.getContentForRewind(uid))

    private fun RewindEntity.toDomainModel(dbContent: StoredRewindContent): Rewind {
        val content = mutableListOf<RewindContent>()

        // Convert text content
        dbContent.textContent.map { it.toDomainModel() }.let { content.addAll(it) }

        // Convert image content
        dbContent.imageContent.map { it.toDomainModel() }.let { content.addAll(it) }

        // Convert video content
        dbContent.videoContent.map { it.toDomainModel() }.let { content.addAll(it) }

        val storedMetadata =
            metadata?.let { json ->
                runCatching { contentJson.decodeFromString<SerializableRewindMetadata>(json) }
                    .getOrNull()
            }
        val orderedContent = restorePanelOrder(content, storedMetadata?.panelOrder.orEmpty())
        val rewindMetadata = storedMetadata?.takeIf { it.hasDomainMetadata }?.toDomainModel()

        return Rewind(
            uid = uid,
            startDate = startDate,
            endDate = endDate,
            generationDate = generationDate,
            label = label,
            title = title,
            content = orderedContent,
            metadata = rewindMetadata,
            isViewed = isViewed,
            firstViewedAt = firstViewedAt,
            viewCount = viewCount,
        )
    }

    private fun restorePanelOrder(
        content: List<RewindContent>,
        panelOrder: List<String>,
    ): List<RewindContent> {
        if (panelOrder.isEmpty()) return content.sortedBy { it.timestamp }
        val remaining = content.toMutableList()
        val ordered =
            panelOrder.mapNotNull { sourceId ->
                val index = remaining.indexOfFirst { it.sourceId.toString() == sourceId }
                if (index < 0) null else remaining.removeAt(index)
            }
        return ordered + remaining.sortedBy { it.timestamp }
    }

    /**
     * Converts a domain Rewind model to a RewindEntity.
     */
    private fun Rewind.toEntity(): RewindEntity =
        RewindEntity(
            uid = uid,
            startDate = startDate,
            endDate = endDate,
            generationDate = generationDate,
            label = label,
            title = title,
            isViewed = isViewed,
            firstViewedAt = firstViewedAt,
            viewCount = viewCount,
            metadata =
                if (metadata == null && content.isEmpty()) {
                    null
                } else {
                    val stored =
                        metadata?.let { SerializableRewindMetadata.fromDomainModel(it) }
                            ?: SerializableRewindMetadata(
                                detectedActivities = emptyList(),
                                locationSummary = null,
                                milestones = emptyList(),
                                peopleHighlighted = emptyList(),
                                hasDomainMetadata = false,
                            )
                    contentJson.encodeToString(stored.copy(panelOrder = content.map { it.sourceId.toString() }))
                },
        )

    /**
     * Converts a RewindTextContentEntity to a domain RewindContent entry.
     */
    private fun RewindTextContentEntity.toDomainModel(): RewindContent =
        when {
            content.startsWith(NARRATIVE_PREFIX) -> {
                runCatching {
                    contentJson.decodeFromString<NarrativeContextPayload>(
                        content.removePrefix(NARRATIVE_PREFIX),
                    )
                }.map { payload ->
                    RewindContent.NarrativeContext(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        contextText = payload.contextText,
                        backgroundImage = payload.backgroundImage,
                    )
                }.getOrElse {
                    Napier.w("Failed to decode narrative context payload for rewind text content", it)
                    RewindContent.TextNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        content = content,
                    )
                }
            }
            content.startsWith(TRANSITION_PREFIX) -> {
                runCatching {
                    contentJson.decodeFromString<TransitionPayload>(
                        content.removePrefix(TRANSITION_PREFIX),
                    )
                }.map { payload ->
                    RewindContent.Transition(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        transitionText = payload.transitionText,
                    )
                }.getOrElse {
                    Napier.w("Failed to decode transition payload for rewind text content", it)
                    RewindContent.TextNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        content = content,
                    )
                }
            }
            content.startsWith(MAP_PANEL_PREFIX) -> {
                runCatching {
                    contentJson.decodeFromString<MapPanelPayload>(
                        content.removePrefix(MAP_PANEL_PREFIX),
                    )
                }.map { payload ->
                    RewindContent.MapPanel(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        locationPath = payload.locationPath,
                    )
                }.getOrElse {
                    Napier.w("Failed to decode map panel payload for rewind text content", it)
                    RewindContent.TextNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        content = content,
                    )
                }
            }
            content.startsWith(WEATHER_PANEL_PREFIX) -> {
                runCatching {
                    contentJson.decodeFromString<WeatherPanelPayload>(
                        content.removePrefix(WEATHER_PANEL_PREFIX),
                    )
                }.map { payload ->
                    RewindContent.WeatherPanel(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        weather = payload.weather,
                    )
                }.getOrElse {
                    Napier.w("Failed to decode weather panel payload for rewind text content", it)
                    RewindContent.TextNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        content = content,
                    )
                }
            }
            content.startsWith(PERSONALITY_CARD_PREFIX) -> {
                runCatching {
                    contentJson.decodeFromString<PersonalityCardPayload>(
                        content.removePrefix(PERSONALITY_CARD_PREFIX),
                    )
                }.map { payload ->
                    RewindContent.PersonalityCard(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        stats =
                            WeekStatsSnapshot(
                                photoCount = payload.photoCount,
                                textNoteCount = payload.textNoteCount,
                                distinctLocations = payload.distinctLocations,
                                distinctPeople = payload.distinctPeople,
                                newPlaces = payload.newPlaces,
                                audioNoteCount = payload.audioNoteCount,
                            ),
                        dominantActivity = payload.dominantActivity,
                    )
                }.getOrElse {
                    Napier.w("Failed to decode personality card payload for rewind text content", it)
                    RewindContent.TextNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        content = content,
                    )
                }
            }
            content.startsWith(TOP_LIST_PREFIX) -> {
                runCatching {
                    contentJson.decodeFromString<TopListPayload>(
                        content.removePrefix(TOP_LIST_PREFIX),
                    )
                }.map { payload ->
                    RewindContent.TopList(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        kind = payload.kind,
                        items =
                            payload.items.map { item ->
                                TopListItem(
                                    label = item.label,
                                    subtitle = item.subtitle,
                                    count = item.count,
                                    sourceId = item.sourceId?.let { Uuid.parse(it) },
                                )
                            },
                    )
                }.getOrElse {
                    Napier.w("Failed to decode top list payload for rewind text content", it)
                    RewindContent.TextNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        content = content,
                    )
                }
            }
            content.startsWith(AUDIO_NOTE_PREFIX) -> {
                runCatching {
                    contentJson.decodeFromString<AudioNotePayload>(
                        content.removePrefix(AUDIO_NOTE_PREFIX),
                    )
                }.map { payload ->
                    RewindContent.AudioNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        uri = payload.uri,
                        durationMs = payload.durationMs,
                        transcriptionText = payload.transcriptionText,
                        caption = payload.caption,
                    )
                }.getOrElse {
                    Napier.w("Failed to decode audio note payload for rewind text content", it)
                    RewindContent.TextNote(
                        timestamp = timestamp,
                        sourceId = sourceId,
                        content = content,
                    )
                }
            }
            else ->
                RewindContent.TextNote(
                    timestamp = timestamp,
                    sourceId = sourceId,
                    content = content,
                )
        }

    /**
     * Converts a RewindImageContentEntity to a domain Image.
     */
    private fun RewindImageContentEntity.toDomainModel(): RewindContent.Image =
        RewindContent.Image(
            timestamp = timestamp,
            sourceId = sourceId,
            uri = uri,
            caption = caption,
        )

    /**
     * Converts a RewindVideoContentEntity to a domain Video.
     */
    private fun RewindVideoContentEntity.toDomainModel(): RewindContent.Video =
        RewindContent.Video(
            timestamp = timestamp,
            sourceId = sourceId,
            uri = uri,
            caption = caption,
            duration = Duration.parse(duration),
        )

    /**
     * Separates RewindContent items by type and converts to appropriate entities.
     */
    private fun List<RewindContent>.toEntities(
        rewindId: Uuid,
    ): Triple<
        List<RewindTextContentEntity>,
        List<RewindImageContentEntity>,
        List<RewindVideoContentEntity>,
    > {
        val textEntities = mutableListOf<RewindTextContentEntity>()
        val imageEntities = mutableListOf<RewindImageContentEntity>()
        val videoEntities = mutableListOf<RewindVideoContentEntity>()

        forEach { content ->
            when (content) {
                is RewindContent.TextNote -> textEntities.add(content.toTextEntity(rewindId))
                is RewindContent.Image -> imageEntities.add(content.toImageEntity(rewindId))
                is RewindContent.Video -> videoEntities.add(content.toVideoEntity(rewindId))
                is RewindContent.NarrativeContext -> textEntities.add(content.toNarrativeEntity(rewindId))
                is RewindContent.Transition -> textEntities.add(content.toTransitionEntity(rewindId))
                is RewindContent.MapPanel -> textEntities.add(content.toMapPanelEntity(rewindId))
                is RewindContent.WeatherPanel -> textEntities.add(content.toWeatherPanelEntity(rewindId))
                is RewindContent.PersonalityCard -> textEntities.add(content.toPersonalityCardEntity(rewindId))
                is RewindContent.TopList -> textEntities.add(content.toTopListEntity(rewindId))
                is RewindContent.AudioNote -> textEntities.add(content.toAudioNoteEntity(rewindId))
            }
        }

        return Triple(textEntities, imageEntities, videoEntities)
    }

    /**
     * Converts a TextNote to a RewindTextContentEntity.
     */
    private fun RewindContent.TextNote.toTextEntity(rewindId: Uuid): RewindTextContentEntity =
        RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = content,
        )

    private fun RewindContent.NarrativeContext.toNarrativeEntity(rewindId: Uuid): RewindTextContentEntity {
        val payload = NarrativeContextPayload(contextText = contextText, backgroundImage = backgroundImage)
        return RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = NARRATIVE_PREFIX + contentJson.encodeToString(payload),
        )
    }

    private fun RewindContent.Transition.toTransitionEntity(rewindId: Uuid): RewindTextContentEntity {
        val payload = TransitionPayload(transitionText = transitionText)
        return RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = TRANSITION_PREFIX + contentJson.encodeToString(payload),
        )
    }

    private fun RewindContent.MapPanel.toMapPanelEntity(rewindId: Uuid): RewindTextContentEntity {
        val payload = MapPanelPayload(locationPath = locationPath)
        return RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = MAP_PANEL_PREFIX + contentJson.encodeToString(payload),
        )
    }

    private fun RewindContent.WeatherPanel.toWeatherPanelEntity(rewindId: Uuid): RewindTextContentEntity {
        val payload = WeatherPanelPayload(weather = weather)
        return RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = WEATHER_PANEL_PREFIX + contentJson.encodeToString(payload),
        )
    }

    private fun RewindContent.PersonalityCard.toPersonalityCardEntity(rewindId: Uuid): RewindTextContentEntity {
        val payload =
            PersonalityCardPayload(
                photoCount = stats.photoCount,
                textNoteCount = stats.textNoteCount,
                distinctLocations = stats.distinctLocations,
                distinctPeople = stats.distinctPeople,
                newPlaces = stats.newPlaces,
                dominantActivity = dominantActivity,
                audioNoteCount = stats.audioNoteCount,
            )
        return RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = PERSONALITY_CARD_PREFIX + contentJson.encodeToString(payload),
        )
    }

    private fun RewindContent.TopList.toTopListEntity(rewindId: Uuid): RewindTextContentEntity {
        val payload =
            TopListPayload(
                kind = kind,
                items =
                    items.map { item ->
                        SerializableTopListItem(
                            label = item.label,
                            subtitle = item.subtitle,
                            count = item.count,
                            sourceId = item.sourceId?.toString(),
                        )
                    },
            )
        return RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = TOP_LIST_PREFIX + contentJson.encodeToString(payload),
        )
    }

    private fun RewindContent.AudioNote.toAudioNoteEntity(rewindId: Uuid): RewindTextContentEntity {
        val payload =
            AudioNotePayload(
                uri = uri,
                durationMs = durationMs,
                transcriptionText = transcriptionText,
                caption = caption,
            )
        return RewindTextContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            content = AUDIO_NOTE_PREFIX + contentJson.encodeToString(payload),
        )
    }

    /**
     * Converts an Image to a RewindImageContentEntity.
     */
    private fun RewindContent.Image.toImageEntity(rewindId: Uuid): RewindImageContentEntity =
        RewindImageContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            uri = uri,
            caption = caption,
        )

    /**
     * Converts a Video to a RewindVideoContentEntity.
     */
    private fun RewindContent.Video.toVideoEntity(rewindId: Uuid): RewindVideoContentEntity =
        RewindVideoContentEntity(
            id = sourceId,
            rewindId = rewindId,
            sourceId = sourceId,
            timestamp = timestamp,
            uri = uri,
            caption = caption,
            duration = duration.toString(),
        )

    @Serializable
    private data class NarrativeContextPayload(
        val contextText: String,
        val backgroundImage: String?,
    )

    @Serializable
    private data class TransitionPayload(
        val transitionText: String,
    )

    @Serializable
    private data class MapPanelPayload(
        val locationPath: List<MapPoint>,
    )

    @Serializable
    private data class WeatherPanelPayload(
        val weather: WeatherContext,
    )

    @Serializable
    private data class PersonalityCardPayload(
        val photoCount: Int,
        val textNoteCount: Int,
        val distinctLocations: Int,
        val distinctPeople: Int,
        val newPlaces: Int,
        val dominantActivity: ActivityType,
        val audioNoteCount: Int = 0,
    )

    @Serializable
    private data class AudioNotePayload(
        val uri: String,
        val durationMs: Long,
        val transcriptionText: String?,
        val caption: String?,
    )

    @Serializable
    private data class TopListPayload(
        val kind: TopListKind,
        val items: List<SerializableTopListItem>,
    )

    @Serializable
    private data class SerializableTopListItem(
        val label: String,
        val subtitle: String?,
        val count: Int?,
        val sourceId: String?,
    )

    @Serializable
    private data class SerializableRewindMetadata(
        val detectedActivities: List<String>,
        val locationSummary: SerializableLocationSummary?,
        val milestones: List<String>,
        val peopleHighlighted: List<String>,
        val reflectionPrompts: List<ReflectionPrompt> = emptyList(),
        val highlightedQuotes: List<HighlightedQuote> = emptyList(),
        val weatherContext: WeatherContext? = null,
        val locationPath: List<MapPoint> = emptyList(),
        val panelOrder: List<String> = emptyList(),
        val hasDomainMetadata: Boolean = true,
    ) {
        fun toDomainModel(): RewindMetadata =
            RewindMetadata(
                detectedActivities =
                    detectedActivities.mapNotNull { name ->
                        runCatching { ActivityType.valueOf(name) }.getOrNull()
                    },
                locationSummary =
                    locationSummary?.let {
                        LocationSummary(
                            distinctLocations = it.distinctLocations,
                            newPlaces = it.newPlaces,
                            primaryLocation = it.primaryLocation,
                        )
                    },
                milestones = milestones,
                peopleHighlighted = peopleHighlighted,
                reflectionPrompts = reflectionPrompts,
                highlightedQuotes = highlightedQuotes,
                weatherContext = weatherContext,
                locationPath = locationPath,
            )

        companion object {
            fun fromDomainModel(metadata: RewindMetadata): SerializableRewindMetadata =
                SerializableRewindMetadata(
                    detectedActivities = metadata.detectedActivities.map { it.name },
                    locationSummary =
                        metadata.locationSummary?.let {
                            SerializableLocationSummary(
                                distinctLocations = it.distinctLocations,
                                newPlaces = it.newPlaces,
                                primaryLocation = it.primaryLocation,
                            )
                        },
                    milestones = metadata.milestones,
                    peopleHighlighted = metadata.peopleHighlighted,
                    reflectionPrompts = metadata.reflectionPrompts,
                    highlightedQuotes = metadata.highlightedQuotes,
                    weatherContext = metadata.weatherContext,
                    locationPath = metadata.locationPath,
                )
        }
    }

    @Serializable
    private data class SerializableLocationSummary(
        val distinctLocations: Int,
        val newPlaces: Int,
        val primaryLocation: String?,
    )

    private companion object {
        private const val NARRATIVE_PREFIX = "LD_NARRATIVE:"
        private const val TRANSITION_PREFIX = "LD_TRANSITION:"
        private const val MAP_PANEL_PREFIX = "LD_MAP_PANEL:"
        private const val WEATHER_PANEL_PREFIX = "LD_WEATHER_PANEL:"
        private const val PERSONALITY_CARD_PREFIX = "LD_PERSONALITY_CARD:"
        private const val TOP_LIST_PREFIX = "LD_TOP_LIST:"
        private const val AUDIO_NOTE_PREFIX = "LD_AUDIO_NOTE:"
    }
}
