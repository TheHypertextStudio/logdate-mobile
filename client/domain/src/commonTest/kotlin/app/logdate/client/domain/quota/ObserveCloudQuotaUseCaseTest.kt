package app.logdate.client.domain.quota

import app.logdate.shared.model.CloudObjectType
import app.logdate.shared.model.CloudQuotaManager
import app.logdate.shared.model.CloudQuotaSnapshot
import app.logdate.shared.model.CloudStorageQuota
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Unit tests for [ObserveCloudQuotaUseCase].
 *
 * Validates the reactive observation of cloud storage quota updates, ensuring the
 * flow correctly propagates information about total capacity, used bytes, and
 * category-specific usage from the [CloudQuotaManager].
 */
class ObserveCloudQuotaUseCaseTest {
    private class MockCloudQuotaManager : CloudQuotaManager {
        var observeQuotaResult: Flow<CloudStorageQuota> =
            flowOf(
                CloudStorageQuota(
                    totalBytes = 0L,
                    usedBytes = 0L,
                    categories = emptyList(),
                ),
            )
        var currentQuota: CloudStorageQuota =
            CloudStorageQuota(
                totalBytes = 0L,
                usedBytes = 0L,
                categories = emptyList(),
            )
        var getCurrentQuotaCalls = 0

        override fun observeQuota(): Flow<CloudStorageQuota> = observeQuotaResult

        override suspend fun getCurrentQuota(): CloudStorageQuota {
            getCurrentQuotaCalls += 1
            return currentQuota
        }

        override suspend fun recordObjectCreation(
            objectType: CloudObjectType,
            bytes: Long,
        ) {}

        override suspend fun recordObjectDeletion(
            objectType: CloudObjectType,
            bytes: Long,
        ) {}

        override suspend fun recordObjectUpdate(
            objectType: CloudObjectType,
            oldBytes: Long,
            newBytes: Long,
        ) {}

        override suspend fun recalculateQuota(): CloudStorageQuota = currentQuota

        override suspend fun setQuotaLimit(totalBytes: Long) {
            currentQuota = currentQuota.copy(totalBytes = totalBytes)
        }

        override suspend fun syncWithServer(): CloudStorageQuota = currentQuota

        override suspend fun getLastServerSyncTime(): Instant? = null
    }

    @Test
    fun `a legacy quota manager cannot assert an account for its cached quota`() =
        runTest {
            val manager = MockCloudQuotaManager()
            val snapshot = ObserveCloudQuotaUseCase(manager).observeSnapshots().toList().single()
            assertIs<CloudQuotaSnapshot.Unavailable>(snapshot)
            assertEquals(null, snapshot.accountId)
            assertEquals(0, manager.getCurrentQuotaCalls)
        }

    @Test
    fun `invoke should return flow of quota from quota manager`() =
        runTest {
            // Given
            val mockQuotaManager = MockCloudQuotaManager()
            val expectedQuota =
                CloudStorageQuota(
                    totalBytes = 1000000000L, // 1GB
                    usedBytes = 250000000L, // 250MB
                    categories = emptyList(),
                )
            mockQuotaManager.observeQuotaResult = flowOf(expectedQuota)
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            // When
            val result = useCase()

            // Then
            val emittedValues = result.toList()
            assertEquals(1, emittedValues.size)
            assertEquals(expectedQuota, emittedValues.first())
            assertEquals(1, mockQuotaManager.getCurrentQuotaCalls)
        }

    @Test
    fun `invoke should request current quota when collection starts`() =
        runTest {
            val mockQuotaManager = MockCloudQuotaManager()
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            useCase().toList()

            assertEquals(1, mockQuotaManager.getCurrentQuotaCalls)
        }

    @Test
    fun `invoke should return empty quota when quota manager returns zeros`() =
        runTest {
            // Given
            val mockQuotaManager = MockCloudQuotaManager()
            val emptyQuota =
                CloudStorageQuota(
                    totalBytes = 0L,
                    usedBytes = 0L,
                    categories = emptyList(),
                )
            mockQuotaManager.observeQuotaResult = flowOf(emptyQuota)
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            // When
            val result = useCase()

            // Then
            val emittedValues = result.toList()
            assertEquals(1, emittedValues.size)
            assertEquals(emptyQuota, emittedValues.first())
        }

