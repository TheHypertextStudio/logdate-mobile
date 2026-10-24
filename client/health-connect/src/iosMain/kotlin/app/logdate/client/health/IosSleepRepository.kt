@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.logdate.client.health

import app.logdate.client.health.model.SleepSession
import app.logdate.client.health.model.SleepStage
import app.logdate.client.health.model.SleepStageType
import app.logdate.client.health.model.TimeOfDay
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSDate
import platform.Foundation.NSSortDescriptor
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.HKAuthorizationStatusSharingAuthorized
import platform.HealthKit.HKCategorySample
import platform.HealthKit.HKCategoryTypeIdentifierSleepAnalysis
import platform.HealthKit.HKCategoryValueSleepAnalysisAsleep
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKSampleSortIdentifierStartDate
import platform.HealthKit.predicateForSamplesWithStartDate
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * iOS [SleepRepository] backed by HealthKit's `HKCategoryTypeIdentifierSleepAnalysis`.
 *
 * HealthKit reads require:
 * - The `com.apple.developer.healthkit` entitlement on the iOS app target.
 * - `NSHealthShareUsageDescription` in Info.plist (added separately).
 * - The user explicitly granting read access via [requestSleepPermissions].
 *
 * Without the entitlement HealthKit calls fail at runtime; with the entitlement and authorization
 * the queries below return real sleep data from the user's Apple Health database.
 */
