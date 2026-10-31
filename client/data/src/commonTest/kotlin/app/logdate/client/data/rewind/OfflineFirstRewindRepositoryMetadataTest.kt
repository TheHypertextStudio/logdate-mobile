@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.logdate.client.data.rewind

import app.logdate.client.data.fakes.RecordingMediaClaimFence
import app.logdate.client.database.dao.rewind.CachedRewindDao
import app.logdate.client.database.entities.rewind.RewindEntity
import app.logdate.client.database.entities.rewind.RewindImageContentEntity
import app.logdate.client.database.entities.rewind.RewindTextContentEntity
import app.logdate.client.database.entities.rewind.RewindVideoContentEntity
import app.logdate.shared.model.ActivityType
import app.logdate.shared.model.LocationSummary
import app.logdate.shared.model.MapPoint
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import app.logdate.shared.model.RewindMetadata
import app.logdate.shared.model.TopListItem
import app.logdate.shared.model.TopListKind
import app.logdate.shared.model.WeatherCategory
import app.logdate.shared.model.WeatherContext
import app.logdate.shared.model.WeekStatsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies that [OfflineFirstRewindRepository] correctly serializes and deserializes
 * [RewindMetadata] when saving and retrieving rewinds.
 */
class OfflineFirstRewindRepositoryMetadataTest {
    @Test
    fun `rewind media owner writes execute inside the media claim fence`() =
        runTest {
            val fence = RecordingMediaClaimFence()
            var atomicInserts = 0
            val dao =
                FakeCachedRewindDao {
                    assertEquals(true, fence.isInsideClaim, "The claim must surround the atomic insert")
                    atomicInserts++
                }
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler), fence)
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = now,
                    endDate = now,
                    generationDate = now,
                    label = "2025#01",
                    title = "Media owners",
                    content = emptyList(),
                )

            repository.saveRewind(rewind)

            assertEquals(1, fence.claims)
            assertEquals(1, atomicInserts, "The claim must surround the atomic story-and-panels insert")
        }

    @Test
    fun `listing rewinds reads their panels with one query per content table`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))
            val weeks =
                (0 until 6).map { week ->
                    val start = Instant.fromEpochMilliseconds(1_700_000_000_000L + week * 604_800_000L)
                    Rewind(
                        uid = Uuid.random(),
                        startDate = start,
                        endDate = start,
                        generationDate = start,
                        label = "2025#$week",
                        title = "Week $week",
                        content =
                            listOf(
                                RewindContent.TextNote(
                                    timestamp = start,
                                    sourceId = Uuid.random(),
                                    content = "Entry for week $week",
                                ),
                            ),
                    )
                }
            weeks.forEach { repository.saveRewind(it) }
            val before = dao.contentQueries

            val listed = repository.getAllRewinds().first()

            assertEquals(3, dao.contentQueries - before, "Each rewind read its own panels")
            assertEquals(
                weeks.associate { it.uid to it.content.single().sourceId },
                listed.associate { it.uid to it.content.single().sourceId },
            )
        }

    @Test
    fun `metadata round-trips through save and retrieve`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))

            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val metadata =
                RewindMetadata(
                    detectedActivities = listOf(ActivityType.TRAVEL, ActivityType.SOCIAL),
                    locationSummary =
                        LocationSummary(
                            distinctLocations = 4,
                            newPlaces = 1,
                            primaryLocation = "Tokyo",
                        ),
                    milestones = listOf("Found the hidden ramen shop"),
                    peopleHighlighted = listOf("Akira", "Yuki"),
                )
            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = now,
                    endDate = now,
                    generationDate = now,
                    label = "2025#01",
                    title = "A week in Tokyo",
                    content = emptyList(),
                    metadata = metadata,
                )

            repository.saveRewind(rewind)
            val retrieved = repository.getRewind(rewind.uid).first()

            assertNotNull(retrieved.metadata)
            assertEquals(listOf(ActivityType.TRAVEL, ActivityType.SOCIAL), retrieved.metadata!!.detectedActivities)
            assertEquals(4, retrieved.metadata!!.locationSummary?.distinctLocations)
            assertEquals(1, retrieved.metadata!!.locationSummary?.newPlaces)
            assertEquals("Tokyo", retrieved.metadata!!.locationSummary?.primaryLocation)
            assertEquals(listOf("Found the hidden ramen shop"), retrieved.metadata!!.milestones)
            assertEquals(listOf("Akira", "Yuki"), retrieved.metadata!!.peopleHighlighted)
        }

    @Test
    fun `null metadata round-trips correctly`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))

            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = now,
                    endDate = now,
                    generationDate = now,
                    label = "2025#02",
                    title = "Quiet week",
                    content = emptyList(),
                    metadata = null,
                )

            repository.saveRewind(rewind)
            val retrieved = repository.getRewind(rewind.uid).first()

            assertNull(retrieved.metadata)
        }

    @Test
    fun `metadata with null location summary round-trips`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))

            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val metadata =
                RewindMetadata(
                    detectedActivities = listOf(ActivityType.QUIET),
                    locationSummary = null,
                    milestones = emptyList(),
                    peopleHighlighted = emptyList(),
                )
            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = now,
                    endDate = now,
                    generationDate = now,
                    label = "2025#03",
                    title = "Stayed in",
                    content = emptyList(),
                    metadata = metadata,
                )

            repository.saveRewind(rewind)
            val retrieved = repository.getRewind(rewind.uid).first()

            assertNotNull(retrieved.metadata)
            assertNull(retrieved.metadata!!.locationSummary)
            assertEquals(listOf(ActivityType.QUIET), retrieved.metadata!!.detectedActivities)
        }

    @Test
    fun `rich rewind content panels round-trip through save and retrieve`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))

            val start = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val mapTimestamp = Instant.fromEpochMilliseconds(1_700_000_001_000L)
            val weatherTimestamp = Instant.fromEpochMilliseconds(1_700_000_002_000L)
            val personalityTimestamp = Instant.fromEpochMilliseconds(1_700_000_003_000L)
            val topListTimestamp = Instant.fromEpochMilliseconds(1_700_000_004_000L)
            val topListSourceId = Uuid.random()
            val locationPath =
                listOf(
                    MapPoint(latitude = 37.7749, longitude = -122.4194, timestamp = mapTimestamp),
                    MapPoint(latitude = 37.8715, longitude = -122.2730, timestamp = mapTimestamp),
                    MapPoint(latitude = 37.8044, longitude = -122.2712, timestamp = mapTimestamp),
                )
            val weather =
                WeatherContext(
                    category = WeatherCategory.RAINY,
                    avgTempCelsius = 12.5,
                    maxTempCelsius = 16.0,
                    minTempCelsius = 8.0,
                    precipitationMm = 22.4,
                )
            val stats =
                WeekStatsSnapshot(
                    photoCount = 12,
                    textNoteCount = 7,
                    distinctLocations = 4,
                    distinctPeople = 3,
                    newPlaces = 2,
                )
            val topListItems =
                listOf(
                    TopListItem(
                        label = "Maya",
                        subtitle = "4 entries",
                        count = 4,
                        sourceId = topListSourceId,
                    ),
                    TopListItem(
                        label = "Eli",
                        subtitle = "2 entries",
                        count = 2,
                        sourceId = null,
                    ),
                )
            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = start,
                    endDate = topListTimestamp,
                    generationDate = topListTimestamp,
                    label = "2025#04",
                    title = "A richer week",
                    content =
                        listOf(
                            RewindContent.MapPanel(
                                timestamp = mapTimestamp,
                                sourceId = Uuid.random(),
                                locationPath = locationPath,
                                significanceScore = 81f,
                            ),
                            RewindContent.WeatherPanel(
                                timestamp = weatherTimestamp,
                                sourceId = Uuid.random(),
                                weather = weather,
                                significanceScore = 64f,
                            ),
                            RewindContent.PersonalityCard(
                                timestamp = personalityTimestamp,
                                sourceId = Uuid.random(),
                                stats = stats,
                                dominantActivity = ActivityType.SOCIAL,
                                significanceScore = 92f,
                            ),
                            RewindContent.TopList(
                                timestamp = topListTimestamp,
                                sourceId = Uuid.random(),
                                kind = TopListKind.PEOPLE,
                                items = topListItems,
                                significanceScore = 75f,
                            ),
                        ),
                    metadata = null,
                )

            repository.saveRewind(rewind)
            val retrieved = repository.getRewind(rewind.uid).first()

            assertEquals(4, retrieved.content.size)

            val mapPanel = assertIs<RewindContent.MapPanel>(retrieved.content[0])
            assertEquals(locationPath, mapPanel.locationPath)

            val weatherPanel = assertIs<RewindContent.WeatherPanel>(retrieved.content[1])
            assertEquals(weather, weatherPanel.weather)

            val personalityCard = assertIs<RewindContent.PersonalityCard>(retrieved.content[2])
            assertEquals(stats, personalityCard.stats)
            assertEquals(ActivityType.SOCIAL, personalityCard.dominantActivity)

            val topList = assertIs<RewindContent.TopList>(retrieved.content[3])
            assertEquals(TopListKind.PEOPLE, topList.kind)
            assertEquals(topListItems, topList.items)
        }

    @Test
    fun `story panel order survives storage across content tables and structural timestamps`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val opening =
                RewindContent.PersonalityCard(
                    now,
                    Uuid.random(),
                    WeekStatsSnapshot(1, 1, 0, 0),
                    ActivityType.MIXED,
                )
            val image = RewindContent.Image(now, Uuid.random(), "test://image", null)
            val transition = RewindContent.Transition(Instant.DISTANT_PAST, Uuid.random(), "And then")
            val note = RewindContent.TextNote(now, Uuid.random(), "A real moment")
            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = now,
                    endDate = now,
                    generationDate = now,
                    label = "2025#05",
                    title = "A week",
                    content = listOf(opening, image, transition, note),
                    metadata = null,
                )

            repository.saveRewind(rewind)
            val retrieved = repository.getRewind(rewind.uid).first()

            assertEquals(rewind.content.map { it.sourceId }, retrieved.content.map { it.sourceId })
            assertNull(retrieved.metadata)
        }

    @Test
    fun `refresh preserves the rewind identity and its attached replies`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val original =
                Rewind(
                    uid = Uuid.random(),
                    startDate = now,
                    endDate = now,
                    generationDate = now,
                    label = "2025#06",
                    title = "Original",
                    content = emptyList(),
                    isViewed = true,
                    firstViewedAt = now,
                    viewCount = 2,
                )
            repository.saveRewind(original)
            dao.attachReply(original.uid)

            repository.replaceRewind(original.uid, original.copy(title = "Refreshed"))

            assertEquals(original.uid, repository.getRewind(original.uid).first().uid)
            assertEquals("Refreshed", repository.getRewind(original.uid).first().title)
            assertEquals(true, repository.getRewind(original.uid).first().isViewed)
            assertEquals(2, repository.getRewind(original.uid).first().viewCount)
            assertEquals(true, dao.hasAttachedReply(original.uid))
        }

    @Test
    fun `milestone tagging preserves existing panels and attached replies`() =
        runTest {
            val dao = FakeCachedRewindDao()
            val repository = OfflineFirstRewindRepository(dao, UnconfinedTestDispatcher(testScheduler))
            val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
            val note = RewindContent.TextNote(now, Uuid.random(), "An entry")
            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = now,
                    endDate = now,
                    generationDate = now,
                    label = "2025#07",
                    title = "A week",
                    content = listOf(note),
                )
            repository.saveRewind(rewind)
            dao.attachReply(rewind.uid)

            repository.tagAsMilestone(rewind.uid, "FIRST:An entry")

            assertEquals(
                listOf(note.sourceId),
                repository
                    .getRewind(rewind.uid)
                    .first()
                    .content
                    .map { it.sourceId },
            )
            assertEquals(true, dao.hasAttachedReply(rewind.uid))
        }
}