    @Test
    fun `invoke should return quota at capacity when storage is full`() =
        runTest {
            // Given
            val mockQuotaManager = MockCloudQuotaManager()
            val fullQuota =
                CloudStorageQuota(
                    totalBytes = 1000000000L, // 1GB
                    usedBytes = 1000000000L, // 1GB (full)
                    categories = emptyList(),
                )
            mockQuotaManager.observeQuotaResult = flowOf(fullQuota)
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            // When
            val result = useCase()

            // Then
            val emittedValues = result.toList()
            assertEquals(1, emittedValues.size)
            assertEquals(fullQuota, emittedValues.first())
        }

    @Test
    fun `invoke should return quota with no usage when storage is empty`() =
        runTest {
            // Given
            val mockQuotaManager = MockCloudQuotaManager()
            val emptyQuota =
                CloudStorageQuota(
                    totalBytes = 1000000000L, // 1GB
                    usedBytes = 0L, // 0 bytes used
                    categories = emptyList(),
                )
            mockQuotaManager.observeQuotaResult = flowOf(emptyQuota)
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            // When
            val result = useCase()

            // Then
            val emittedValues = result.toList()
            assertEquals(1, emittedValues.size)
            assertEquals(emptyQuota, emittedValues.first())
        }

    @Test
    fun `invoke should handle multiple quota emissions from manager`() =
        runTest {
            // Given
            val mockQuotaManager = MockCloudQuotaManager()
            val firstQuota =
                CloudStorageQuota(
                    totalBytes = 1000000000L,
                    usedBytes = 100000000L, // 100MB
                    categories = emptyList(),
                )
            val secondQuota =
                CloudStorageQuota(
                    totalBytes = 1000000000L,
                    usedBytes = 200000000L, // 200MB (increased usage)
                    categories = emptyList(),
                )
            mockQuotaManager.observeQuotaResult = flowOf(firstQuota, secondQuota)
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            // When
            val result = useCase()

            // Then
            val emittedValues = result.toList()
            assertEquals(2, emittedValues.size)
            assertEquals(firstQuota, emittedValues[0])
            assertEquals(secondQuota, emittedValues[1])
        }

    @Test
    fun `invoke should handle quota transitions from unknown to available`() =
        runTest {
            // Given
            val mockQuotaManager = MockCloudQuotaManager()
            val availableQuota =
                CloudStorageQuota(
                    totalBytes = 2000000000L, // 2GB
                    usedBytes = 500000000L, // 500MB
                    categories = emptyList(),
                )
            val emptyQuota =
                CloudStorageQuota(
                    totalBytes = 0L,
                    usedBytes = 0L,
                    categories = emptyList(),
                )
            mockQuotaManager.observeQuotaResult = flowOf(emptyQuota, availableQuota)
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            // When
            val result = useCase()

            // Then
            val emittedValues = result.toList()
            assertEquals(2, emittedValues.size)
            assertEquals(emptyQuota, emittedValues[0])
            assertEquals(availableQuota, emittedValues[1])
        }

    @Test
    fun `invoke should handle large quota values`() =
        runTest {
            // Given
            val mockQuotaManager = MockCloudQuotaManager()
            val largeQuota =
                CloudStorageQuota(
                    totalBytes = 1000000000000L, // 1TB
                    usedBytes = 250000000000L, // 250GB
                    categories = emptyList(),
                )
            mockQuotaManager.observeQuotaResult = flowOf(largeQuota)
            val useCase = ObserveCloudQuotaUseCase(mockQuotaManager)

            // When
            val result = useCase()

            // Then
            val emittedValues = result.toList()
            assertEquals(1, emittedValues.size)
            assertEquals(largeQuota, emittedValues.first())
        }
}
