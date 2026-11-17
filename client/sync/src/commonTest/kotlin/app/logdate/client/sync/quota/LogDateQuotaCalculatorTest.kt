package app.logdate.client.sync.quota

import app.logdate.client.database.dao.StorageMetadataDao
import app.logdate.client.database.dao.StorageSummary
import app.logdate.client.database.entities.StorageContentType
import app.logdate.client.database.entities.StorageMetadataEntity
import app.logdate.shared.model.CloudObjectType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/**
 * Tests for [LogDateQuotaCalculator].
 *
 * What a user is told they have stored is the number this produces, so the cases that matter are
 * the ones where it could be wrong without looking wrong: a category that is not tracked at all,
 * a total that silently disagrees with its parts, and a corrupt size that would otherwise be
 * reported as fact.
 */
class LogDateQuotaCalculatorTest {
    @Test
    fun `category usage reads the size and count for that category only`() =
        runTest {
            val dao =
                FakeStorageMetadataDao(
                    sizes = mapOf(StorageContentType.IMAGE_NOTE to 2_048L),
                    counts = mapOf(StorageContentType.IMAGE_NOTE to 3),
                )

            val usage = LogDateQuotaCalculator(dao).calculateCategoryUsage(CloudObjectType.IMAGE_NOTES)

            assertEquals(CloudObjectType.IMAGE_NOTES, usage.category)
            assertEquals(2_048L, usage.sizeBytes)
            assertEquals(3, usage.objectCount)
        }

    @Test
    fun `category usage is zero for a category with nothing stored`() =
        runTest {
            val usage =
                LogDateQuotaCalculator(FakeStorageMetadataDao())
                    .calculateCategoryUsage(CloudObjectType.VOICE_NOTES)

            assertEquals(0L, usage.sizeBytes)
            assertEquals(0, usage.objectCount)
        }

    @Test
    fun `every cloud object type is accounted for`() =
        runTest {
            val quota = LogDateQuotaCalculator(FakeStorageMetadataDao()).calculateTotalUsage()

            assertEquals(
                CloudObjectType.entries.toSet(),
                quota.categories.map { it.category }.toSet(),
            )
        }

    @Test
    fun `the total used is the sum of its categories`() =
        runTest {
            val dao =
                FakeStorageMetadataDao(
                    sizes =
                        mapOf(
                            StorageContentType.TEXT_NOTE to 100L,
                            StorageContentType.IMAGE_NOTE to 2_000L,
                            StorageContentType.VOICE_NOTE to 30L,
                        ),
                )

            val quota = LogDateQuotaCalculator(dao).calculateTotalUsage()

            assertEquals(2_130L, quota.usedBytes)
            assertEquals(quota.categories.sumOf { it.sizeBytes }, quota.usedBytes)
        }

    /**
     * A negative total means the stored metadata is corrupt. Reporting it as a usage figure would
     * present nonsense as fact, and can make the remaining allowance look larger than it is.
     */
    @Test
    fun `a negative stored size fails rather than being reported`() =
        runTest {
            val dao = FakeStorageMetadataDao(sizes = mapOf(StorageContentType.TEXT_NOTE to -1L))

            assertFailsWith<IllegalStateException> {
                LogDateQuotaCalculator(dao).calculateCategoryUsage(CloudObjectType.TEXT_NOTES)
            }
        }

    @Test
    fun `an implausibly large stored size fails rather than being reported`() =
        runTest {
            val hundredTerabytes = 100L * 1024L * 1024L * 1024L * 1024L
            val dao =
                FakeStorageMetadataDao(
                    sizes = mapOf(StorageContentType.IMAGE_NOTE to hundredTerabytes + 1),
                )

            assertFailsWith<IllegalStateException> {
                LogDateQuotaCalculator(dao).calculateCategoryUsage(CloudObjectType.IMAGE_NOTES)
            }
        }
}

/**
 * Answers only the two queries the calculator makes, from whatever the test set up.
 *
 * Exclusion from quota is enforced by the DAO's own queries rather than by the calculator, so it
 * is not modelled here.
 */
private class FakeStorageMetadataDao(
    private val sizes: Map<StorageContentType, Long> = emptyMap(),
    private val counts: Map<StorageContentType, Int> = emptyMap(),
) : StorageMetadataDao {
    override suspend fun getTotalSizeByType(contentType: StorageContentType): Long = sizes[contentType] ?: 0L

    override suspend fun getObjectCountByType(contentType: StorageContentType): Int = counts[contentType] ?: 0

    override suspend fun getStorageMetadata(contentId: Uuid): StorageMetadataEntity? = null

    override fun observeStorageMetadata(contentId: Uuid): Flow<StorageMetadataEntity?> = flowOf(null)

    override suspend fun getStorageMetadataByType(contentType: StorageContentType): List<StorageMetadataEntity> = emptyList()

    override suspend fun getStorageSummaryByType(): List<StorageSummary> = emptyList()

    override suspend fun getTotalStorageUsage(): Long = sizes.values.sum()

    override suspend fun upsertStorageMetadata(metadata: StorageMetadataEntity) = Unit

    override suspend fun insertStorageMetadata(metadata: StorageMetadataEntity) = Unit

    override suspend fun removeStorageMetadata(contentId: Uuid) = Unit

    override suspend fun removeStorageMetadata(contentIds: List<Uuid>) = Unit

    override suspend fun updateStorageSize(
        contentId: Uuid,
        newSizeBytes: Long,
        timestamp: Long,
    ) = Unit

    override suspend fun updateQuotaExclusion(
        contentId: Uuid,
        exclude: Boolean,
        timestamp: Long,
    ) = Unit

    override suspend fun getExcludedFromQuota(): List<StorageMetadataEntity> = emptyList()
}