private class FakeCachedRewindDao(
    private val onAtomicInsert: () -> Unit = {},
) : CachedRewindDao {
    private val rewinds = MutableStateFlow<List<RewindEntity>>(emptyList())
    private val textContent = mutableListOf<RewindTextContentEntity>()
    private val imageContent = mutableListOf<RewindImageContentEntity>()
    private val videoContent = mutableListOf<RewindVideoContentEntity>()
    private val replyParents = mutableSetOf<Uuid>()

    fun attachReply(rewindId: Uuid) {
        replyParents.add(rewindId)
    }

    fun hasAttachedReply(rewindId: Uuid): Boolean = rewindId in replyParents

    override fun getAllRewinds(): Flow<List<RewindEntity>> = rewinds

    override fun getRewindById(uid: Uuid): Flow<RewindEntity> = rewinds.map { list -> list.first { it.uid == uid } }

    override fun getRewindForPeriod(
        start: Instant,
        end: Instant,
    ): Flow<RewindEntity?> = rewinds.map { list -> list.firstOrNull { it.startDate == start && it.endDate == end } }

    override fun getRewindsContainedIn(
        rangeStart: Instant,
        rangeEnd: Instant,
    ): Flow<List<RewindEntity>> =
        rewinds.map { list ->
            list
                .filter { it.startDate >= rangeStart && it.endDate <= rangeEnd }
                .sortedBy { it.startDate }
        }

    override suspend fun rewindExistsForPeriod(
        start: Instant,
        end: Instant,
    ): Boolean = rewinds.value.any { it.startDate == start && it.endDate == end }

    override suspend fun insertRewind(rewind: RewindEntity) {
        if (rewinds.value.any { it.uid == rewind.uid }) deleteRewind(rewind.uid)
        rewinds.value = rewinds.value.filterNot { it.uid == rewind.uid } + rewind
    }

    override suspend fun insertRewindWithContent(
        rewind: RewindEntity,
        textContent: List<RewindTextContentEntity>,
        imageContent: List<RewindImageContentEntity>,
        videoContent: List<RewindVideoContentEntity>,
    ) {
        onAtomicInsert()
        super<CachedRewindDao>.insertRewindWithContent(rewind, textContent, imageContent, videoContent)
    }

    override suspend fun updateRewind(rewind: RewindEntity): Int {
        if (rewinds.value.none { it.uid == rewind.uid }) return 0
        rewinds.value = rewinds.value.map { if (it.uid == rewind.uid) rewind else it }
        return 1
    }

    override suspend fun deleteTextContentForRewind(uid: Uuid) {
        textContent.removeAll { it.rewindId == uid }
    }

    override suspend fun deleteImageContentForRewind(uid: Uuid) {
        imageContent.removeAll { it.rewindId == uid }
    }

    override suspend fun deleteVideoContentForRewind(uid: Uuid) {
        videoContent.removeAll { it.rewindId == uid }
    }

    var contentQueries = 0
        private set

    override suspend fun getTextContentForRewind(rewindId: Uuid): List<RewindTextContentEntity> {
        contentQueries++
        return textContent.filter { it.rewindId == rewindId }
    }

    override suspend fun getImageContentForRewind(rewindId: Uuid): List<RewindImageContentEntity> {
        contentQueries++
        return imageContent.filter { it.rewindId == rewindId }
    }

    override suspend fun getVideoContentForRewind(rewindId: Uuid): List<RewindVideoContentEntity> {
        contentQueries++
        return videoContent.filter { it.rewindId == rewindId }
    }

    override suspend fun getTextContentForRewinds(rewindIds: List<Uuid>): List<RewindTextContentEntity> {
        contentQueries++
        return textContent.filter { it.rewindId in rewindIds }
    }

    override suspend fun getImageContentForRewinds(rewindIds: List<Uuid>): List<RewindImageContentEntity> {
        contentQueries++
        return imageContent.filter { it.rewindId in rewindIds }
    }

    override suspend fun getVideoContentForRewinds(rewindIds: List<Uuid>): List<RewindVideoContentEntity> {
        contentQueries++
        return videoContent.filter { it.rewindId in rewindIds }
    }

    override suspend fun insertTextContent(content: List<RewindTextContentEntity>) {
        textContent.addAll(content)
    }

    override suspend fun insertImageContent(content: List<RewindImageContentEntity>) {
        imageContent.addAll(content)
    }

    override suspend fun insertVideoContent(content: List<RewindVideoContentEntity>) {
        videoContent.addAll(content)
    }

    override suspend fun markAsFirstViewed(
        uid: Uuid,
        now: Instant,
    ): Int {
        val existing = rewinds.value.firstOrNull { it.uid == uid } ?: return 0
        if (existing.isViewed) return 0
        rewinds.value =
            rewinds.value.map { rewind ->
                if (rewind.uid == uid) {
                    rewind.copy(
                        isViewed = true,
                        firstViewedAt = now,
                        viewCount = 1,
                    )
                } else {
                    rewind
                }
            }
        return 1
    }

    override suspend fun incrementViewCount(uid: Uuid) {
        rewinds.value =
            rewinds.value.map { rewind ->
                if (rewind.uid == uid && rewind.isViewed) {
                    rewind.copy(viewCount = rewind.viewCount + 1)
                } else {
                    rewind
                }
            }
    }

    override suspend fun deleteRewind(uid: Uuid) {
        replyParents.remove(uid)
        rewinds.value = rewinds.value.filterNot { it.uid == uid }
        textContent.removeAll { it.rewindId == uid }
        imageContent.removeAll { it.rewindId == uid }
        videoContent.removeAll { it.rewindId == uid }
    }
}