class IosSleepRepository(
    private val healthStore: HKHealthStore = HKHealthStore(),
) : SleepRepository {
    private val sleepType: HKObjectType? = HKObjectType.categoryTypeForIdentifier(HKCategoryTypeIdentifierSleepAnalysis)

    override suspend fun hasSleepPermissions(): Boolean {
        if (!HKHealthStore.isHealthDataAvailable()) return false
        val type = sleepType ?: return false
        return healthStore.authorizationStatusForType(type) == HKAuthorizationStatusSharingAuthorized
    }

    override suspend fun requestSleepPermissions(): Boolean {
        if (!HKHealthStore.isHealthDataAvailable()) return false
        val type = sleepType ?: return false
        return suspendCancellableCoroutine { continuation ->
            healthStore.requestAuthorizationToShareTypes(
                typesToShare = null,
                readTypes = setOf(type),
            ) { success, error ->
                if (error != null) {
                    Napier.w("HealthKit authorization failed: ${error.localizedDescription}")
                }
                if (continuation.isActive) continuation.resume(success)
            }
        }
    }

    override suspend fun getSleepSessions(
        start: Instant,
        end: Instant,
    ): List<SleepSession> {
        if (!HKHealthStore.isHealthDataAvailable()) return emptyList()
        val type = sleepType ?: return emptyList()
        val samples = querySleepSamples(type, start, end)
        return groupSamplesIntoSessions(samples)
    }

    override suspend fun getAverageWakeUpTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? = computeAverage(timeZone, days, useEnd = true)

    override suspend fun getAverageSleepTime(
        timeZone: TimeZone,
        days: Int,
    ): TimeOfDay? = computeAverage(timeZone, days, useEnd = false)

    private suspend fun computeAverage(
        timeZone: TimeZone,
        days: Int,
        useEnd: Boolean,
    ): TimeOfDay? {
        val now = Clock.System.now()
        val start = now - days.days
        val sessions = getSleepSessions(start, now)
        if (sessions.isEmpty()) return null
        val totalMinutes =
            sessions.sumOf { session ->
                val instant = if (useEnd) session.endTime else session.startTime
                val local = instant.toLocalDateTime(timeZone)
                local.hour * 60 + local.minute
            }
        val average = totalMinutes / sessions.size
        return TimeOfDay(hour = average / 60, minute = average % 60)
    }

    private suspend fun querySleepSamples(
        type: HKObjectType,
        start: Instant,
        end: Instant,
    ): List<HKCategorySample> {
        val startDate = NSDate.dateWithTimeIntervalSince1970(start.epochSeconds.toDouble())
        val endDate = NSDate.dateWithTimeIntervalSince1970(end.epochSeconds.toDouble())
        val predicate =
            HKSampleQuery.predicateForSamplesWithStartDate(
                startDate = startDate,
                endDate = endDate,
                options = 0u,
            )
        val sortAscending =
            NSSortDescriptor.sortDescriptorWithKey(
                key = HKSampleSortIdentifierStartDate,
                ascending = true,
            )
        return suspendCancellableCoroutine { continuation ->
            val query =
                HKSampleQuery(
                    sampleType = type as platform.HealthKit.HKSampleType,
                    predicate = predicate,
                    limit = 0u, // HKObjectQueryNoLimit
                    sortDescriptors = listOf(sortAscending),
                    resultsHandler = { _, results, error ->
                        if (!continuation.isActive) return@HKSampleQuery
                        if (error != null) {
                            Napier.w("HealthKit sleep query failed: ${error.localizedDescription}")
                            continuation.resume(emptyList())
                            return@HKSampleQuery
                        }
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume((results as? List<HKCategorySample>).orEmpty())
                    },
                )
            healthStore.executeQuery(query)
            continuation.invokeOnCancellation { healthStore.stopQuery(query) }
        }
    }

    /**
     * Groups consecutive sleep-stage samples into sessions. Two samples that are within
     * [SESSION_GAP] of each other are merged; a larger gap starts a new session.
     */
    private fun groupSamplesIntoSessions(samples: List<HKCategorySample>): List<SleepSession> {
        if (samples.isEmpty()) return emptyList()
        val sessions = mutableListOf<MutableList<HKCategorySample>>()
        var current = mutableListOf<HKCategorySample>()
        for (sample in samples) {
            if (current.isEmpty()) {
                current += sample
                continue
            }
            val previousEnd = current.last().endDate.toInstant()
            val nextStart = sample.startDate.toInstant()
            if (nextStart - previousEnd > SESSION_GAP) {
                sessions += current
                current = mutableListOf()
            }
            current += sample
        }
        if (current.isNotEmpty()) sessions += current
        return sessions.map { it.toSession() }
    }

    private fun MutableList<HKCategorySample>.toSession(): SleepSession {
        val first = first()
        val last = last()
        val sourceName = first.sourceRevision.source.name
        val stages =
            this
                .filter { it.value == HKCategoryValueSleepAnalysisAsleep || it.value > HKCategoryValueSleepAnalysisAsleep }
                .map { sample ->
                    SleepStage(
                        type = sample.value.toStageType(),
                        startTime = sample.startDate.toInstant(),
                        endTime = sample.endDate.toInstant(),
                    )
                }
        return SleepSession(
            id = first.UUID.UUIDString(),
            startTime = first.startDate.toInstant(),
            endTime = last.endDate.toInstant(),
            sourceAppName = sourceName,
            stages = stages,
        )
    }

    companion object {
        private val SESSION_GAP: Duration = 30.minutes
    }
}

private fun NSDate.toInstant(): Instant = Instant.fromEpochMilliseconds((timeIntervalSince1970 * 1000.0).toLong())

@Suppress("MagicNumber")
private fun Long.toStageType(): SleepStageType =
    when (this) {
        // HKCategoryValueSleepAnalysisInBed = 0 → not really a sleep stage
        0L -> SleepStageType.UNKNOWN

        // HKCategoryValueSleepAnalysisAsleep (legacy) = 1
        1L -> SleepStageType.LIGHT

        // HKCategoryValueSleepAnalysisAwake = 2
        2L -> SleepStageType.AWAKE

        // iOS 16+: HKCategoryValueSleepAnalysisAsleepCore = 3
        3L -> SleepStageType.LIGHT

        // iOS 16+: HKCategoryValueSleepAnalysisAsleepDeep = 4
        4L -> SleepStageType.DEEP

        // iOS 16+: HKCategoryValueSleepAnalysisAsleepREM = 5
        5L -> SleepStageType.REM

        else -> SleepStageType.UNKNOWN
    }
